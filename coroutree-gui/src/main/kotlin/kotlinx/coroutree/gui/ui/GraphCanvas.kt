package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutree.gui.view.GraphViewState
import kotlinx.coroutree.gui.view.MinimapGeometry
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.LinkRoute
import kotlinx.coroutree.gui.view.graph.Point
import kotlinx.coroutree.gui.view.graph.Rect
import kotlinx.coroutree.gui.view.label
import kotlinx.coroutree.gui.view.toFloatRect
import kotlin.math.max

/** Every kind of cross-link has a dash pattern of its own, so that it can be told from the others without its colour. */
fun EdgeKind.dashes(): FloatArray = when (this) {
    EdgeKind.LAUNCHED_FROM -> floatArrayOf(7f, 4f)
    EdgeKind.CANCELS -> floatArrayOf(9f, 3f, 2f, 3f)
    EdgeKind.INTERRUPTS -> floatArrayOf(2f, 3.5f)
    EdgeKind.RUNS_ON -> floatArrayOf(13f, 3f)
}

private const val LINE_WIDTH = 1.4f
private const val ARROW_LENGTH = 7f
private const val ARROW_HALF_WIDTH = 3.2f
private val LABEL_STYLE = Type.codeSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp)

/**
 * The lines of the graph, and the boxes too while they are too small to be worth a composable ([drawBoxes]).
 * Draws in world coordinates under the viewport's transform and skips what is outside the pane.
 */
@Composable
fun GraphEdges(viewModel: TraceViewModel, state: GraphViewState, drawBoxes: Boolean) {
    val colors = palette
    val measurer = rememberTextMeasurer(cacheSize = 64)
    Canvas(Modifier.fillMaxSize()) {
        val layout = state.layout
        if (layout.isEmpty) return@Canvas
        val viewport = state.viewport
        val pixelsPerDp = density
        val scale = viewport.zoom * pixelsPerDp
        val visible = viewport.visibleWorld(state.width, state.height)
        val selected = viewModel.selectedNodeId
        val highlight = viewModel.highlight
        // A line is at least one pixel on screen, however far out the zoom; what it would lose in width it loses in
        // opacity instead, or a thousand lines zoomed out to a few pixels would be one solid bar.
        val thin = max(LINE_WIDTH, 1f / scale)
        val thick = max(2.6f, 2f / scale)
        val alpha = state.edgeAlpha * (LINE_WIDTH * scale).coerceIn(0.12f, 1f)

        withTransform({
            translate(viewport.panX * pixelsPerDp, viewport.panY * pixelsPerDp)
            scale(scale, scale, Offset.Zero)
        }) {
            if (alpha > 0f) {
                for (trunk in layout.trunks) {
                    if (!trunk.bounds.intersects(visible)) continue
                    val color = colors.edge.copy(alpha = alpha)
                    if (trunk.straight) {
                        line(trunk.top, trunk.drops[0], color, thin)
                    } else {
                        line(trunk.top, Point(trunk.top.x, trunk.busY), color, thin)
                        line(Point(trunk.bounds.left, trunk.busY), Point(trunk.bounds.right, trunk.busY), color, thin)
                        for (drop in trunk.drops) if (drop.x >= visible.left && drop.x <= visible.right) line(Point(drop.x, trunk.busY), drop, color, thin)
                    }
                }
                // What the selection and the selected event point at goes on top of the rest.
                val emphasised = ArrayList<LinkRoute>()
                for (route in layout.routes) {
                    if (!route.bounds.intersects(visible)) continue
                    if (route.link in highlight.links || route.link.from == selected || route.link.to == selected) emphasised += route
                    else link(route, colors.of(route.link.kind).copy(alpha = alpha * 0.85f), thin, measurer, colors)
                }
                highlight.structural?.let { (parent, child) ->
                    layout.trunks.firstOrNull { it.parentId == parent }?.pathTo(child)?.let { path ->
                        path.zipWithNext { a, b -> line(a, b, colors.accent.copy(alpha = state.edgeAlpha), thick) }
                    }
                }
                for (route in emphasised) {
                    val color = if (route.link in highlight.links) colors.accent else colors.of(route.link.kind)
                    link(route, color.copy(alpha = state.edgeAlpha), thick, measurer, colors)
                }
            }
            if (drawBoxes) {
                for (box in layout.boxesIn(visible)) {
                    val rect = state.rect(box.id) ?: continue
                    val color = colors.of(viewModel.snapshot.node(box.id)?.state ?: continue)
                    val fade = state.alpha(box.id) * (if (box.node.dimmed) 0.5f else 1f)
                    val topLeft = Offset(rect.left, rect.top)
                    val size = Size(rect.width, rect.height)
                    drawRoundRect(colors.surface, topLeft, size, CornerRadius(6f))
                    drawRoundRect(color.copy(alpha = 0.35f * fade), topLeft, size, CornerRadius(6f))
                    val outline = if (box.id == selected || box.id in highlight.nodes) colors.accent else color.copy(alpha = fade)
                    drawRoundRect(outline, topLeft, size, CornerRadius(6f), style = Stroke(if (box.id == selected) thick * 1.6f else thin))
                }
            }
        }
    }
}

private fun DrawScope.line(from: Point, to: Point, color: Color, width: Float) =
    drawLine(color, Offset(from.x.toFloat(), from.y.toFloat()), Offset(to.x.toFloat(), to.y.toFloat()), width, StrokeCap.Round)

