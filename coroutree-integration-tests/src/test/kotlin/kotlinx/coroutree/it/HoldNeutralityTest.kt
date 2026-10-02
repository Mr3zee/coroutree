package kotlinx.coroutree.it

import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A hold must leave alone what is the thread's (DESIGN §3.1 "Not disturbing", §12 "M1.2 as built", the first row):
 * **the park permit** — "a thread that was unparked, then held inside the hook of its own `park()`, would have its
 * permit eaten by the hold's first tick and block forever in the real park" — and **the interrupt flag** — "The wait
 * clears the flag, remembers it, and sets it again before it returns to the program". Against a real JVM, the
 * `PaceNeutrality` sample, on platform and virtual threads, with the thread held exactly where it matters.
 */
@Timeout(180)
class HoldNeutralityTest {
    private fun start(runName: String): ControlledProgram {
        val started = startUnderAgent("samples.PaceNeutralityKt", runName = runName, agentOptions = mapOf("live" to "true"), keepStdinOpen = true)
        return try {
            ControlledProgram(started, started.awaitSession())
        } catch (e: Throwable) {
            started.process.destroyForcibly()
            throw e
        }
    }

    /**
     * The events of [node], minus what golden trees leave out as well: threads that start at the same moment run into
     * each other on the JVM's class-loading monitors, which is blocking, reported, and different in every run.
     */
    private fun LiveClient.eventsOf(node: Long): List<Event> {
        val result = ArrayList<Event>()
        var inMonitor = false
        for (event in events) {
            if (event.nodeId != node) continue
            if (event.kind == EventKind.THREAD_BLOCKED && event.blockReason == BlockReason.MONITOR) inMonitor = true
            else if (event.kind == EventKind.THREAD_UNBLOCKED && inMonitor) inMonitor = false
            else result += event
        }
        return result
    }

