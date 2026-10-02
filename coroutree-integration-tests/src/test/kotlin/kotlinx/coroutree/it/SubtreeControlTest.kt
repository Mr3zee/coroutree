package kotlinx.coroutree.it

import kotlinx.coroutree.model.PaceDef
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * More of execution control per subtree and of its commands, on the `PaceControl` sample (DESIGN §3.1 "Per subtree",
 * "Control channel"; §12 "M1.2 as built", settings): what the other test classes of execution control leave out.
 */
@Timeout(180)
class SubtreeControlTest {
    /** §12: "so 'pause everything, then `resume 42`' runs one subtree alone". */
    @Test
    fun withEverythingPausedOneSubtreeCanBeResumedAlone() {
        startPaceControl("Subtree-alone").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val right = controller.nodeNamed("right")
            val rightTicker = controller.nodeNamed("right-ticker-1")
            controller.nodeNamed("left-ticker-1")
            controller.command("pause")
            controller.awaitQuiet()

            val set = controller.command("resume", scope = right)
            assertFalse(set.paused)
            assertFalse(set.dropped)
            assertEquals(PaceDef.Reason.CONTROLLER, set.reason)
            val subtree = controller.events.subtreeOf(right)
            assertTrue(rightTicker in subtree)
            controller.assertGoesOn("the resumed subtree runs", atLeast = 50) { it.nodeId == rightTicker }
            controller.assertStandsStill("everything else is still paused", millis = 600) { it.nodeId !in subtree }
            val state = controller.snapshot().pace
            assertEquals(true, state.global?.paused)
            assertEquals(mapOf(right to false), state.nodes.mapValues { it.value.paused })

            // Back under the global setting, it stops with the rest.
            assertTrue(controller.command("inherit", scope = right).dropped)
            controller.awaitQuiet()
            controller.assertStandsStill("the subtree follows the program's setting again", millis = 400)
            controller.command("resume")
            controller.assertGoesOn("resumed, everything runs") { it.nodeId !in subtree }
            program.stopAndAwaitExit()
        }
    }

    /** A pace on one subtree spaces that subtree's sequences and nobody else's; a second client sets it, the first reads it. */
    @Test
    fun aPaceOnOneSubtreeSlowsThatSubtreeAndNothingElse() {
        startPaceControl("Subtree-paced").use { program ->
            val watcher = program.connect()
            val controller = program.connect()
            program.awaitOutput("ready")
            val right = watcher.nodeNamed("right")
            val rightTicker = watcher.nodeNamed("right-ticker-1")
            val leftTicker = watcher.nodeNamed("left-ticker-1")
            val orphan = watcher.nodeNamed("orphan")

            val interval = 100_000_000L
            controller.send("pace $interval $right")
            val set = watcher.awaitPace("the subtree's pace, as the other client set it") { it.scopeNodeId == right }
            assertEquals(interval, set.intervalNanos)
            assertFalse(set.paused)
            Thread.sleep(2_500)
            controller.send("inherit $right")
            val unset = watcher.awaitPace("the end of the subtree's pace") { it.scopeNodeId == right && it.dropped }

            val from = set.timeNanos + 300_000_000
            val window = watcher.events.filter { it.timeNanos in from..unset.timeNanos }
            val subtree = watcher.events.subtreeOf(right)
            PacedCorpusTest.assertStepsKeepTheirDistance(watcher.snapshot(), interval, window.filter { it.nodeId in subtree })
            val intervals = (unset.timeNanos - from) / interval
            val ofRight = window.steps.count { it.nodeId == rightTicker }
            assertTrue(ofRight in 2..intervals + 1, "the paced ticker made $ofRight steps in $intervals intervals")
            // The others tick every 5 ms as before: several times what the pace would leave them.
            for ((name, node) in listOf("left-ticker-1" to leftTicker, "orphan" to orphan)) {
                val steps = window.steps.count { it.nodeId == node }
                assertTrue(steps >= intervals * 4, "$name, outside the paced subtree, made only $steps steps in $intervals intervals of that pace")
            }
            assertEquals(true, watcher.snapshot().pace.global?.isOpen, "the global setting was never touched")
            watcher.assertGoesOn("the subtree runs free again", atLeast = 30) { it.nodeId == rightTicker }
            program.stopAndAwaitExit()
        }
    }

    /** §12: "`step n` of a scope that is not paused pauses it and grants n (→ in a running session: stop after one more); on a paused scope permits add up". */
    @Test
    fun stepAskedOfARunningProgramStopsItAndStepsAddUpFromThere() {
        startPaceControl("Step-running").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            controller.assertGoesOn("the program runs")
            val stopped = controller.command("step 3")
            assertTrue(stopped.paused, "a step asked of a running program pauses it")
            assertEquals(3, stopped.steps)
            controller.awaitQuiet()
            controller.assertStandsStill("stopped after its steps", millis = 500)
            assertEquals(true, controller.snapshot().pace.global?.paused)

            // At rest every permit is used up, and from here it is exact.
            val before = controller.steps
            controller.send("step 2")
            controller.send("step 2")
            controller.await("four more steps") { controller.steps >= before + 4 }
            controller.assertStandsStill("two times 'step 2' is four steps", millis = 400) { !it.sameStep }
            assertEquals(before + 4, controller.steps)

            // 'pause' takes back what was granted and not used: nothing moves after it.
            controller.awaitQuiet()
            controller.command("pause")
            controller.assertStandsStill("paused", millis = 300)
            controller.command("resume")
            controller.assertGoesOn("resumed")
            program.stopAndAwaitExit()
        }
    }

    /**
     * §12: "A well-formed command about an unknown node still makes its sender a controller", and TRACE_FORMAT: "Unknown
     * lines, unknown nodes and nodes that have ended are ignored". So the command changes nothing — and when its
     * sender leaves, the last controller has left: a program that was started paused runs (§3.1 "Fail open").
     */
    @Test
    fun aCommandAboutAnUnknownNodeChangesNothingButItsSenderIsAController() {
        startPaceControl("Unknown-node", mapOf("pace.paused" to "true")).use { program ->
            val watcher = program.connect()
            val lost = program.connect()
            watcher.awaitPace("the configured setting") { it.reason == PaceDef.Reason.CONFIG && it.paused }
            lost.send("pause 987654321")
            lost.send("resume 987654321")
            lost.send("step 5 987654321")
            lost.send("inherit 987654321")
            watcher.assertStandsStill("commands about a node that does not exist resume nothing", millis = 600)
            assertEquals(listOf(PaceDef.Reason.CONFIG), watcher.paceDefs.map { it.reason })
            assertFalse("ready" in program.output)

            lost.close()
            val open = watcher.awaitPace("the fail-open") { it.reason == PaceDef.Reason.FAIL_OPEN }
            assertFalse(open.paused)
            assertEquals(0, open.scopeNodeId)
            program.awaitOutput("ready")
            program.stopAndAwaitExit()
        }
    }

    /** TRACE_FORMAT: "Intervals above a day are a day." */
    @Test
    fun anIntervalAboveADayIsADay() {
        startPaceControl("Pace-a-day").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val day = 86_400_000_000_000L
            assertEquals(day, controller.command("pace 9000000000000000000").intervalNanos)
            assertEquals(day, controller.command("pace ${day + 1}").intervalNanos)
            assertEquals(day - 1, controller.command("pace ${day - 1}").intervalNanos)
            controller.awaitQuiet()
            // Whoever is held for a day by now is let go by the next setting, not a day later.
            assertEquals(0, controller.command("pace 0").intervalNanos)
            controller.assertGoesOn("unpaced again, the program runs", atLeast = 50)
            program.stopAndAwaitExit()
        }
    }
}
