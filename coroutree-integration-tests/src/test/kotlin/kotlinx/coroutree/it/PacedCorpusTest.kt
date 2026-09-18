package kotlinx.coroutree.it

import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.HandledBy
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.tree.TraceSnapshot
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The first hard requirement of execution control (DESIGN §3.1, §10): **the hold is invisible.** The whole corpus is
 * run again slowed down, and again started paused and stepped to its end by a controller, and both times the tree
 * must be the one of the unpaced golden: no extra THREAD_BLOCKED, interrupt, state or node, and no complaint of the
 * agent — which includes an event that did not pass the gate. A run that hangs fails with a thread dump.
 *
 * The paced runs are also where exactness is checked over everything the corpus does: any two steps of one sequence
 * are at least the interval apart, with no tolerance.
 */
class PacedCorpusTest {
    @TestFactory
    fun pacedCorpusMatchesTheUnpacedGoldens(): List<DynamicTest> = GoldenTreeTest.SAMPLES.map { sample ->
        dynamicTest("$sample paced") {
            val intervalNanos = if (sample in WALL_CLOCK_SENSITIVE) FAST_INTERVAL_NANOS else INTERVAL_NANOS
            val run = runUnderAgent(
                "samples.${sample}Kt",
                runName = "$sample-paced",
                agentOptions = mapOf("pace.events.per.second" to (1e9 / intervalNanos).toString()),
            )
            assertEquals(0, run.exitCode, run.output)
            GoldenTreeTest.check(sample, run, mayUpdate = false)

            val snapshot = run.snapshot
            assertEquals(true, snapshot.header?.paceable, "a configured pace makes a gate, live socket or not")
            assertEquals(
                listOf(PaceDef.Reason.CONFIG),
                snapshot.paceChanges.map { it.reason }.filter { it != PaceDef.Reason.SHUTDOWN },
                "nobody changes the pace of a run without a live socket",
            )
            assertEquals(intervalNanos, snapshot.paceChanges.first().intervalNanos)
            assertStepsKeepTheirDistance(snapshot, intervalNanos)
            assertTrue(snapshot.events.all { it.heldNanos >= 0 })
            assertTrue(snapshot.events.sumOf { it.heldNanos } > 0, "a run this slow was held somewhere")
            assertTrue(snapshot.events.none { it.sameStep && it.heldNanos != 0L }, "a step is held before its first event, never inside")
        }
    }

    @TestFactory
    fun steppedCorpusMatchesTheUnpacedGoldens(): List<DynamicTest> = GoldenTreeTest.SAMPLES.map { sample ->
        dynamicTest("$sample stepped") {
            val started = startUnderAgent(
                "samples.${sample}Kt",
                runName = "$sample-stepped",
                agentOptions = mapOf("live" to "true", "pace.paused" to "true"),
            )
            try {
                val session = started.awaitSession()
                assertTrue(session.paceable, session.text)
                session.connect().use { controller ->
                    if (sample in WALL_CLOCK_SENSITIVE) {
                        // Every step still takes a permit, but none waits for the controller, who keeps a stock of
                        // them: a round trip per step (a tick of the gate at least) is more than the margins of these
                        // samples allow. Time is not held (DESIGN §3.1).
                        var granted = 0
                        while (!controller.streamEnded) {
                            if (granted - controller.steps < 1000) {
                                controller.send("step 2000")
                                granted += 2000
                            }
                            controller.holdsWithin(5) { granted - controller.steps < 1000 }
                        }
                    } else {
                        // One step at a time, the next one asked for when the last has shown; when the program is
                        // busy with itself (a delay, a sleep) permits may run ahead, which is a user pressing → twice.
                        var seen = 0
                        while (!controller.streamEnded) {
                            controller.send("step 1")
                            controller.holdsWithin(100) { controller.steps > seen }
                            seen = controller.steps
                        }
                    }
                    controller.awaitEnd()
                }
                val run = started.await()
                assertEquals(0, run.exitCode, run.output)
                GoldenTreeTest.check(sample, run, mayUpdate = false)
                val changes = run.snapshot.paceChanges
                assertEquals(PaceDef.Reason.CONFIG, changes.first().reason)
                assertTrue(changes.first().paused, "started paused")
                assertTrue(changes.any { it.reason == PaceDef.Reason.CONTROLLER && it.steps > 0 })
            } finally {
                started.process.destroyForcibly()
            }
        }
    }

