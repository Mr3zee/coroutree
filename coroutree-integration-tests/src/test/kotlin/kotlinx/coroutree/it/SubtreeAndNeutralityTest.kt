package kotlinx.coroutree.it

import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.PaceDef
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Execution control per subtree, and what a hold must leave alone (DESIGN §10, "Per subtree" and "Neutral"), on the
 * `PaceControl` sample.
 */
@Timeout(180)
class SubtreeAndNeutralityTest {
    @Test
    fun aPausedSubtreeGetsNoEventsWhileItsSiblingRunsOnAndAnOutsiderIsHeldAtItsDoor() {
        startPaceControl("Subtree-pause").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val left = controller.nodeNamed("left")
            val leftTicker = controller.nodeNamed("left-ticker-1")
            controller.nodeNamed("left-ticker-2")
            val rightTicker = controller.nodeNamed("right-ticker-1")
            controller.assertGoesOn("the left subtree runs") { it.nodeId == leftTicker }

            val set = controller.command("pause", scope = left)
            assertTrue(set.paused)
            assertFalse(set.dropped)
            val subtree = controller.events.subtreeOf(left)
            assertTrue(leftTicker in subtree && rightTicker !in subtree, "$subtree")
            controller.awaitQuiet { it.nodeId in subtree }
            val rightBefore = controller.events.count { it.nodeId == rightTicker }
            controller.assertStandsStill("the left subtree is paused", millis = 800) { it.nodeId in subtree || it.threadId == left }
            assertTrue(controller.events.count { it.nodeId == rightTicker } >= rightBefore + 50, "a sibling subtree with a thread of its own runs on at full speed")
            assertEquals(mapOf(left to true), controller.snapshot().pace.nodes.mapValues { it.value.paused })
            assertEquals(true, controller.snapshot().pace.global?.isOpen, "the global setting is untouched")

            // Nothing happens *to* a paused subtree: the main thread, which is not part of it, cancels a coroutine in it.
            program.tell("cancel-left")
            assertFalse(program.saysWithin(500, "cancel-left done"), "the outsider is held at the event, before the cancellation happens")
            assertTrue(controller.events.none { it.kind == EventKind.CANCELLATION_REQUESTED && it.nodeId == leftTicker })

            val dropped = controller.command("inherit", scope = left)
            assertTrue(dropped.dropped)
            program.awaitOutput("cancel-left done")
            controller.await("the cancellation") { frames -> frames.any { it.event?.kind == EventKind.CANCELLATION_REQUESTED && it.event?.nodeId == leftTicker } }
            controller.assertGoesOn("'inherit' lifts the pause") { it.nodeId in subtree }
            assertTrue(controller.snapshot().pace.nodes.isEmpty())
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun theEndOfANodeLiftsItsSettingAndWhatItStartedRunsOn() {
        startPaceControl("Subtree-end-of-node").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val parent = controller.nodeNamed("parent")
            val orphan = controller.nodeNamed("orphan")
            controller.assertGoesOn("the orphan ticks") { it.nodeId == orphan }

            controller.command("pause", scope = parent)
            assertTrue(orphan in controller.events.subtreeOf(parent), "a thread hangs under the thread that started it")
            controller.awaitQuiet { it.nodeId == orphan }
            controller.assertStandsStill("what the paused thread started is paused with it", millis = 400) { it.nodeId == orphan }

            // The parent thread ends — which takes steps, and it is paused: it is stepped to its end. The orphan takes
            // permits of the same setting, so how many it needs is not known in advance.
            program.tell("end-parent")
            var lifted: PaceDef? = null
            for (round in 1..400) {
                controller.send("step 1 $parent")
                if (controller.holdsWithin(50) { frames -> frames.any { it.pace?.reason == PaceDef.Reason.NODE_FINISHED } }) {
                    lifted = controller.paceDefs.first { it.reason == PaceDef.Reason.NODE_FINISHED }
                    break
                }
            }
            assertNotNull(lifted, "the parent thread never ended")
            assertEquals(parent, lifted.scopeNodeId)
            assertTrue(lifted.dropped)
            assertTrue(controller.events.any { it.kind == EventKind.FINISHED && it.nodeId == parent })
            controller.assertGoesOn("with its parent's setting gone the orphan runs free", atLeast = 100) { it.nodeId == orphan }
            assertTrue(controller.snapshot().pace.nodes.isEmpty())

            // A setting for a node that has ended is not taken.
            val defsBefore = controller.paceDefs.size
            controller.send("pause $parent")
            controller.command("pace 0") // a command that does answer, to know the one before has been read
            assertEquals(defsBefore + 1, controller.paceDefs.size)
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun aThreadInterruptedWhileHeldKeepsItsFlagIsReportedOnceAndBurnsNoCpu() {
        startPaceControl("Neutral-interrupt").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val target = controller.nodeNamed("target")
            val victim = controller.nodeNamed("victim")
            controller.command("pause", scope = target)

            // The victim cancels the paused coroutine: an outsider, held at that event. Its own thread is not paused, so
            // the main thread's interrupt gets through to it — while it is held.
            program.tell("cancel-target")
            program.awaitOutput("cancel-target done")
            assertFalse(controller.holdsWithin(400) { frames -> frames.any { it.event?.kind == EventKind.CANCELLATION_REQUESTED && it.event?.nodeId == target } })
            program.tell("interrupt-victim")
            controller.await("the interrupt") { frames -> frames.any { it.event?.kind == EventKind.THREAD_INTERRUPTED && it.event?.nodeId == victim } }
            Thread.sleep(1_000) // a second in which a thread that mishandles the flag would spin
            assertFalse("victim interrupted" in program.output, "the interrupt does not get the thread through the gate")

            controller.command("resume", scope = target)
            program.awaitOutput("victim interrupted=")
            val report = Regex("victim interrupted=(\\w+) cpuMillis=(\\d+)").find(program.output)
            assertNotNull(report, program.output)
            assertEquals("true", report.groupValues[1], "the thread comes out of the hold with the interrupt it was sent")
            assertTrue(report.groupValues[2].toLong() < 300, "being held for more than a second cost ${report.groupValues[2]} ms of CPU")

            val run = program.stopAndAwaitExit()
            val ofVictim = run.snapshot.events.filter { it.nodeId == victim }
            assertEquals(1, ofVictim.count { it.kind == EventKind.THREAD_INTERRUPTED }, "one interrupt, not a second one for putting the flag back")
            // Not a trace of the hold itself: the victim only ever blocks where the program makes it, in its queue.
            assertEquals(setOf(BlockReason.PARK), ofVictim.filter { it.kind == EventKind.THREAD_BLOCKED }.map { it.blockReason }.toSet())
            assertEquals(ofVictim.count { it.kind == EventKind.THREAD_BLOCKED }, ofVictim.count { it.kind == EventKind.THREAD_UNBLOCKED })
            val cancellation = run.snapshot.events.single { it.kind == EventKind.CANCELLATION_REQUESTED && it.nodeId == target }
            assertTrue(cancellation.heldNanos >= 1_000_000_000, "what the hold cost is said where it belongs: ${cancellation.heldNanos}")
        }
    }

    @Test
    fun aHeldThreadThatOwnsAMonitorOthersWantCausesNoDeadlock() {
        startPaceControl("Neutral-monitor").use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            val lockedTarget = controller.nodeNamed("locked-target")
            controller.command("pause", scope = lockedTarget)
            program.tell("cancel-locked")
            assertFalse(program.saysWithin(800, "owner released"), "the owner is held inside its synchronized block")
            assertFalse("contender entered" in program.output, "and the contender waits for the monitor, like for any slow thread")
            controller.assertGoesOn("everybody else runs on")
            controller.command("resume", scope = lockedTarget)
            program.awaitOutput("owner released")
            program.awaitOutput("contender entered")
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun blockingCallsAreBalancedOnEveryThreadAfterARunThatWasHeldEverywhere() {
        // Every thread of the program is held again and again, inside sleep, park, wait and the monitor probe's callbacks.
        // With -Dcoroutree.debug (which every test JVM has) the agent checks its books when a thread ends and reports
        // an imbalance as an error, which stopAndAwaitExit does not let pass.
        startPaceControl("Neutral-balance", mapOf("pace.events.per.second" to "400")).use { program ->
            val controller = program.connect()
            program.awaitOutput("ready")
            repeat(5) {
                controller.command("pause")
                Thread.sleep(60)
                controller.command("resume")
                Thread.sleep(60)
            }
            program.tell("cancel-target")
            program.tell("cancel-locked")
            program.tell("end-parent")
            program.awaitOutput("contender entered")
            val run = program.stopAndAwaitExit()
            val events = run.snapshot.events
            for ((thread, ofThread) in events.filter { it.kind == EventKind.THREAD_BLOCKED || it.kind == EventKind.THREAD_UNBLOCKED }.groupBy { it.nodeId }) {
                val finished = events.any { it.kind == EventKind.FINISHED && it.nodeId == thread }
                if (!finished) continue
                assertEquals(
                    ofThread.count { it.kind == EventKind.THREAD_BLOCKED }, ofThread.count { it.kind == EventKind.THREAD_UNBLOCKED },
                    "blocked and unblocked of thread ${run.snapshot.node(thread)?.info?.name}",
                )
            }
        }
    }
}
