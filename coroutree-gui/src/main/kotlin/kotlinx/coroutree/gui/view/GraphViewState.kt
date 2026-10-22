package kotlinx.coroutree.gui.view

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutree.gui.view.graph.GraphLayout
import kotlin.math.abs

/**
 * What the graph pane shows: the layout, the animation of the boxes towards it, and the viewport.
 * During the animation the selected node stays where it is on screen. Without a selection, the node nearest the
 * middle of the pane does. While [autoFit] is true, the viewport fits the whole graph instead (DESIGN §6.1).
 */
class GraphViewState {
    var viewport: Viewport by mutableStateOf(Viewport())
        private set
    var layout: GraphLayout by mutableStateOf(GraphLayout.EMPTY)
        private set

    /** 1 when settled. */
    var progress: Float by mutableFloatStateOf(1f)
        private set
    var autoFit: Boolean by mutableStateOf(true)
        private set
    var width: Float by mutableFloatStateOf(0f)
        private set
    var height: Float by mutableFloatStateOf(0f)
        private set

    /** Box positions at animation start, or null without an animation. */
    private var from: Map<Long, FloatRect>? = null
    private var viewportAtStart = Viewport()
    private var anchorId: Long? = null

    val settled: Boolean get() = progress >= 1f

    fun resize(newWidth: Float, newHeight: Float) {
        if (newWidth == width && newHeight == height) return
        width = newWidth
        height = newHeight
        if (autoFit) viewport = fitting()
    }

    /**
     * Makes [newLayout] the one to show. Returns whether an animation runs: then the caller calls [advance] with
     * fractions up to 1. The first layout, and one with more than [ANIMATION_LIMIT] boxes, appears without animation.
     */
    fun show(newLayout: GraphLayout, selectedNodeId: Long?): Boolean {
        if (newLayout === layout) return !settled
        val old = layout
        val animate = !old.isEmpty && !newLayout.isEmpty && old.boxes.size <= ANIMATION_LIMIT && newLayout.boxes.size <= ANIMATION_LIMIT
        val drawn = if (animate) old.boxes.associate { it.id to rect(it.id)!! } else null
        anchorId = if (drawn == null) null else
            selectedNodeId?.takeIf { it in drawn && newLayout.box(it) != null } ?: nearestToCentre(drawn, newLayout)
        from = drawn
        layout = newLayout
        viewportAtStart = viewport
        if (drawn == null) {
            progress = 1f
            if (autoFit) viewport = fitting()
            return false
        }
        progress = 0f
        return true
    }

    fun advance(fraction: Float) {
        val target = fraction.coerceIn(0f, 1f)
        val anchor = anchorId
        val before = anchor?.let(::rect)
        progress = target
        if (autoFit) {
            viewport = viewportAtStart.lerp(fitting(), target)
        } else if (before != null) {
            val after = rect(anchor)!!
            viewport = viewport.pannedBy((before.centerX - after.centerX) * viewport.zoom, (before.centerY - after.centerY) * viewport.zoom)
        }
        if (target >= 1f) from = null
    }

    /** Current box position in world coordinates, or null if [id] isn't in the layout. */
    fun rect(id: Long): FloatRect? {
        val target = layout.box(id)?.rect?.toFloatRect() ?: return null
        val start = from?.get(id) ?: return target
        return if (settled) target else start.lerp(target, progress)
    }

    fun alpha(id: Long): Float = if (settled || from?.containsKey(id) != false) 1f else progress

    /** New-layout edges fade in during the second half of the animation. */
    val edgeAlpha: Float get() = if (settled) 1f else ((progress - 0.5f) * 2f).coerceAtLeast(0f)

    fun pan(dx: Float, dy: Float) {
        autoFit = false
        viewport = viewport.pannedBy(dx, dy)
    }

    fun zoom(factor: Float, anchorX: Float = width / 2, anchorY: Float = height / 2) {
        autoFit = false
        viewport = viewport.zoomedBy(factor, anchorX, anchorY, minZoom = fitting().zoom)
    }

    /** Reenables automatic fitting as the graph grows. */
    fun fit() {
        autoFit = true
        viewport = fitting()
    }

    /** Centers the node at 1:1 zoom and disables automatic fitting. */
    fun zoomTo(id: Long) {
        val rect = layout.box(id)?.rect?.toFloatRect() ?: return
        autoFit = false
        viewport = viewport.centredOn(rect, width, height, zoom = 1f)
    }

    /** Reveals an offscreen node at the current zoom. */
    fun reveal(id: Long) {
        if (width <= 0f || height <= 0f) return // the first layout opens fitted
        val rect = layout.box(id)?.rect?.toFloatRect() ?: return
        val revealed = viewport.revealing(rect, width, height)
        if (revealed != viewport) {
            autoFit = false
            viewport = revealed
        }
    }

    fun centreOn(worldX: Float, worldY: Float) {
        autoFit = false
        viewport = viewport.centredOn(FloatRect(worldX, worldY, worldX, worldY), width, height)
    }

    val wholeGraphInView: Boolean
        get() {
            val visible = viewport.visibleWorld(width, height)
            val bounds = layout.bounds
            return visible.left <= bounds.left && visible.top <= bounds.top && visible.right >= bounds.right && visible.bottom >= bounds.bottom
        }

    fun isInView(id: Long): Boolean {
        val onScreen = rect(id)?.let(viewport::toScreen) ?: return false
        return onScreen.left >= 0f && onScreen.top >= 0f && onScreen.right <= width && onScreen.bottom <= height
    }

    /** Uses settled box positions even during animation. */
    fun nodeAt(screenX: Float, screenY: Float): Long? =
        layout.boxAt(viewport.toWorldX(screenX).toInt(), viewport.toWorldY(screenY).toInt())?.id

    private fun fitting(): Viewport = Viewport.fitting(layout.bounds, width, height)

    private fun nearestToCentre(drawn: Map<Long, FloatRect>, newLayout: GraphLayout): Long? {
        val cx = viewport.toWorldX(width / 2)
        val cy = viewport.toWorldY(height / 2)
        return layout.boxesIn(viewport.visibleWorld(width, height))
            .filter { newLayout.box(it.id) != null }
            .minByOrNull { drawn.getValue(it.id).let { r -> abs(r.centerX - cx) + abs(r.centerY - cy) } }?.id
    }

    companion object {
        const val ANIMATION_LIMIT = 3000
    }
}
