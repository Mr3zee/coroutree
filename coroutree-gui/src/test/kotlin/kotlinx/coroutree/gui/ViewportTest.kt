package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.view.FloatRect
import kotlinx.coroutree.gui.view.MinimapGeometry
import kotlinx.coroutree.gui.view.Viewport
import kotlinx.coroutree.gui.view.graph.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportTest {
    private val pane = 800f to 400f

    @Test
    fun mapsWorldToScreenAndBack() {
        val viewport = Viewport(zoom = 0.5f, panX = 30f, panY = -10f)
        assertEquals(80f, viewport.toScreenX(100f))
        assertEquals(40f, viewport.toScreenY(100f))
        assertEquals(100f, viewport.toWorldX(80f))
        assertEquals(100f, viewport.toWorldY(40f))
        assertEquals(FloatRect(30f, -10f, 80f, 15f), viewport.toScreen(FloatRect(0f, 0f, 100f, 50f)))
        assertEquals(Rect(-60, 20, 1540, 820), viewport.visibleWorld(pane.first, pane.second))
    }

    @Test
    fun zoomKeepsThePointUnderThePointerAndStaysWithinLimits() {
        val start = Viewport(zoom = 1f, panX = 40f, panY = 20f)
        val worldX = start.toWorldX(300f)
        val worldY = start.toWorldY(150f)
        val zoomed = start.zoomedBy(1.6f, 300f, 150f)
        assertEquals(1.6f, zoomed.zoom)
        assertEquals(300f, zoomed.toScreenX(worldX), 0.001f)
        assertEquals(150f, zoomed.toScreenY(worldY), 0.001f)

        assertEquals(Viewport.MAX_ZOOM, start.zoomedBy(100f, 0f, 0f).zoom)
        assertEquals(Viewport.MIN_ZOOM, start.zoomedBy(0.0001f, 0f, 0f).zoom)
        assertEquals(0.4f, start.zoomedBy(0.0001f, 0f, 0f, minZoom = 0.4f).zoom, "no further out than the whole graph")
        assertEquals(0.005f, Viewport(zoom = 0.005f).zoomedBy(0.5f, 0f, 0f, minZoom = 0.005f).zoom, "a graph that needs less than the usual minimum gets it")
        assertEquals(0.01f, Viewport(zoom = 0.005f).zoomedBy(2f, 0f, 0f).zoom)
    }

    @Test
    fun fitsTheWholeGraphCentredAndNeverMagnifies() {
        val wide = Viewport.fitting(Rect(0, 0, 1600, 400), pane.first, pane.second)
        assertEquals(0.5f, wide.zoom)
        assertEquals(FloatRect(0f, 100f, 800f, 300f), wide.toScreen(FloatRect(0f, 0f, 1600f, 400f)))

        val small = Viewport.fitting(Rect(0, 0, 200, 100), pane.first, pane.second)
        assertEquals(1f, small.zoom)
        assertEquals(FloatRect(300f, 150f, 500f, 250f), small.toScreen(FloatRect(0f, 0f, 200f, 100f)))

        val huge = Viewport.fitting(Rect(0, 0, 1_600_000, 4000), pane.first, pane.second)
        assertEquals(0.0005f, huge.zoom, "ten thousand leaves side by side still fit")
        assertEquals(Viewport(), Viewport.fitting(Rect.EMPTY, pane.first, pane.second))
        assertEquals(Viewport(), Viewport.fitting(Rect(0, 0, 10, 10), 0f, 0f))
    }

    @Test
    fun revealsWithTheSmallestPanThatDoes() {
        val viewport = Viewport(zoom = 1f)
        val inView = FloatRect(100f, 100f, 200f, 140f)
        assertEquals(viewport, viewport.revealing(inView, pane.first, pane.second))

        val right = viewport.revealing(FloatRect(900f, 100f, 1000f, 140f), pane.first, pane.second)
        assertEquals(Viewport(1f, panX = -232f, panY = 0f), right, "just far enough for the box and a margin")
        val aboveLeft = viewport.revealing(FloatRect(-300f, -200f, -200f, -160f), pane.first, pane.second)
        assertEquals(FloatRect(32f, 32f, 132f, 72f), aboveLeft.toScreen(FloatRect(-300f, -200f, -200f, -160f)))

        // Too big for the pane at this zoom: centred.
        val big = Viewport(zoom = 2f).revealing(FloatRect(1000f, 0f, 1500f, 40f), pane.first, pane.second)
        assertEquals(400f, big.toScreen(FloatRect(1000f, 0f, 1500f, 40f)).centerX)
        assertEquals(2f, big.zoom)
    }

    @Test
    fun centresAndInterpolates() {
        val centred = Viewport(zoom = 0.3f).centredOn(FloatRect(1000f, 500f, 1100f, 540f), pane.first, pane.second, zoom = 1f)
        assertEquals(FloatRect(350f, 180f, 450f, 220f), centred.toScreen(FloatRect(1000f, 500f, 1100f, 540f)))
        val halfway = Viewport(1f, 0f, 0f).lerp(Viewport(0.5f, 100f, -50f), 0.5f)
        assertEquals(Viewport(0.75f, 50f, -25f), halfway)
        assertEquals(FloatRect(5f, 10f, 15f, 30f), FloatRect(0f, 0f, 10f, 20f).lerp(FloatRect(10f, 20f, 20f, 40f), 0.5f))
    }

    @Test
    fun minimapScalesTheDrawingIntoItsCornerAndBack() {
        // A drawing four times as wide as high, in a minimap that is not: it is letterboxed, centred vertically.
        val map = MinimapGeometry(Rect(0, 0, 2000, 500), 200f, 100f)
        assertEquals(0.1f, map.scale)
        assertEquals(FloatRect(0f, 25f, 200f, 75f), map.toMap(FloatRect(0f, 0f, 2000f, 500f)))
        assertEquals(FloatRect(50f, 35f, 60f, 39f), map.toMap(FloatRect(500f, 100f, 600f, 140f)))
        assertEquals(1000f, map.toWorldX(100f))
        assertEquals(250f, map.toWorldY(50f))
        assertTrue(map.toWorldY(0f) < 0f, "above the drawing")
        assertEquals(1f, MinimapGeometry(Rect.EMPTY, 200f, 100f).scale)
    }
}
