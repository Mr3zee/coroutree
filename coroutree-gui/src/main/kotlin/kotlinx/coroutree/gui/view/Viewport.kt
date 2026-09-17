package kotlinx.coroutree.gui.view

import kotlinx.coroutree.gui.view.graph.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** A rectangle in fractions of a dp: where something is on screen, or on its way between two layouts. */
data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun lerp(to: FloatRect, fraction: Float): FloatRect = FloatRect(
        left + (to.left - left) * fraction, top + (to.top - top) * fraction,
        right + (to.right - right) * fraction, bottom + (to.bottom - bottom) * fraction,
    )
}

fun Rect.toFloatRect(): FloatRect = FloatRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

/**
 * How the graph's coordinates map to the pane: `screen = world · zoom + pan`, both in dp. Immutable; every gesture
 * is a function from one viewport to the next.
 */
data class Viewport(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    fun toScreenX(worldX: Float): Float = worldX * zoom + panX
    fun toScreenY(worldY: Float): Float = worldY * zoom + panY
    fun toWorldX(screenX: Float): Float = (screenX - panX) / zoom
    fun toWorldY(screenY: Float): Float = (screenY - panY) / zoom

    fun toScreen(world: FloatRect): FloatRect = FloatRect(toScreenX(world.left), toScreenY(world.top), toScreenX(world.right), toScreenY(world.bottom))

    fun pannedBy(dx: Float, dy: Float): Viewport = copy(panX = panX + dx, panY = panY + dy)

    /**
     * Zooms by [factor], keeping the world point under the screen point ([anchorX], [anchorY]) where it is.
     * [minZoom] is how far out one may go: [MIN_ZOOM], or less where the whole graph needs less to fit.
     */
    fun zoomedBy(factor: Float, anchorX: Float, anchorY: Float, minZoom: Float = MIN_ZOOM): Viewport {
        val newZoom = (zoom * factor).coerceIn(minOf(minZoom, zoom), maxOf(MAX_ZOOM, zoom))
        val applied = newZoom / zoom
        return Viewport(newZoom, anchorX - (anchorX - panX) * applied, anchorY - (anchorY - panY) * applied)
    }

    /** The part of the world a pane of [width] × [height] shows, rounded outwards. */
    fun visibleWorld(width: Float, height: Float): Rect = Rect(
        floor(toWorldX(0f)).toInt(), floor(toWorldY(0f)).toInt(), ceil(toWorldX(width)).toInt(), ceil(toWorldY(height)).toInt(),
    )

    /** Pans as little as it takes to have [world] in view with [margin] around it; centres what does not fit. Zoom stays. */
    fun revealing(world: FloatRect, width: Float, height: Float, margin: Float = 32f): Viewport {
        val screen = toScreen(world)
        fun shift(from: Float, to: Float, size: Float): Float = when {
            to - from + 2 * margin > size -> size / 2 - (from + to) / 2
            from < margin -> margin - from
            to > size - margin -> size - margin - to
            else -> 0f
        }
        return pannedBy(shift(screen.left, screen.right, width), shift(screen.top, screen.bottom, height))
    }

    /** [world]'s centre in the middle of the pane at [zoom]. */
    fun centredOn(world: FloatRect, width: Float, height: Float, zoom: Float = this.zoom): Viewport =
        Viewport(zoom, width / 2 - world.centerX * zoom, height / 2 - world.centerY * zoom)

    fun lerp(to: Viewport, fraction: Float): Viewport =
        Viewport(zoom + (to.zoom - zoom) * fraction, panX + (to.panX - panX) * fraction, panY + (to.panY - panY) * fraction)

    companion object {
        const val MIN_ZOOM = 0.02f
        const val MAX_ZOOM = 3f

        /**
         * All of [bounds] in a pane of [width] × [height], centred; never magnified beyond 1:1, and as small as it
         * takes: a graph of ten thousand leaves is a hundred times wider than any pane.
         */
        fun fitting(bounds: Rect, width: Float, height: Float): Viewport {
            if (bounds.width <= 0 || bounds.height <= 0 || width <= 0f || height <= 0f) return Viewport()
            val zoom = min(1f, min(width / bounds.width, height / bounds.height))
            return Viewport().centredOn(bounds.toFloatRect(), width, height, zoom)
        }
    }
}

/** The whole drawing scaled into a minimap of [width] × [height], centred, and the way back from a click on it. */
class MinimapGeometry(private val bounds: Rect, width: Float, height: Float) {
    val scale: Float = if (bounds.width <= 0 || bounds.height <= 0) 1f else min(width / bounds.width, height / bounds.height)
    private val offsetX = (width - bounds.width * scale) / 2 - bounds.left * scale
    private val offsetY = (height - bounds.height * scale) / 2 - bounds.top * scale

    fun toMap(world: FloatRect): FloatRect =
        FloatRect(world.left * scale + offsetX, world.top * scale + offsetY, world.right * scale + offsetX, world.bottom * scale + offsetY)

    fun toWorldX(mapX: Float): Float = (mapX - offsetX) / scale
    fun toWorldY(mapY: Float): Float = (mapY - offsetY) / scale
}
