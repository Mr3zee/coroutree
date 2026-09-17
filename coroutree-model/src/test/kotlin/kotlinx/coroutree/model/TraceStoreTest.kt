package kotlinx.coroutree.model

import kotlinx.coroutree.model.tree.CrossLink
import kotlinx.coroutree.model.tree.TraceStore
import kotlinx.coroutree.model.tree.TreeRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TraceStoreTest {
    private var seq = 0L

    private fun event(nodeId: Long, kind: EventKind, configure: Event.() -> Event = { this }) =
        Frame(event = Event(seq = ++seq, nodeId = nodeId, kind = kind).configure())

    private fun launched(id: Long, parent: Long, kind: NodeKind, construct: String, name: String = "", creator: Long = 0) =
        event(id, EventKind.LAUNCHED) {
            copy(node = NodeInfo(id = id, kind = kind, construct = construct, name = name, parentId = parent, creatorId = creator))
        }

    @Test
    fun foldsEventsIntoTreeStatesAndLinks() {
        val store = TraceStore()
        listOf(
            event(1, EventKind.DISCOVERED) { copy(node = NodeInfo(id = 1, kind = NodeKind.THREAD, name = "main")) },
            launched(2, parent = 1, NodeKind.COROUTINE, "runBlocking"),
            launched(3, parent = 2, NodeKind.COROUTINE, "launch", name = "child"),
            launched(4, parent = 0, NodeKind.COROUTINE, "launch", creator = 3),
            event(3, EventKind.RESUMED) { copy(threadId = 1) },
            event(3, EventKind.SUSPENDED),
            event(4, EventKind.CANCELLATION_REQUESTED) { copy(otherNodeId = 3) },
            event(4, EventKind.CANCELLING),
            event(4, EventKind.FINISHED) { copy(finalState = NodeState.CANCELLED) },
            event(1, EventKind.THREAD_BLOCKED) { copy(otherNodeId = 2, blockReason = BlockReason.SLEEP) },
        ).forEach(store::accept)

        val snapshot = store.snapshot()
        assertEquals(listOf(1L, 4L), snapshot.roots)
        assertEquals(listOf(2L), snapshot.node(1)!!.children)
        assertEquals(NodeState.BLOCKED, snapshot.node(1)!!.state)
        assertEquals(NodeState.BLOCKED, snapshot.node(2)!!.state, "the coroutine running on a blocked thread is blocked too")
        assertEquals(NodeState.SUSPENDED, snapshot.node(3)!!.state)
        assertEquals(NodeState.CANCELLED, snapshot.node(4)!!.state)
        assertEquals(
            listOf(CrossLink.Kind.LAUNCHED_FROM, CrossLink.Kind.CANCELS),
            snapshot.node(3)!!.links.map { it.kind },
        )
        assertEquals(10, snapshot.events.size)
        assertTrue(snapshot.node(3)!!.events.any { it.kind == EventKind.CANCELLATION_REQUESTED }, "events are mirrored to the other node")
    }

    @Test
    fun restoresHistoricalOrder() {
        val store = TraceStore()
        val frames = listOf(
            launched(1, 0, NodeKind.COROUTINE, "launch"),
            event(1, EventKind.RESUMED),
            event(1, EventKind.SUSPENDED),
            event(1, EventKind.RESUMED),
        )
        for (i in listOf(0, 2, 3, 1)) store.accept(frames[i])
        assertEquals(listOf(1L, 2L, 3L, 4L), store.snapshot().events.map { it.seq })
        assertEquals(NodeState.ACTIVE, store.snapshot().node(1)!!.state)
    }

    @Test
    fun releasesEventsBehindAGapAtEndOfStream() {
        val store = TraceStore()
        store.accept(launched(1, 0, NodeKind.COROUTINE, "launch"))
        seq++ // lost
        store.accept(event(1, EventKind.FINISHED) { copy(finalState = NodeState.COMPLETED) })
        assertEquals(1, store.snapshot().events.size)
        store.endOfStream()
        assertEquals(2, store.snapshot().events.size)
        assertTrue(store.snapshot().complete)
    }

    @Test
    fun snapshotsAreImmutableAndShared() {
        val store = TraceStore()
        store.accept(launched(1, 0, NodeKind.COROUTINE, "launch"))
        store.accept(launched(2, 1, NodeKind.COROUTINE, "launch"))
        val before = store.snapshot()
        assertSame(before, store.snapshot(), "nothing changed, nothing rebuilt")

        store.accept(launched(3, 1, NodeKind.COROUTINE, "async"))
        val after = store.snapshot()
        assertEquals(listOf(2L), before.node(1)!!.children)
        assertEquals(listOf(2L, 3L), after.node(1)!!.children)
        assertNull(before.node(3))
        assertSame(before.node(2), after.node(2), "untouched nodes are reused")
    }

    @Test
    fun nodeReferencedBeforeItsDefinitionEndsUpUnderItsParent() {
        val store = TraceStore()
        store.accept(launched(1, 0, NodeKind.COROUTINE, "launch"))
        store.accept(event(1, EventKind.CANCELLATION_REQUESTED) { copy(otherNodeId = 2) })
        assertEquals(listOf(1L, 2L), store.snapshot().roots)
        assertTrue(store.snapshot().node(2)!!.placeholder)
        store.accept(launched(2, 1, NodeKind.COROUTINE, "launch"))
        assertEquals(listOf(1L), store.snapshot().roots)
        assertEquals(listOf(2L), store.snapshot().node(1)!!.children)
    }

    @Test
    fun rendersStableText() {
        val store = TraceStore()
        listOf(
            Frame(stackFrame = StackFrameDef(id = 1, className = "demo.MainKt", methodName = "main", fileName = "Main.kt", line = 7)),
            launched(1, 0, NodeKind.COROUTINE, "runBlocking").let { it.copy(event = it.event!!.copy(node = it.event!!.node!!.copy(siteFrame = 1))) },
            launched(2, 1, NodeKind.CONTEXT_CHANGE, "withContext"),
            event(2, EventKind.DISPATCHER_CHANGED) {
                copy(contextDiff = listOf(ContextChange(ContextElementKind.DISPATCHER, "Dispatcher", "BlockingEventLoop", "Dispatchers.IO")))
            },
            event(2, EventKind.RESUMED) { copy(threadId = 9) },
            event(2, EventKind.FINISHED) { copy(finalState = NodeState.COMPLETED) },
        ).forEach(store::accept)
        assertEquals(
            """
            runBlocking @ Main.kt:7 [active]
              withContext [completed] {Dispatcher: BlockingEventLoop → Dispatchers.IO}
                - resumed
                - completed

            """.trimIndent(),
            TreeRenderer().render(store.snapshot()),
        )
    }

    @Test
    fun writerAndReaderRoundTrip() {
        val frames = listOf(
            Frame(header = TraceHeader(formatVersion = TraceHeader.FORMAT_VERSION, buildId = "b", includePackages = listOf("a", "b"))),
            Frame(stackFrame = StackFrameDef(1, "A", "m", "A.kt", 3)),
            Frame(event = Event(seq = 1, nodeId = 5, kind = EventKind.SUSPENDED, stack = listOf(1, 1, 1))),
            Frame(diagnostic = Diagnostic(Diagnostic.Severity.WARNING, "hello")),
        )
        val bytes = ByteArrayOutputStream().also { out -> TraceWriter(out).use { w -> frames.forEach(w::write) } }.toByteArray()
        assertEquals(frames, TraceReader(ByteArrayInputStream(bytes)).use { it.frames().toList() })

        val truncated = bytes.copyOf(bytes.size - 3)
        assertEquals(frames.dropLast(1), TraceReader(ByteArrayInputStream(truncated)).use { it.frames().toList() })

        assertFailsWith<TraceFormatException> { TraceReader(ByteArrayInputStream("not a trace at all".toByteArray())) }
    }
}