private fun DrawScope.link(route: LinkRoute, color: Color, width: Float, measurer: TextMeasurer, colors: Palette) {
    val points = route.points
    val last = points.last()
    val beforeLast = points[points.size - 2]
    // The line stops where the arrowhead begins, so that a dash never pokes through its tip.
    val dx = (last.x - beforeLast.x).coerceIn(-1, 1)
    val dy = (last.y - beforeLast.y).coerceIn(-1, 1)
    val path = Path().apply {
        moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (i in 1 until points.size - 1) lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        lineTo(last.x - dx * ARROW_LENGTH, last.y - dy * ARROW_LENGTH)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Butt, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(route.link.kind.dashes())))
    drawPath(
        Path().apply {
            moveTo(last.x.toFloat(), last.y.toFloat())
            lineTo(last.x - dx * ARROW_LENGTH - dy * ARROW_HALF_WIDTH, last.y - dy * ARROW_LENGTH - dx * ARROW_HALF_WIDTH)
            lineTo(last.x - dx * ARROW_LENGTH + dy * ARROW_HALF_WIDTH, last.y - dy * ARROW_LENGTH + dx * ARROW_HALF_WIDTH)
            close()
        },
        color,
    )
    drawCircle(color, 2.2f, Offset(points[0].x.toFloat(), points[0].y.toFloat()))

    val label = route.label ?: return
    val pixelsPerDp = density
    drawRect(colors.canvas, Offset(label.left.toFloat(), label.top.toFloat()), Size(label.width.toFloat(), label.height.toFloat()))
    val text = measurer.measure(
        route.link.kind.label, LABEL_STYLE.copy(color = color, fontWeight = FontWeight.Medium),
        overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
        constraints = Constraints(maxWidth = (label.width * density).toInt().coerceAtLeast(1)),
        density = this,
    )
    // Measured in pixels, drawn under a transform whose unit is the dp.
    withTransform({
        translate(label.left + (label.width - text.size.width / pixelsPerDp) / 2, label.top + (label.height - text.size.height / pixelsPerDp) / 2)
        scale(1 / pixelsPerDp, 1 / pixelsPerDp, Offset.Zero)
    }) { drawText(text) }
}

/** The whole graph in a corner, the pane's part of it framed; a click or a drag moves the pane there. */
@Composable
fun Minimap(viewModel: TraceViewModel, state: GraphViewState, modifier: Modifier = Modifier) {
    val colors = palette
    val shape = RoundedCornerShape(6.dp)
    Canvas(
        modifier.size(MINIMAP_WIDTH.dp, MINIMAP_HEIGHT.dp).clip(shape).background(colors.surface.copy(alpha = 0.92f)).border(1.dp, colors.border, shape)
            .testTag("minimap")
            .pointerInput(state) {
                detectTapGestures { position -> state.moveTo(position, density) }
            }
            .pointerInput(state) {
                detectDragGestures { change, _ ->
                    change.consume()
                    state.moveTo(change.position, density)
                }
            },
    ) {
        val layout = state.layout
        val map = MinimapGeometry(layout.bounds, MINIMAP_WIDTH - 2 * MINIMAP_PAD, MINIMAP_HEIGHT - 2 * MINIMAP_PAD)
        val pixelsPerDp = density
        withTransform({
            scale(pixelsPerDp, pixelsPerDp, Offset.Zero)
            translate(MINIMAP_PAD, MINIMAP_PAD)
        }) {
            for (box in layout.boxes) {
                val rect = map.toMap(box.rect.toFloatRect())
                val color = colors.of(viewModel.snapshot.node(box.id)?.state ?: continue)
                drawRect(
                    if (box.id == viewModel.selectedNodeId) colors.accent else color.copy(alpha = if (box.node.dimmed) 0.35f else 0.8f),
                    Offset(rect.left, rect.top), Size(max(rect.width, 1.5f), max(rect.height, 1.5f)),
                )
            }
            val pane = map.toMap(state.viewport.visibleWorld(state.width, state.height).clippedTo(layout.bounds).toFloatRect())
            drawRect(colors.accent.copy(alpha = 0.12f), Offset(pane.left, pane.top), Size(pane.width, pane.height))
            drawRect(colors.accent, Offset(pane.left, pane.top), Size(pane.width, pane.height), style = Stroke(1.2f))
        }
    }
}

private const val MINIMAP_WIDTH = 190f
private const val MINIMAP_HEIGHT = 120f
private const val MINIMAP_PAD = 6f

private fun GraphViewState.moveTo(position: Offset, density: Float) {
    val map = MinimapGeometry(layout.bounds, MINIMAP_WIDTH - 2 * MINIMAP_PAD, MINIMAP_HEIGHT - 2 * MINIMAP_PAD)
    // The drawing rarely has the minimap's proportions; a click beside it means the nearest part of it.
    centreOn(
        map.toWorldX(position.x / density - MINIMAP_PAD).coerceIn(layout.bounds.left.toFloat(), layout.bounds.right.toFloat()),
        map.toWorldY(position.y / density - MINIMAP_PAD).coerceIn(layout.bounds.top.toFloat(), layout.bounds.bottom.toFloat()),
    )
}

private fun Rect.clippedTo(other: Rect): Rect {
    val left = left.coerceIn(other.left, other.right)
    val top = top.coerceIn(other.top, other.bottom)
    return Rect(left, top, right.coerceIn(left, other.right), bottom.coerceIn(top, other.bottom))
}
