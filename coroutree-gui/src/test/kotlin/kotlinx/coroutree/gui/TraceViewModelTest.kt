package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.graph.assertInvariant
import kotlinx.coroutree.gui.view.GraphHighlight
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.GraphLink
import kotlinx.coroutree.model.EventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TraceViewModelTest {
    private val snapshot = demoSnapshot()
    private val viewModel = TraceViewModel().also { it.snapshot = snapshot }

    @Test
    fun selectingANodeHighlightsItsEventsAndRevealsTheLatest() {
        val payment = snapshot.named("payment")
        viewModel.selectNode(payment.id)

        assertEquals(payment.id, viewModel.selectedNodeId)
        assertNull(viewModel.selectedEventSeq)
        assertEquals(payment.events.last().seq, viewModel.logReveal?.target)
        assertNull(viewModel.graphReveal, "the node was clicked where it is: the graph has nowhere to go")
        val highlighted = snapshot.events.filter(viewModel::isHighlighted)
        assertEquals(payment.events.map { it.seq }, highlighted.map { it.seq }, "own events and events that name the node")
        assertTrue(highlighted.any { it.nodeId != payment.id })
    }

    @Test
    fun selectingAnEventSelectsItsNodeAndRevealsItInTheGraph() {
        val audit = snapshot.named("audit")
        val event = audit.events.first { it.kind == EventKind.SUSPENDED }
        viewModel.selectEvent(event)

        assertEquals(event.seq, viewModel.selectedEventSeq)
        assertEquals(event, viewModel.selectedEvent)
        assertEquals(audit.id, viewModel.selectedNodeId)
        assertEquals(audit.id, viewModel.graphReveal?.target)
        assertEquals(event.seq, viewModel.logReveal?.target)
        assertFalse(viewModel.options.showLibrary, "a project node needs no library to be seen")
    }

    @Test
    fun revealingANodeThatIsLeftOutAsLibraryNoiseShowsTheLibrary() {
        val selector = snapshot.named("http-selector")
        assertNull(viewModel.layout.box(selector.id), "library internals are not in the graph by default")

        viewModel.selectEvent(selector.events.first { it.kind == EventKind.SUSPENDED })

        assertTrue(viewModel.options.showLibrary)
        assertNotNull(viewModel.layout.box(selector.id))
        assertEquals(selector.id, viewModel.graphReveal?.target)
    }

    @Test
    fun revealRequestsForTheSameTargetAreDistinct() {
        val node = snapshot.named("audit")
        viewModel.navigateTo(node.id)
        val first = viewModel.graphReveal
        viewModel.navigateTo(node.id)
        assertNotEquals(first, viewModel.graphReveal)
    }

    @Test
    fun anEventThatNamesAnotherNodeHighlightsBothAndTheStructuralEdgeBetweenThem() {
        val scope = snapshot.constructed("coroutineScope")
        val payment = snapshot.named("payment")
        assertEquals(GraphHighlight.NONE, viewModel.highlight)

        viewModel.selectEvent(scope.events.first { it.kind == EventKind.EXCEPTION_PROPAGATED })
        assertEquals(GraphHighlight(setOf(scope.id, payment.id), structural = scope.id to payment.id), viewModel.highlight)
        assertNotNull(viewModel.layout.trunks.single { it.parentId == scope.id }.pathTo(payment.id), "the edge is there to be highlighted")

        // The same edge, named from the child's side.
        val audit = snapshot.named("audit")
        viewModel.selectEvent(audit.events.first { it.kind == EventKind.CANCELLATION_PROPAGATED })
        assertEquals(scope.id to audit.id, viewModel.highlight.structural)

        viewModel.selectEvent(payment.events.first { it.kind == EventKind.EXCEPTION_THROWN })
        assertEquals(GraphHighlight.NONE, viewModel.highlight, "an event of one node alone highlights nothing but the selection")
    }

    @Test
    fun anEventThatNamesAnotherNodeHighlightsTheCrossLinkBetweenThem() {
        val metrics = snapshot.named("metrics")
        val runBlocking = snapshot.constructed("runBlocking")
        viewModel.selectEvent(metrics.events.first { it.kind == EventKind.CANCELLATION_REQUESTED })

        val highlight = viewModel.highlight
        assertEquals(setOf(metrics.id, runBlocking.id), highlight.nodes)
        assertNull(highlight.structural)
        assertEquals(setOf(EdgeKind.CANCELS), highlight.links.map { it.kind }.toSet())
        assertTrue(viewModel.layout.routes.any { it.link in highlight.links })

        viewModel.toggleLinks(EdgeKind.CANCELS)
        assertTrue(viewModel.highlight.links.isEmpty(), "a link that is switched off is not there to highlight")
        assertEquals(setOf(metrics.id, runBlocking.id), viewModel.highlight.nodes)
    }

    @Test
    fun togglesDecideWhatIsInTheGraph() {
        fun kinds() = viewModel.layout.routes.map { it.link.kind }.toSet()
        assertEquals(setOf(EdgeKind.LAUNCHED_FROM, EdgeKind.CANCELS, EdgeKind.INTERRUPTS), kinds())
        assertTrue(viewModel.layout.routes.all { it.label != null }, "the demo has room for every label")

        viewModel.toggleLinks(EdgeKind.LAUNCHED_FROM)
        assertEquals(setOf(EdgeKind.CANCELS, EdgeKind.INTERRUPTS), kinds())
        viewModel.toggleLinks(EdgeKind.LAUNCHED_FROM)
        assertEquals(setOf(EdgeKind.LAUNCHED_FROM, EdgeKind.CANCELS, EdgeKind.INTERRUPTS), kinds())

        viewModel.showLabels(false)
        assertTrue(viewModel.layout.routes.all { it.label == null })

        val before = viewModel.layout.boxes.size
        viewModel.showLibrary(true)
        assertEquals(snapshot.nodes.size, viewModel.layout.boxes.size)
        assertEquals(before + viewModel.layout.boxes.count { it.node.dimmed }, viewModel.layout.boxes.size)
        assertEquals(0, viewModel.graph.hiddenNodes)
    }

    @Test
    fun runsOnIsDrawnForTheSelectionOnly() {
        viewModel.showLibrary(true) // the threads coroutines run on are pool workers
        val receipt = snapshot.named("receipt")
        val worker = snapshot.node(receipt.runsOn)!!
        assertTrue(viewModel.layout.routes.none { it.link.kind == EdgeKind.RUNS_ON })

        viewModel.selectNode(receipt.id)
        assertEquals(listOf(GraphLink(EdgeKind.RUNS_ON, receipt.id, worker.id)), viewModel.layout.routes.map { it.link.copy(labelWidth = 0) }.filter { it.kind == EdgeKind.RUNS_ON })
        assertInvariant(viewModel.layout, "the demo with a runs-on edge")

        viewModel.selectNode(worker.id)
        assertEquals(listOf(receipt.id), viewModel.layout.routes.filter { it.link.kind == EdgeKind.RUNS_ON }.map { it.link.from }, "seen from the thread")

        viewModel.clearSelection()
        assertTrue(viewModel.layout.routes.none { it.link.kind == EdgeKind.RUNS_ON })
    }

    @Test
    fun aSnapshotThatChangesNoBoxAndNoEdgeKeepsTheLayout() {
        val frames = kotlinx.coroutree.gui.demo.DemoTrace.frames()
        val live = TraceViewModel().also { it.snapshot = snapshotOf(frames.dropLast(1)) }
        val layout = live.layout
        live.snapshot = snapshot // one more event, a thread that blocks
        assertSame(layout, live.layout, "the engine did not run again")
    }

    @Test
    fun findsEventsBySequenceNumber() {
        assertEquals(0, viewModel.eventIndex(snapshot.events.first().seq))
        assertEquals(snapshot.events.lastIndex, viewModel.eventIndex(snapshot.events.last().seq))
        assertEquals(-1, viewModel.eventIndex(1_000_000))
    }

    @Test
    fun selectionSurvivesALiveSnapshotUpdate() {
        val frames = kotlinx.coroutree.gui.demo.DemoTrace.frames()
        val early = snapshotOf(frames.take(frames.size / 2))
        val live = TraceViewModel().also { it.snapshot = early }
        val node = early.constructed("runBlocking")
        live.selectNode(node.id)
        live.snapshot = snapshot
        assertEquals(node.id, live.selectedNode?.id)
        assertTrue(live.selectedNode!!.events.size > node.events.size)
    }
}
