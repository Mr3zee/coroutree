package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.view.FloatRect
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Change over time (DESIGN §6.1): the glide from one layout to the next, and what the viewport holds on to meanwhile. */
class GraphViewStateTest {
    private fun layoutOf(vararg nodes: Pair<Long, Long>): GraphLayout = LayoutEngine.layout(GraphModel(nodes.map { GraphNode(it.first, it.second) }, emptyList()))

    // A root with two children; then a third child, two grandchildren and a second root arrive.
    private val before = layoutOf(1L to 0L, 2L to 1L, 3L to 1L)
    private val after = layoutOf(1L to 0L, 2L to 1L, 3L to 1L, 4L to 1L, 5L to 0L, 6L to 2L, 7L to 2L)

    private fun state(width: Float = 600f, height: Float = 300f) = GraphViewState().apply { resize(width, height) }

    private fun GraphViewState.onScreen(id: Long): FloatRect = viewport.toScreen(rect(id)!!)

    private fun assertClose(expected: FloatRect, actual: FloatRect) =
        assertTrue(abs(expected.left - actual.left) < 0.01f && abs(expected.top - actual.top) < 0.01f && abs(expected.right - actual.right) < 0.01f, "$expected vs $actual")

    @Test
    fun theFirstLayoutIsShownAtOnceAndFitted() {
        val state = state()
        assertFalse(state.show(before, selectedNodeId = null), "nothing to glide from")
        assertTrue(state.settled && state.autoFit && state.wholeGraphInView)
        assertEquals(Viewport.fitting(before.bounds, 600f, 300f), state.viewport)
        assertEquals(before.box(2)!!.rect.toFloatRect(), state.rect(2))
        assertEquals(1f, state.alpha(2))
        assertEquals(1f, state.edgeAlpha)
        assertNull(state.rect(42))
    }

    @Test
    fun aLayoutThatArrivesBeforeThePaneHasASizeIsFittedOnceItHasOne() {
        val state = GraphViewState()
        state.show(before, null)
        state.reveal(3) // a selection made before the first frame must not take the viewport over
        assertTrue(state.autoFit)
        state.resize(600f, 300f)
        assertEquals(Viewport.fitting(before.bounds, 600f, 300f), state.viewport)
    }

    @Test
    fun boxesGlideNewOnesFadeInAndEdgesComeLast() {
        val state = state()
        state.show(before, null)
        assertTrue(state.show(after, null))
        assertFalse(state.settled)
        val from = before.box(3)!!.rect.toFloatRect()
        val to = after.box(3)!!.rect.toFloatRect()
        assertNotEquals(from, to, "the newcomers push node 3 aside")

        assertEquals(from, state.rect(3), "the glide starts where things are")
        assertEquals(0f, state.alpha(4))
        assertEquals(0f, state.edgeAlpha)

        state.advance(0.25f)
        assertEquals(from.lerp(to, 0.25f), state.rect(3))
        assertEquals(after.box(4)!!.rect.toFloatRect(), state.rect(4), "a new box fades in at its own place")
        assertEquals(0.25f, state.alpha(4))
        assertEquals(1f, state.alpha(3))
        assertEquals(0f, state.edgeAlpha, "edges wait until the boxes are nearly there")

        state.advance(0.75f)
        assertEquals(0.5f, state.edgeAlpha)
        state.advance(1f)
        assertTrue(state.settled)
        assertEquals(to, state.rect(3))
        assertEquals(1f, state.alpha(4))
        assertEquals(1f, state.edgeAlpha)
    }

    @Test
    fun aLayoutThatArrivesMidGlideTakesOverFromWhereTheBoxesAre() {
        val state = state()
        state.show(before, null)
        state.show(after, null)
        state.advance(0.5f)
        val midway = state.rect(3)!!
        val fading = state.rect(4)!!

        val third = layoutOf(1L to 0L, 2L to 1L, 3L to 1L, 4L to 1L, 5L to 0L, 6L to 2L, 7L to 2L, 8L to 3L, 9L to 3L)
        assertTrue(state.show(third, null))
        assertEquals(midway, state.rect(3), "no jump")
        assertEquals(fading, state.rect(4))
        assertEquals(1f, state.alpha(4), "it was on screen already, however faintly")
        assertEquals(0f, state.alpha(8))
        state.advance(1f)
        assertEquals(third.box(3)!!.rect.toFloatRect(), state.rect(3))
    }

    @Test
    fun untilTheUserTakesOverTheViewportFollowsTheGraph() {
        val state = state()
        state.show(before, null)
        state.show(after, null)
        state.advance(0.5f)
        assertEquals(Viewport.fitting(before.bounds, 600f, 300f).lerp(Viewport.fitting(after.bounds, 600f, 300f), 0.5f), state.viewport)
        state.advance(1f)
        assertEquals(Viewport.fitting(after.bounds, 600f, 300f), state.viewport)
        assertTrue(state.wholeGraphInView)

        state.resize(900f, 300f)
        assertEquals(Viewport.fitting(after.bounds, 900f, 300f), state.viewport, "and the pane")
    }

