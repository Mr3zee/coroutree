package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.view.GraphViewState
import kotlinx.coroutree.gui.view.Viewport
import kotlinx.coroutree.gui.view.graph.GraphLayout
import kotlinx.coroutree.gui.view.graph.GraphModel
import kotlinx.coroutree.gui.view.graph.GraphNode
import kotlinx.coroutree.gui.view.graph.LayoutEngine
import kotlinx.coroutree.gui.view.toFloatRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ends of the pane's state that GraphViewStateTest does not reach: a graph with nothing in it (a live trace
 * before its first node, a trace of nothing but library nodes), boxes that leave the graph, the limits of zoom as
 * DESIGN §12 states them, and a viewport the user has taken far away from everything.
 */
class GraphViewStateRulesTest {
    private fun layoutOf(vararg nodes: Pair<Long, Long>): GraphLayout = LayoutEngine.layout(GraphModel(nodes.map { GraphNode(it.first, it.second) }, emptyList()))

    private val small = layoutOf(1L to 0L, 2L to 1L, 3L to 1L)
    private val grown = layoutOf(1L to 0L, 2L to 1L, 3L to 1L, 4L to 1L, 5L to 0L, 6L to 2L, 7L to 2L)
    private val wide = LayoutEngine.layout(GraphModel(listOf(GraphNode(1, 0)) + (2L..60L).map { GraphNode(it, 1) }, emptyList()))

    private fun state(width: Float = 600f, height: Float = 300f) = GraphViewState().apply { resize(width, height) }

    private fun assertClose(expected: Viewport, actual: Viewport) =
        assertTrue(abs(expected.zoom - actual.zoom) < 1e-4f && abs(expected.panX - actual.panX) < 0.01f && abs(expected.panY - actual.panY) < 0.01f, "$expected vs $actual")

    @Test
    fun anEmptyGraphHasNothingToFitAnimateOrFind() {
        val state = state()
        assertFalse(state.show(GraphLayout.EMPTY, selectedNodeId = 7))
        assertTrue(state.settled && state.autoFit)
        assertEquals(Viewport(), state.viewport)
        assertTrue(state.wholeGraphInView, "nothing is outside the pane")
        assertNull(state.nodeAt(300f, 150f))
        assertNull(state.rect(7))
        assertFalse(state.isInView(7))

        // None of these has anything to act on; none of them may leave a broken viewport behind.
        state.reveal(7)
        state.zoomTo(7)
        assertTrue(state.autoFit, "nothing was taken over")
        state.zoom(0.25f)
        state.zoom(8f)
        state.fit()
        assertEquals(Viewport(), state.viewport)
        assertTrue(state.viewport.zoom.isFinite() && state.viewport.panX.isFinite() && state.viewport.panY.isFinite())
    }

    @Test
    fun theFirstNodesOfALiveTraceAndTheLastOnesToGoAreShownAtOnce() {
        val state = state()
        state.show(GraphLayout.EMPTY, null)
        assertFalse(state.show(small, null), "from nothing: there is nowhere to glide from")
        assertEquals(Viewport.fitting(small.bounds, 600f, 300f), state.viewport)

        // The library is switched off and every node was library: the graph empties.
        assertFalse(state.show(GraphLayout.EMPTY, selectedNodeId = 2), "to nothing: there is nowhere to glide to")
        assertTrue(state.settled)
        assertNull(state.rect(2))
        assertEquals(Viewport(), state.viewport, "still following the graph, which is empty")
    }

    @Test
    fun boxesThatLeaveTheGraphAreGoneAtOnceAndTheRestGlides() {
        val state = state()
        state.show(grown, null)
        assertTrue(state.show(small, selectedNodeId = 6), "the selected node is among those that left")
        assertNull(state.rect(6))
        assertFalse(state.isInView(6))
        assertEquals(grown.box(3)!!.rect.toFloatRect(), state.rect(3), "what stays starts from where it was")
        assertEquals(1f, state.alpha(3))
        state.advance(1f)
        assertEquals(small.box(3)!!.rect.toFloatRect(), state.rect(3))
        assertClose(Viewport.fitting(small.bounds, 600f, 300f), state.viewport)
    }

