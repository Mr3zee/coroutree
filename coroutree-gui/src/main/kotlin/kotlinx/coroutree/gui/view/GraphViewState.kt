package kotlinx.coroutree.gui.view

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutree.gui.view.graph.GraphLayout
import kotlin.math.abs

/**
 * What the graph pane shows right now: the settled layout it is heading for, how far the boxes have glided towards
 * it, and the viewport. Holds no Compose UI, only snapshot state, so tests drive it directly.
 *
 * When the layout changes (DESIGN §6.1, "change over time") boxes glide from where they are drawn to their new
 * places and new ones fade in; [advance] is called with the fraction of the way. The viewport stays anchored: the
 * selected node, or without one the node that was nearest the middle of the pane, stays where it is on screen while
 * the rest moves around it. Until the user takes over the viewport ([autoFit]) it keeps the whole graph in view
 * instead, which is what one wants of a live trace that starts as a single box.
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

    /** Where the boxes were drawn when the current glide began; `null` when there is none. */
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
     * Makes [newLayout] the one to show. Returns whether there is a glide to run: the caller then calls [advance]
     * with fractions up to 1. A first layout, or one too big to animate, is shown at once.
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

    /** Where the box of [id] is drawn at this moment, in world coordinates; `null` if it is not in the layout. */
    fun rect(id: Long): FloatRect? {
        val target = layout.box(id)?.rect?.toFloatRect() ?: return null
        val start = from?.get(id) ?: return target
        return if (settled) target else start.lerp(target, progress)
    }

    /** Boxes that were not there before fade in where they belong rather than fly in from somewhere. */
    fun alpha(id: Long): Float = if (settled || from?.containsKey(id) != false) 1f else progress

    /** Edges are those of the new layout; they fade in over the second half of the glide, when the boxes are nearly there. */
    val edgeAlpha: Float get() = if (settled) 1f else ((progress - 0.5f) * 2f).coerceAtLeast(0f)

    // --- gestures ---------------------------------------------------------------------------------------------------

    fun pan(dx: Float, dy: Float) {
        autoFit = false
        viewport = viewport.pannedBy(dx, dy)
    }

    fun zoom(factor: Float, anchorX: Float = width / 2, anchorY: Float = height / 2) {
        autoFit = false
        viewport = viewport.zoomedBy(factor, anchorX, anchorY, minZoom = fitting().zoom)
    }

    /** Zoom-to-fit; the viewport goes back to following the graph as it grows. */
    fun fit() {
        autoFit = true
        viewport = fitting()
    }

    /** Zoom-to-selection: the node at 1:1 in the middle of the pane. */
    fun zoomTo(id: Long) {
        val rect = layout.box(id)?.rect?.toFloatRect() ?: return
        autoFit = false
        viewport = viewport.centredOn(rect, width, height, zoom = 1f)
    }

    /** Brings the node into view if it is not, without touching the zoom. */
    fun reveal(id: Long) {
        if (width <= 0f || height <= 0f) return // not on screen yet; it will open fitted, with everything in view
        val rect = layout.box(id)?.rect?.toFloatRect() ?: return
        val revealed = viewport.revealing(rect, width, height)
        if (revealed != viewport) {
            autoFit = false
            viewport = revealed
        }
    }

    /** The middle of the pane goes to the world point ([worldX], [worldY]): a click on the minimap. */
    fun centreOn(worldX: Float, worldY: Float) {
        autoFit = false
        viewport = viewport.centredOn(FloatRect(worldX, worldY, worldX, worldY), width, height)
    }

    /** Nothing of the graph is outside the pane: there is nowhere to navigate to, and no use for a minimap. */
    val wholeGraphInView: Boolean
        get() {
            val visible = viewport.visibleWorld(width, height)
            val bounds = layout.bounds
            return visible.left <= bounds.left && visible.top <= bounds.top && visible.right >= bounds.right && visible.bottom >= bounds.bottom
        }

    /** Whether the whole box of [id] is inside the pane right now. */
    fun isInView(id: Long): Boolean {
        val onScreen = rect(id)?.let(viewport::toScreen) ?: return false
        return onScreen.left >= 0f && onScreen.top >= 0f && onScreen.right <= width && onScreen.bottom <= height
    }

    /** The node drawn at a point of the pane, by its settled place. */
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
        /** Beyond this many boxes a change is shown at once: a glide of ten thousand boxes is a slide show. */
        const val ANIMATION_LIMIT = 3000
    }
}