    @Test
    fun theSelectedNodeStaysWhereItIsOnScreenWhileTheRestMoves() {
        val state = state()
        state.show(before, null)
        state.zoom(1.5f) // the user has taken over
        assertFalse(state.autoFit)
        val held = state.onScreen(3)
        val other = state.onScreen(1)

        state.show(after, selectedNodeId = 3)
        for (fraction in listOf(0.1f, 0.4f, 0.8f, 1f)) {
            state.advance(fraction)
            assertClose(held, state.onScreen(3))
        }
        assertNotEquals(other.left, state.onScreen(1).left, "everything else has moved around it")
        assertEquals(1.5f * Viewport.fitting(before.bounds, 600f, 300f).zoom, state.viewport.zoom, "anchoring pans, it does not zoom")
    }

    @Test
    fun withoutASelectionWhatWasInTheMiddleOfThePaneStays() {
        val state = state()
        state.show(before, null)
        state.zoomTo(2) // node 2 in the middle, at 1:1
        val held = state.onScreen(2)
        state.show(after, selectedNodeId = null)
        state.advance(0.5f)
        assertClose(held, state.onScreen(2))
        state.advance(1f)
        assertClose(held, state.onScreen(2))

        // A selection that is not in the new layout is no anchor; the middle of the pane is.
        state.show(before, selectedNodeId = 7)
        state.advance(1f)
        assertClose(held, state.onScreen(2))
    }

    @Test
    fun aPanDuringTheGlideIsNotUndoneByTheAnchor() {
        val state = state()
        state.show(before, null)
        state.zoomTo(2)
        val held = state.onScreen(2)
        state.show(after, selectedNodeId = 2)
        state.advance(0.3f)
        state.pan(40f, -15f)
        state.advance(1f)
        val now = state.onScreen(2)
        assertEquals(held.left + 40f, now.left, 0.01f)
        assertEquals(held.top - 15f, now.top, 0.01f)
    }

    @Test
    fun gesturesTakeTheViewportOverAndFitHandsItBack() {
        val state = state()
        state.show(after, null)
        val fitted = state.viewport

        state.pan(25f, 10f)
        assertFalse(state.autoFit)
        assertEquals(fitted.pannedBy(25f, 10f), state.viewport)
        state.fit()
        assertTrue(state.autoFit)
        assertEquals(fitted, state.viewport)

        state.zoom(2f, anchorX = 100f, anchorY = 50f)
        assertEquals(fitted.zoomedBy(2f, 100f, 50f), state.viewport)
        state.zoom(0.01f)
        assertEquals(fitted.zoom, state.viewport.zoom, "zooming out ends where the whole graph is in view")

        state.zoomTo(7)
        assertEquals(1f, state.viewport.zoom)
        assertEquals(300f, state.onScreen(7).centerX, 0.01f)
        assertEquals(150f, state.onScreen(7).centerY, 0.01f)
        assertTrue(state.isInView(7))
        state.zoomTo(99) // not in the graph
        assertEquals(1f, state.viewport.zoom)

        state.centreOn(0f, 0f)
        assertEquals(300f, state.viewport.toScreenX(0f))
        assertNull(state.nodeAt(300f, 150f), "the corner of the drawing is margin")
    }

    @Test
    fun revealPansOnlyWhenTheNodeIsOutOfView() {
        val state = state()
        state.show(after, null)
        state.reveal(5)
        assertTrue(state.autoFit, "everything is in view: nothing to do, nothing taken over")

        state.zoomTo(6)
        val zoom = state.viewport.zoom
        assertFalse(state.isInView(5))
        state.reveal(5)
        assertTrue(state.isInView(5))
        assertEquals(zoom, state.viewport.zoom)
        val revealed = state.viewport
        state.reveal(5)
        assertEquals(revealed, state.viewport)
    }

    @Test
    fun findsTheNodeUnderAPointOfThePane() {
        val state = state()
        state.show(after, null)
        state.zoomTo(4)
        assertEquals(4L, state.nodeAt(300f, 150f))
        assertEquals(4L, state.nodeAt(300f - 40f, 150f + 15f))
        assertNull(state.nodeAt(300f, 150f + 30f), "below the box")
    }

    @Test
    fun hugeLayoutsChangeAtOnce() {
        val big = LayoutEngine.layout(GraphModel((1L..GraphViewState.ANIMATION_LIMIT + 1L).map { GraphNode(it, if (it == 1L) 0 else 1) }, emptyList()))
        val state = state()
        state.show(before, null)
        assertFalse(state.show(big, null))
        assertTrue(state.settled && state.wholeGraphInView)
    }
}
