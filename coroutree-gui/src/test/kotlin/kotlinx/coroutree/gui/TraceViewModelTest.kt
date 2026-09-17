package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.view.LinkMark
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.tree.CrossLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
        val highlighted = snapshot.events.filter(viewModel::isHighlighted)
        assertEquals(payment.events.map { it.seq }, highlighted.map { it.seq }, "own events and events that name the node")
        assertTrue(highlighted.any { it.nodeId != payment.id })
    }

    @Test
    fun selectingAnEventSelectsAndRevealsItsNodeEvenInsideACollapsedSubtree() {
        val selector = snapshot.named("http-selector")
        assertEquals(-1, viewModel.rowIndex(selector.id), "library internals start collapsed")

        val event = selector.events.first { it.kind == EventKind.SUSPENDED }
        viewModel.selectEvent(event)

        assertEquals(event.seq, viewModel.selectedEventSeq)
        assertEquals(event, viewModel.selectedEvent)
        assertEquals(selector.id, viewModel.selectedNodeId)
        assertEquals(selector.id, viewModel.treeReveal?.target)
        assertNotEquals(-1, viewModel.rowIndex(selector.id))
    }

    @Test
    fun revealRequestsForTheSameTargetAreDistinct() {
        val node = snapshot.named("audit")
        viewModel.navigateTo(node.id)
        val first = viewModel.treeReveal
        viewModel.navigateTo(node.id)
        assertNotEquals(first, viewModel.treeReveal)
    }

    @Test
    fun togglingCollapsesAndExpands() {
        val scope = snapshot.constructed("coroutineScope")
        val before = viewModel.rows.size
        viewModel.toggle(viewModel.rows.first { it.node.id == scope.id })
        assertEquals(before - scope.children.size, viewModel.rows.size)
        assertFalse(viewModel.rows.first { it.node.id == scope.id }.expanded)
        viewModel.toggle(viewModel.rows.first { it.node.id == scope.id })
        assertEquals(before, viewModel.rows.size)
    }

    @Test
    fun marksRowsLinkedToTheSelection() {
        val metrics = snapshot.named("metrics")
        val inventory = snapshot.named("inventory")
        val runBlocking = snapshot.constructed("runBlocking")
        viewModel.selectNode(metrics.id)
        assertEquals(
            mapOf(
                inventory.id to listOf(LinkMark(CrossLink.Kind.LAUNCHED_FROM, fromSelection = false)),
                runBlocking.id to listOf(LinkMark(CrossLink.Kind.CANCELS, fromSelection = false)),
            ),
            viewModel.linkMarks,
        )
        assertEquals("launched ●", viewModel.linkMarks.getValue(inventory.id).single().label)

        viewModel.selectNode(inventory.id)
        assertEquals("launched by ●", viewModel.linkMarks.getValue(metrics.id).single().label)
        viewModel.clearSelection()
        assertTrue(viewModel.linkMarks.isEmpty())
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
