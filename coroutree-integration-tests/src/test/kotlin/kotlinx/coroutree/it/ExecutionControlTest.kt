package kotlinx.coroutree.it

import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.PaceDef
import org.junit.jupiter.api.Timeout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Stopping, pacing and stepping a real program from its live socket, the way the GUI does it (DESIGN §3.1, §10
 * "Exact"). The program is the `PaceControl` sample: seven threads and five coroutines that keep producing events
 * until told to stop, so that every promise is tested with sequences running in parallel.
 */
@Timeout(180)
class ExecutionControlTest {
    @Test
    fun startedPausedTheTraceDoesNotGrowAndStepAddsExactlyThatManySteps() {
        startPaceControl("Exact-steps", mapOf("pace.paused" to "true")).use { program ->
            val controller = program.connect()
            val config = controller.awaitPace("the configured setting") { it.reason == PaceDef.Reason.CONFIG }
            assertTrue(config.paused)
            assertEquals(0, config.scopeNodeId)
            controller.assertStandsStill("started paused, the program does not get to its first event", millis = 600)
            assertEquals(0, controller.events.size)
            assertFalse("ready" in program.output, "the main thread is held at its first event, before it has started anything")

            var expected = 0
            for (n in listOf(1, 3, 1, 5, 2, 17, 1)) {
                val answer = controller.command("step $n")
                assertEquals(n, answer.steps)
                assertTrue(answer.paused)
                expected += n
                controller.await("$expected steps") { controller.steps >= expected }
                // Steps, not events: a step may be several events (launched + context changed + dispatcher changed),
                // and the later ones of the last step may still be on their way.
                controller.assertStandsStill("'step $n' lets exactly $n steps through", millis = 300) { !it.sameStep }
                assertEquals(expected, controller.steps, "after 'step $n'")
            }
            // Five presses in quick succession are five steps, not one.
            repeat(5) { controller.send("step 1") }
            expected += 5
            controller.await("$expected steps") { controller.steps >= expected }
            controller.assertStandsStill("five times 'step 1' is five steps", millis = 300) { !it.sameStep }
            assertEquals(expected, controller.steps)

            controller.awaitQuiet()
            val events = controller.events
            assertEquals((1L..events.size).toList(), events.map { it.seq }, "stepping skips no sequence number")
            // An event that continues a step continues what its own thread was just reporting.
            val lastOfThread = HashMap<Long, Event>()
            for (event in events) {
                if (event.sameStep) assertTrue(lastOfThread[event.threadId] != null, "#${event.seq} continues a step that its thread never began")
                lastOfThread[event.threadId] = event
            }
            assertTrue(events.first().heldNanos >= 500_000_000, "the first event says how long its thread was held before it: ${events.first().heldNanos}")

            controller.command("resume")
            program.awaitOutput("ready")
            controller.assertGoesOn("resumed, the program runs")
            val run = program.stopAndAwaitExit()
            assertEquals(true, run.snapshot.pace.global?.isOpen, "the recorded trace ends with the gate as the last command left it")
        }
    }