    @Test
    fun showingTheLayoutThatIsAlreadyShownNeitherRestartsNorEndsItsGlide() {
        val state = state()
        state.show(small, null)
        assertFalse(state.show(small, null), "settled: nothing to run")
        state.show(grown, null)
        state.advance(0.4f)
        val midway = state.rect(3)
        assertTrue(state.show(grown, null), "the glide that is under way goes on")
        assertEquals(0.4f, state.progress)
        assertEquals(midway, state.rect(3))
    }

    @Test
    fun progressStaysBetweenStartAndSettled() {
        val state = state()
        state.show(small, null)
        state.show(grown, null)
        state.advance(-3f)
        assertEquals(0f, state.progress)
        assertEquals(small.box(3)!!.rect.toFloatRect(), state.rect(3))
        state.advance(42f)
        assertEquals(1f, state.progress)
        assertTrue(state.settled)
        assertEquals(grown.box(3)!!.rect.toFloatRect(), state.rect(3))
    }

    /** DESIGN §12: "Zooming out ends at the zoom that fits the whole graph, however small that is; zooming in at 3×." */
    @Test
    fun zoomEndsAtThreeTimesAndAtTheWholeGraph() {
        val state = state()
        state.show(wide, null)
        val fitted = state.viewport.zoom
        assertTrue(fitted < 0.2f, "sixty boxes side by side do not fit a pane of 600 at any readable size: $fitted")

        repeat(40) { state.zoom(1.5f) }
        assertEquals(Viewport.MAX_ZOOM, state.viewport.zoom)
        repeat(40) { state.zoom(1 / 1.5f) }
        assertEquals(fitted, state.viewport.zoom, 1e-6f)

        // A graph that fits at 1:1 is never shown smaller than that.
        val little = state()
        little.show(small, null)
        assertEquals(1f, little.viewport.zoom)
        little.zoom(0.5f)
        assertEquals(1f, little.viewport.zoom)
        little.zoom(2f)
        assertEquals(2f, little.viewport.zoom)
    }

    @Test
    fun zoomingKeepsThePointUnderThePointerAtBothLimits() {
        val state = state()
        state.show(wide, null)
        val worldX = state.viewport.toWorldX(120f)
        val worldY = state.viewport.toWorldY(80f)
        repeat(12) { state.zoom(1.7f, anchorX = 120f, anchorY = 80f) }
        assertEquals(Viewport.MAX_ZOOM, state.viewport.zoom)
        assertEquals(120f, state.viewport.toScreenX(worldX), 0.1f)
        assertEquals(80f, state.viewport.toScreenY(worldY), 0.1f)
        state.zoom(1.7f, anchorX = 400f, anchorY = 200f) // at the limit already: nothing moves
        assertEquals(120f, state.viewport.toScreenX(worldX), 0.1f)
    }

    @Test
    fun aViewportTheUserTookFarAwayFromEveryBoxIsLeftWhereItIs() {
        val state = state()
        state.show(small, null)
        state.pan(5_000f, 5_000f)
        val away = state.viewport
        assertTrue(state.show(grown, selectedNodeId = null))
        for (fraction in listOf(0.3f, 0.7f, 1f)) state.advance(fraction)
        assertEquals(away, state.viewport, "nothing was in view to hold on to, and the user's viewport is the user's")

        state.resize(900f, 500f)
        assertEquals(away, state.viewport, "a pane that changes size does not refit once the user has taken over")
        assertFalse(state.wholeGraphInView)
    }

    @Test
    fun theMinimapHasNoUseWhileEverythingIsInViewAndGetsOneOnceItIsNot() {
        val state = state()
        state.show(grown, null)
        assertTrue(state.wholeGraphInView)
        state.centreOn(grown.bounds.right + 2_000f, 0f)
        assertFalse(state.wholeGraphInView)
        assertTrue(grown.boxes.none { state.isInView(it.id) })
        state.fit()
        assertTrue(state.wholeGraphInView && grown.boxes.all { state.isInView(it.id) })
    }
}
