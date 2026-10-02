package kotlinx.coroutree.model

import kotlinx.coroutree.model.tree.TraceStore
import kotlinx.coroutree.model.tree.TreeRenderer
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TRACE_FORMAT, "Order": frames may be out of order on the wire, `seq` is dense, and a reader restores the exact order
 * by holding an event back until its predecessor has arrived; at the end of the stream, or when too many pile up behind
 * a gap, it lets them through in order.
 */
class TraceOrderTest {
    private val program = script {
        frame(1, "demo.MainKt", "main", "Main.kt", 12)
        thread(1, "main")
        launched(2, parent = 1, construct = "runBlocking")
        launched(3, parent = 2, name = "a", site = 1)
        launched(4, parent = 2, name = "b", creator = 3)
        event(3, EventKind.RESUMED, thread = 1)
        event(4, EventKind.CANCELLATION_REQUESTED, other = 3)
        event(4, EventKind.CANCELLING)
        event(3, EventKind.SUSPENDED)
        finished(4, NodeState.CANCELLED)
        event(1, EventKind.THREAD_BLOCKED) { copy(blockReason = BlockReason.RUN_BLOCKING) }
        event(3, EventKind.RESUMED, thread = 1)
        event(3, EventKind.EXCEPTION_THROWN) { copy(exception = ExceptionInfo("java.lang.IllegalStateException", "boom")) }
        event(2, EventKind.EXCEPTION_PROPAGATED, other = 3) { copy(exception = ExceptionInfo("java.lang.IllegalStateException", "boom")) }
        finished(3, NodeState.FAILED)
        event(1, EventKind.THREAD_UNBLOCKED)
        finished(2, NodeState.FAILED)
    }

    @Test
    fun whateverOrderTheEventsArriveInTheTreeIsTheSame() {
        val inOrder = storeOf(program).snapshot()
        val expected = TreeRenderer().render(inOrder)
        for (seed in 1L..50L) {
            // Definitions of nodes arrive after events about them, children before parents, the end before the start.
            val shuffled = program.shuffled(Random(seed))
            val snapshot = storeOf(shuffled).snapshot()
            assertEquals(inOrder.events, snapshot.events, "seed $seed")
            assertEquals(expected, TreeRenderer().render(snapshot), "seed $seed")
            for (id in inOrder.nodes.keys) {
                assertEquals(inOrder.node(id)!!.events, snapshot.node(id)!!.events, "seed $seed, node $id")
                assertEquals(inOrder.node(id)!!.links, snapshot.node(id)!!.links, "seed $seed, node $id")
                assertFalse(snapshot.node(id)!!.placeholder, "seed $seed, node $id")
            }
        }
    }

    @Test
    fun nothingBehindAMissingEventIsShownUntilItArrives() {
        val events = program.filter { it.event != null }
        val store = TraceStore()
        store.accept(events[0])
        for (later in events.drop(2)) store.accept(later)
        assertEquals(listOf(1L), store.snapshot().events.map { it.seq }, "a reader never shows history with a hole in the middle while the hole may still fill")
        assertFalse(store.snapshot().complete)

        store.accept(events[1])
        assertEquals((1L..events.size).toList(), store.snapshot().events.map { it.seq })
    }

    @Test
    fun anEventDeliveredTwiceHappenedOnce() {
        // A live client that reconnects is sent the trace from its first byte again.
        val store = TraceStore()
        val events = program.filter { it.event != null }
        for (frame in events.take(6)) store.accept(frame)
        for (frame in events.take(6)) store.accept(frame)
        store.accept(events[8]) // waits for its predecessor
        store.accept(events[8])
        for (frame in events) store.accept(frame)

        val once = storeOf(program).snapshot()
        val snapshot = store.snapshot()
        assertEquals(once.events, snapshot.events)
        assertEquals(once.node(3)!!.events, snapshot.node(3)!!.events)
        assertEquals(once.node(3)!!.links, snapshot.node(3)!!.links, "no second link for a cancellation that was read twice")
        assertEquals(once.node(1)!!.state, snapshot.node(1)!!.state, "blocked once, unblocked once")
        assertEquals(once.node(2)!!.children, snapshot.node(2)!!.children)
    }

    @Test
    fun theEndOfTheStreamReleasesWhatWaitedBehindAGapInOrder() {
        val frames = script {
            launched(1)
            lost()
            event(1, EventKind.SUSPENDED)
            lost()
            lost()
            event(1, EventKind.RESUMED)
            finished(1, NodeState.COMPLETED)
        }
        val store = TraceStore()
        for (frame in frames.reversed()) store.accept(frame)
        assertEquals(listOf(1L), store.snapshot().events.map { it.seq })
        assertEquals(NodeState.ACTIVE, store.snapshot().node(1)!!.state)

        store.endOfStream()
        val snapshot = store.snapshot()
        assertEquals(listOf(1L, 3L, 6L, 7L), snapshot.events.map { it.seq })
        assertEquals(NodeState.COMPLETED, snapshot.node(1)!!.state)
        assertTrue(snapshot.complete)
    }

    @Test
    fun aGapThatNeverFillsDoesNotHoldALiveTraceBackForEver() {
        // A live stream has no end to wait for. "When too many events pile up behind a gap, it lets them through in order."
        val frames = script {
            launched(1)
            lost()
            repeat(10_000) { event(1, if (it % 2 == 0) EventKind.SUSPENDED else EventKind.RESUMED) }
        }
        val store = TraceStore()
        for (frame in frames) store.accept(frame)

        val expected = listOf(1L) + (3L..10_002L)
        assertEquals(expected, store.snapshot().events.map { it.seq }, "everything behind one lost number is visible without the stream ending")
        assertEquals(NodeState.ACTIVE, store.snapshot().node(1)!!.state)

        // The lost one turning up late cannot be put back into a history that has been shown.
        store.accept(Frame(event = Event(seq = 2, nodeId = 1, kind = EventKind.CANCELLING)))
        store.endOfStream()
        val all = store.snapshot().events.map { it.seq }
        assertEquals(all.sorted().distinct(), all, "history stays in order of seq, each event once")
        assertTrue(all.containsAll(expected))
    }
}