    private fun LiveClient.describe(node: Long): String {
        val snapshot = snapshot()
        return events.filter { it.nodeId == node }.joinToString("\n", prefix = "\n") { event ->
            "  #${event.seq} ${event.kind} ${event.blockReason} sameStep=${event.sameStep} held=${event.heldNanos / 1_000_000}ms at " +
                event.stack.take(6).mapNotNull { snapshot.frame(it) }.joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.line}" }
        } + "\n" + paceDefs.joinToString("\n") { "  pace after #${it.afterSeq}: scope=${it.scopeNodeId} paused=${it.paused} steps=${it.steps} ${it.reason}" }
    }

    private fun LiveClient.awaitEvent(what: String, node: Long, kind: EventKind) {
        await(what) { eventsOf(node).any { it.kind == kind } }
    }

    private fun LiveClient.awaitParked(name: String, node: Long) {
        await("$name to wait for its order") { eventsOf(node).any { it.kind == EventKind.THREAD_BLOCKED && it.blockReason == BlockReason.PARK } }
    }

    /**
     * Brings the thread [name] to its own `LockSupport.park()` and holds it inside the hook of that call: its subtree is
     * paused, `go` wakes it from the queue it waits on (held at *unblocked*), one step lets it out of the queue and
     * into the park, where there is no permit of the gate left.
     */
    private fun ControlledProgram.holdInItsOwnPark(controller: LiveClient, name: String, node: Long, beforeItGetsThere: () -> Unit = {}) {
        controller.awaitParked(name, node)
        controller.command("pause", scope = node)
        tell("go $name")
        awaitOutput("go $name done")
        beforeItGetsThere()
        controller.command("step 1", scope = node)
        controller.awaitEvent("$name to come out of the queue", node, kind = EventKind.THREAD_UNBLOCKED)
        Thread.sleep(300) // it calls park(), and is held in front of it for many ticks of the gate
        assertEquals(1, controller.eventsOf(node).count { it.kind == EventKind.THREAD_BLOCKED }, "$name is held before its park is reported: " + controller.describe(node))
        assertFalse("$name passed" in output)
    }

    private fun ControlledProgram.assertPassedItsPark(controller: LiveClient, name: String, node: Long) {
        controller.command("resume", scope = node)
        // Without its permit the thread is parked for good; the program's `stop` would then be what gets it out.
        awaitOutput("$name passed", timeoutMillis = 10_000)
        controller.awaitEvent("the end of $name", node, kind = EventKind.FINISHED)
        assertEquals(
            listOf(EventKind.LAUNCHED, EventKind.THREAD_BLOCKED, EventKind.THREAD_UNBLOCKED, EventKind.THREAD_BLOCKED, EventKind.THREAD_UNBLOCKED, EventKind.FINISHED),
            controller.eventsOf(node).map { it.kind },
            "what $name did is what the program made it do: wait for its order, park once",
        )
        assertEquals(setOf(BlockReason.PARK), controller.eventsOf(node).filter { it.kind == EventKind.THREAD_BLOCKED }.map { it.blockReason }.toSet())
    }

    @Test
    fun anUnparkMadeBeforeAThreadIsHeldInItsOwnParkSurvivesTheHold() {
        start("Neutral-permit-early").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val node = controller.nodeNamed("parker-early")
            program.holdInItsOwnPark(controller, "parker-early", node, beforeItGetsThere = {
                // The thread is out of the queue's own park and held on its way back: the permit is for the park to come.
                controller.awaitQuiet { it.nodeId == node }
                program.tell("unpark parker-early")
                program.awaitOutput("unpark parker-early done")
            })
            program.assertPassedItsPark(controller, "parker-early", node)
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun anUnparkMadeWhileAThreadIsHeldInItsOwnParkSurvivesTheHold() {
        start("Neutral-permit-late").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val node = controller.nodeNamed("parker-late")
            program.holdInItsOwnPark(controller, "parker-late", node)
            program.tell("unpark parker-late")
            program.awaitOutput("unpark parker-late done")
            Thread.sleep(300)
            assertFalse("parker-late passed" in program.output, "the unpark does not get the thread through the gate")
            program.assertPassedItsPark(controller, "parker-late", node)
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun theSameOnAVirtualThread() {
        start("Neutral-permit-virtual").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val node = controller.nodeNamed("virtual-parker")
            program.holdInItsOwnPark(controller, "virtual-parker", node)
            program.tell("unpark virtual-parker")
            program.awaitOutput("unpark virtual-parker done")
            Thread.sleep(300)
            assertFalse("virtual-parker passed" in program.output)
            program.assertPassedItsPark(controller, "virtual-parker", node)
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun aThreadThatIsHeldWithItsInterruptFlagSetComesOutWithTheFlagSet() {
        start("Neutral-flag").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val node = controller.nodeNamed("flagged")
            controller.awaitParked("flagged", node)
            controller.command("pause", scope = node)
            program.tell("go flagged")
            program.awaitOutput("go flagged done")
            // Out of the queue, then the interrupt of itself. The next thing it does is sleep, and that is where it is held:
            // interrupted already, so that the hold has a flag to put aside and to put back.
            controller.command("step 2", scope = node)
            controller.awaitEvent("flagged to interrupt itself", node, kind = EventKind.THREAD_INTERRUPTED)
            Thread.sleep(500)
            assertFalse("flagged sleep" in program.output, "the flag does not get the thread through the gate")
            assertTrue(controller.eventsOf(node).none { it.kind == EventKind.THREAD_BLOCKED && it.blockReason == BlockReason.SLEEP })

            controller.command("resume", scope = node)
            // A sleep that does not find the flag lasts a minute.
            program.awaitOutput("flagged sleep interrupted=true", timeoutMillis = 10_000)
            controller.awaitEvent("the end of flagged", node, kind = EventKind.FINISHED)
            assertEquals(
                listOf(
                    EventKind.LAUNCHED, EventKind.THREAD_BLOCKED, EventKind.THREAD_UNBLOCKED, EventKind.THREAD_INTERRUPTED,
                    EventKind.THREAD_BLOCKED, EventKind.THREAD_UNBLOCKED, EventKind.EXCEPTION_HANDLED, EventKind.FINISHED,
                ),
                controller.eventsOf(node).map { it.kind },
                "one interrupt, its own; nothing for the flag being put back",
            )
            val held = controller.eventsOf(node).filter { it.kind == EventKind.THREAD_BLOCKED }.last().heldNanos
            assertTrue(held >= 400_000_000, "the hold is in heldNanos of the event it delayed and nowhere else: $held")
            program.stopAndAwaitExit()
        }
    }
}