    companion object {
        const val INTERVAL_NANOS = 5_000_000L
        const val FAST_INTERVAL_NANOS = 500_000L

        /**
         * Samples whose tree depends on wall-clock margins, by name and with the reason, as §10 asks: they are paced at an
         * interval far below their margins and stepped on permits handed out in advance, not skipped. Time is not held
         * (DESIGN §3.1): a program that is held for longer than its own margins is a program that behaves differently, and
         * says so truthfully. The others are deterministic whatever the clock does — one thread, delays in its own event
         * loop — and are stepped for real, one step per round trip, which takes a tick of the gate at the very least.
         */
        val WALL_CLOCK_SENSITIVE: Set<String> = setOf(
            // withTimeout(20): a body held for longer than that is cancelled before it gets to suspend.
            "Timeouts",
            // delay(10), then the parent is cancelled: children that are held for 10 ms before they start never run.
            "Cancellation",
            // delay() outside an event loop goes to the library's timer thread, which the first such delay starts from
            // inside itself. That Thread.start is a step: held there for longer than the delay lasts (5 to 50 ms here), the
            // coroutine finds itself resumed already and does not suspend at all.
            "ContextAndDispatchers", "ScopeEdgeCases", "DispatcherThreads", "SuspendMain", "DeferredHeldException", "SupervisorAndHandler",
            // Who does what first is settled by sleeping: "the holder has the lock by now", "the sleeper sleeps by now", 50 ms each.
            "Threads", "VirtualThreads", "MonitorContention",
        )

        /**
         * Two steps of one sequence — made by the same flow, or happening to the same node — are never closer than the
         * interval. Flows are rebuilt from the tree: a scope, a context change, runBlocking and suspend fun main run in
         * their caller's place. The flow a step was made by is taken only where the trace says it for certain; the node
         * it happened to is always known.
         */
        fun assertStepsKeepTheirDistance(snapshot: TraceSnapshot, intervalNanos: Long, events: List<Event> = snapshot.events) {
            val steps = events.filter { !it.sameStep }
            val lastOnSlot = HashMap<Long, Event>()
            for (step in steps.sortedBy { it.timeNanos }) {
                for (slot in setOfNotNull(step.nodeId, unitOf(step)?.let { flowOf(snapshot, it) })) {
                    val previous = lastOnSlot.put(slot, step) ?: continue
                    val distance = abs(step.timeNanos - previous.timeNanos)
                    if (distance < intervalNanos) {
                        fail(
                            "Steps #${previous.seq} (${previous.kind}) and #${step.seq} (${step.kind}) share the sequence of node $slot " +
                                "(${snapshot.node(slot)?.info?.construct}) and are $distance ns apart, less than the interval of $intervalNanos ns"
                        )
                    }
                }
            }
        }

        /** The execution unit a step happened in, where the event itself says so. */
        private fun unitOf(step: Event): Long? = when (step.kind) {
            EventKind.RESUMED, EventKind.SUSPENDED -> step.nodeId
            EventKind.LAUNCHED -> step.node?.creatorId?.takeIf { it != 0L }
            EventKind.CANCELLATION_REQUESTED, EventKind.THREAD_INTERRUPTED -> step.otherNodeId.takeIf { it != 0L }
            EventKind.THREAD_BLOCKED, EventKind.THREAD_UNBLOCKED -> step.otherNodeId.takeIf { it != 0L } ?: step.nodeId
            EventKind.EXCEPTION_HANDLED -> step.nodeId.takeIf { step.handledBy == HandledBy.CATCH }
            else -> null
        }

        private val CALLED_IN_PLACE = setOf(
            "kotlinx.coroutines.internal.ScopeCoroutine",
            "kotlinx.coroutines.TimeoutCoroutine",
            "kotlinx.coroutines.SupervisorCoroutine",
            "kotlinx.coroutines.DispatchedCoroutine",
            "kotlinx.coroutines.UndispatchedCoroutine",
            "kotlinx.coroutines.BlockingCoroutine",
        )

        fun flowOf(snapshot: TraceSnapshot, nodeId: Long): Long {
            var id = nodeId
            repeat(10_000) {
                val info = snapshot.node(id)?.info ?: return id
                val inPlace = info.implClass in CALLED_IN_PLACE || info.construct == "suspend fun main"
                if (!inPlace || info.creatorId == 0L) return id
                id = info.creatorId
            }
            return id
        }
    }
}