    @Test
    fun pauseStopsARunningProgramAndResumeLetsItGoOn() {
        startPaceControl("Pause-resume").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            controller.assertGoesOn("the program runs")
            repeat(3) { round ->
                val paused = controller.command("pause")
                assertTrue(paused.paused)
                assertEquals(PaceDef.Reason.CONTROLLER, paused.reason)
                // A hook that was past the gate when the pause came completes; after that, nothing.
                controller.awaitQuiet()
                controller.assertStandsStill("paused (round $round)", millis = 500)
                val resumed = controller.command("resume")
                assertFalse(resumed.paused)
                controller.assertGoesOn("resumed (round $round)")
            }
            val run = program.stopAndAwaitExit()
            // Nothing of the three holds is in the trace but the time they took and the settings that caused them.
            val held = run.snapshot.events.sumOf { it.heldNanos }
            assertTrue(held >= 3 * 500_000_000L, "three pauses of half a second and more were accounted for as ${held / 1_000_000} ms held")
            assertEquals(listOf(true, false, true, false, true, false), run.snapshot.paceChanges.filter { it.reason == PaceDef.Reason.CONTROLLER }.map { it.paused })
        }
    }

    @Test
    fun aPaceSetWhileTheProgramRunsSpacesEverySequenceAndSerialisesNone() {
        startPaceControl("Pace-live").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val orphan = controller.nodeNamed("orphan")
            val leftOne = controller.nodeNamed("left-ticker-1")
            val leftTwo = controller.nodeNamed("left-ticker-2")
            val right = controller.nodeNamed("right-ticker-1")
            controller.assertGoesOn("the program runs")

            val interval = 40_000_000L
            val set = controller.command("pace $interval")
            assertEquals(interval, set.intervalNanos)
            Thread.sleep(3_000)
            val unset = controller.command("pace 0")
            assertEquals(0, unset.intervalNanos)

            // Steps that passed the gate before the pace was set kept no time; from a moment later on every step has.
            val from = set.timeNanos + 200_000_000
            val paced = controller.events.filter { it.timeNanos in from..unset.timeNanos }
            val snapshot = controller.snapshot()
            PacedCorpusTest.assertStepsKeepTheirDistance(snapshot, interval, paced)

            // Parallel sequences are paced independently: each one with a thread of its own gets what the pace allows,
            // not a share of it. (The window holds 70 intervals; a global order of turns would leave each of the many
            // sequences of this program a fraction of that.)
            val window = (unset.timeNanos - from) / interval
            for ((name, node) in listOf("orphan" to orphan, "right-ticker-1" to right)) {
                val steps = paced.steps.count { it.nodeId == node }
                assertTrue(steps <= window + 1, "$name made $steps steps in $window intervals: faster than the pace")
                assertTrue(steps >= window * 6 / 10, "$name made only $steps steps in $window intervals: it waited for somebody else")
            }

            // Concurrent counts as parallel: the two tickers of the left event loop share a thread and are two
            // sequences all the same, never spaced against each other. What they do share is the thread, which can be
            // held for one of them at a time: each gets less than the pace allows, as the design says.
            val one = paced.steps.filter { it.nodeId == leftOne }
            val two = paced.steps.filter { it.nodeId == leftTwo }
            assertTrue(one.size >= 10 && two.size >= 10, "both tickers of the shared thread make progress: ${one.size}, ${two.size}")
            val closest = one.minOf { a -> two.minOf { b -> abs(a.timeNanos - b.timeNanos) } }
            assertTrue(closest < interval / 4, "steps of two coroutines on one thread are never closer than $closest ns: they are being spaced against each other")

            controller.assertGoesOn("unpaced again, the program runs", atLeast = 50)
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun timeTheProgramSpendsBlockedOfItsOwnAccordCountsTowardsTheInterval() {
        // delay(5) against a pace of one step in 2 ms: a ticker that resumes 5 ms after it suspended is not held at all.
        startPaceControl("Pace-minimum", mapOf("pace.events.per.second" to "500")).use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val ticker = controller.nodeNamed("right-ticker-1")
            controller.await("the ticker to tick") { frames -> frames.count { it.event?.nodeId == ticker && it.event?.kind == EventKind.RESUMED } >= 40 }
            val ofTicker = controller.events.filter { it.nodeId == ticker }
            val resumes = ofTicker.filter { it.kind == EventKind.RESUMED }.drop(1)
            val heldResumes = resumes.filter { it.heldNanos != 0L }
            assertTrue(heldResumes.isEmpty(), "resumed after a delay longer than the interval, and held all the same: ${heldResumes.map { it.heldNanos }}")
            assertTrue(ofTicker.any { it.kind == EventKind.SUSPENDED && it.heldNanos > 0 }, "the suspension that follows a resume at once is what the pace holds")
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun aConfiguredPaceIsWhatTheRunStartsWith() {
        startPaceControl("Pace-configured", mapOf("pace.events.per.second" to "40")).use { program ->
            val controller = program.connect()
            val config = controller.awaitPace("the configured setting") { it.reason == PaceDef.Reason.CONFIG }
            assertEquals(25_000_000, config.intervalNanos)
            assertFalse(config.paused)
            program.awaitOutput("ready")
            controller.assertGoesOn("the program runs", atLeast = 60)
            PacedCorpusTest.assertStepsKeepTheirDistance(controller.snapshot(), 25_000_000, controller.events)
            // Faster from the GUI, then the rest at full speed.
            controller.command("pace 0")
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun heldTimeIsAccountedForPerThreadAndNeverExceedsTheClock() {
        startPaceControl("Held-nanos", mapOf("pace.events.per.second" to "100")).use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            controller.assertGoesOn("the program runs", atLeast = 200)
            controller.command("pace 0")
            val run = program.stopAndAwaitExit()
            val events = run.snapshot.events
            val total = events.maxOf { it.timeNanos }
            for ((thread, ofThread) in events.groupBy { it.threadId }) {
                val held = ofThread.sumOf { it.heldNanos }
                assertTrue(held <= total, "thread $thread was held for $held ns of a run of $total ns")
                // Between two events of one thread lies at least what the second one says it was held for.
                for ((previous, next) in ofThread.zipWithNext()) {
                    val between = next.timeNanos - previous.timeNanos
                    if (next.heldNanos > between + 2_000_000) {
                        fail("Event #${next.seq} says its thread was held for ${next.heldNanos} ns, but only $between ns lie between it and #${previous.seq}")
                    }
                }
            }
            assertTrue(events.none { it.sameStep && it.heldNanos != 0L })
            assertTrue(events.filter(Event::sameStep).all { it.seq > 1 })
        }
    }

    /**
     * Thousands of coroutines on Dispatchers.Default, and a controller that will not leave the gate alone: paused and
     * resumed, slowed down and let go, stepped, a pool and a coroutine given settings of their own and losing them
     * again, all while the workers race through it. The program must come out the other end as if nothing had happened.
     */
    @Test
    fun aControllerThatNeverStopsChangingItsMindDoesNotBreakABusyProgram() {
        val started = startUnderAgent("samples.StressKt", runName = "Stress-controlled", agentOptions = mapOf("live" to "true", "pace.paused" to "true"), programArgs = listOf("3000", "4"))
        try {
            started.awaitSession().connect().use { controller ->
                controller.command("resume")
                var round = 0
                fun send(command: String) = runCatching { controller.send(command) } // the program may be done any moment
                while (!controller.streamEnded && round < 400) {
                    val someNode = controller.events.lastOrNull()?.nodeId ?: 0
                    val pool = controller.events.firstOrNull { it.node?.kind == kotlinx.coroutree.model.NodeKind.POOL }?.nodeId ?: 0
                    when (round++ % 8) {
                        0 -> send("pause")
                        1 -> send("step 50")
                        2 -> send("resume")
                        3 -> send("pace 200000")
                        4 -> if (pool != 0L) send("pause $pool")
                        5 -> if (someNode != 0L) send("pace 1000000 $someNode")
                        6 -> if (pool != 0L) send("inherit $pool")
                        else -> send("pace 0")
                    }
                    Thread.sleep(15)
                }
                // Whatever state that left the gate in: open it, and let the program finish.
                send("resume")
                send("pace 0")
                for (node in controller.snapshot().pace.nodes.keys) send("inherit $node")
                controller.awaitEnd(120_000)
            }
            val run = started.await(120)
            assertEquals(0, run.exitCode, run.output)
            val snapshot = run.snapshot
            assertEquals(emptyList(), snapshot.diagnostics.filter { it.severity != kotlinx.coroutree.model.Diagnostic.Severity.INFO }.map { it.message })
            assertEquals((1L..snapshot.events.size).toList(), snapshot.events.map { it.seq })
            val coroutines = snapshot.nodes.values.filter { it.info.kind == kotlinx.coroutree.model.NodeKind.COROUTINE }
            assertTrue(coroutines.size >= 3000, "${coroutines.size} coroutines")
            assertTrue(coroutines.all { it.state == kotlinx.coroutree.model.NodeState.COMPLETED }, "every coroutine ran to its end: " + coroutines.groupingBy { it.state }.eachCount())
            assertTrue(snapshot.pace.nodes.keys.all { snapshot.node(it)?.state?.isFinal == false }, "no setting is left on a node that has ended: ${snapshot.pace.nodes}")
        } finally {
            started.process.destroyForcibly()
        }
    }

    @Test
    fun stepOnOneSubtreeLetsThatManyThroughThereAndNowhereElse() {
        startPaceControl("Step-subtree").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val right = controller.nodeNamed("right")
            controller.nodeNamed("right-ticker-1")
            controller.command("pause")
            controller.awaitQuiet()
            val before = controller.events.size
            val answer = controller.command("step 4", scope = right)
            assertEquals(4, answer.steps)
            assertTrue(answer.paused, "the subtree's own setting starts as the pause that governed it")
            controller.await("four steps") { controller.events.size >= before + 4 }
            controller.assertStandsStill("'step 4' on a subtree", millis = 400)
            val added = controller.events.drop(before)
            assertEquals(4, added.steps.size)
            val subtree = controller.events.subtreeOf(right)
            assertTrue(added.all { it.nodeId in subtree }, "steps of other subtrees got through: ${added.map { it.nodeId }} vs $subtree")
            controller.command("inherit", scope = right)
            controller.command("resume")
            program.stopAndAwaitExit()
        }
    }
}
