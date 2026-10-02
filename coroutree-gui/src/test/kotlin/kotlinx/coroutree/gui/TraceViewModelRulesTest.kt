package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.demo.DemoTrace
import kotlinx.coroutree.gui.view.GraphHighlight
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.NodeInfo
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.tree.TraceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Selection while the trace grows and while the toolbar changes what is in the graph (DESIGN §6: selection is two-way). */
class TraceViewModelRulesTest {
    private val frames = DemoTrace.frames()
    private val full = snapshotOf(frames)

    private fun running(count: Int) = TraceStore().apply { frames.take(count).forEach(::accept) }.snapshot()

    @Test
    fun aSelectedEventStaysSelectedAndFoundWhileTheLiveTraceGrows() {
        val viewModel = TraceViewModel()
        viewModel.snapshot = running(frames.size / 3)
        val event = viewModel.snapshot.events.last()
        viewModel.selectEvent(event)
        val index = viewModel.logIndex(event.seq)

        for (count in listOf(frames.size / 2, frames.size - 1, frames.size)) {
            viewModel.snapshot = running(count)
            assertEquals(event, viewModel.selectedEvent, "after $count frames")
            assertEquals(event.nodeId, viewModel.selectedNode?.id)
            assertTrue(viewModel.isHighlighted(event))
        }
        // Changes of the gate that were made before the event stand before it in the log, later ones do not move it.
        assertEquals(index, viewModel.logIndex(event.seq), "the log grows at its end")
    }

    @Test
    fun eventsAreFoundBySequenceNumberInATraceThatLostSome() {
        // A stream that ended with events missing (the JVM was killed while threads were still writing).
        val seqs = listOf(1L, 2L, 4L, 7L, 8L)
        val lossy = snapshotOf(
            listOf(Frame(event = Event(seq = 1, nodeId = 1, kind = EventKind.DISCOVERED, node = NodeInfo(id = 1, kind = NodeKind.THREAD)))) +
                seqs.drop(1).map { Frame(event = Event(seq = it, nodeId = 1, kind = EventKind.THREAD_BLOCKED)) },
        )
        assertEquals(seqs, lossy.events.map { it.seq })
        val viewModel = TraceViewModel().also { it.snapshot = lossy }
        assertEquals(listOf(0, 1, -1, 2, -1, -1, 3, 4, -1), (1L..9L).map(viewModel::eventIndex))
        assertEquals(-1, viewModel.eventIndex(0))
        assertEquals(-1, viewModel.logIndex(5))

        viewModel.selectEvent(lossy.events[3])
        assertEquals(7L, viewModel.selectedEvent?.seq)
        // An event of another trace, or one that has not arrived: selected by number, found by nobody.
        viewModel.selectEvent(Event(seq = 5, nodeId = 1, kind = EventKind.RESUMED))
        assertNull(viewModel.selectedEvent)
        assertEquals(GraphHighlight.NONE, viewModel.highlight)
    }

    @Test
    fun followingAReferenceIntoTheLibraryShowsTheLibraryAndIntoTheProjectLeavesTheToolbarAlone() {
        val viewModel = TraceViewModel().also { it.snapshot = full }
        viewModel.navigateTo(full.named("audit").id)
        assertFalse(viewModel.options.showLibrary)
        assertEquals(full.named("audit").id, viewModel.graphReveal?.target)

        val worker = full.named("DefaultDispatcher-worker-1")
        viewModel.navigateTo(worker.id)
        assertTrue(viewModel.options.showLibrary, "a pool worker can only be shown by showing the library")
        assertEquals(worker.id, viewModel.selectedNodeId)
        assertEquals(worker.id, viewModel.graphReveal?.target)
        assertNotNull(viewModel.layout.box(worker.id))
    }

    @Test
    fun aSelectionWhoseBoxLeavesTheGraphIsStillTheSelection() {
        val viewModel = TraceViewModel().also { it.snapshot = full }
        val selector = full.named("http-selector")
        viewModel.selectEvent(selector.events.first { it.kind == EventKind.SUSPENDED })
        assertNotNull(viewModel.layout.box(selector.id))

        viewModel.showLibrary(false) // the user switches the library off again
        assertNull(viewModel.layout.box(selector.id))
        assertEquals(selector.id, viewModel.selectedNode?.id, "the details pane still has its node")
        assertNotNull(viewModel.selectedEvent)
        assertTrue(viewModel.layout.routes.none { it.link.kind == EdgeKind.RUNS_ON })
        // The pane is told of the new layout with a selection that is not in it.
        viewModel.graphView.resize(900f, 500f)
        viewModel.graphView.show(viewModel.layout, viewModel.selectedNodeId)
        viewModel.graphView.advance(1f)
        assertTrue(viewModel.graphView.settled)
        assertNull(viewModel.graphView.rect(selector.id))
    }

    @Test
    fun clearingTheSelectionClearsEverythingThatHungOnIt() {
        val viewModel = TraceViewModel().also { it.snapshot = full }
        viewModel.showLibrary(true)
        val receipt = full.named("receipt")
        val scope = full.constructed("coroutineScope")
        viewModel.selectEvent(scope.events.first { it.kind == EventKind.EXCEPTION_PROPAGATED })
        assertNotEquals(GraphHighlight.NONE, viewModel.highlight)
        viewModel.selectNode(receipt.id) // a node selected in the graph: the event is no longer the selection
        assertNull(viewModel.selectedEvent)
        assertEquals(GraphHighlight.NONE, viewModel.highlight)
        assertTrue(viewModel.graph.model.links.any { it.kind == EdgeKind.RUNS_ON })

        viewModel.clearSelection()
        assertNull(viewModel.selectedNode)
        assertTrue(viewModel.graph.model.links.none { it.kind == EdgeKind.RUNS_ON })
        assertTrue(full.events.none(viewModel::isHighlighted))
    }

    @Test
    fun anEventBetweenANodeAndOneThatIsNotInTheGraphHighlightsNoEdge() {
        // A project coroutine cancelled by a library node that is hidden: both are named, there is no line to mark.
        val snapshot = snapshotOf(
            trace {
                node(1, 0, name = "job")
                node(2, 0, Origin.LIBRARY, name = "watchdog")
                event(1, EventKind.CANCELLATION_REQUESTED, other = 2)
            },
        )
        val viewModel = TraceViewModel().also { it.snapshot = snapshot }
        viewModel.selectEvent(snapshot.events.last())
        // selectEvent reveals node 1, which is visible: the library stays off.
        assertFalse(viewModel.options.showLibrary)
        assertEquals(setOf(1L, 2L), viewModel.highlight.nodes)
        assertTrue(viewModel.highlight.links.isEmpty())
        assertNull(viewModel.highlight.structural)

        viewModel.showLibrary(true)
        assertEquals(listOf(EdgeKind.CANCELS), viewModel.highlight.links.map { it.kind })
    }
}
