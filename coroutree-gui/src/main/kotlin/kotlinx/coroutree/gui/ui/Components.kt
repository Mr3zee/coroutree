package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.NodeState

/** Single-line text in the theme's colours; everything in the tool windows is one line per fact. */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    color: Color = palette.text,
    maxLines: Int = 1,
) {
    BasicText(text, modifier, style.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis, softWrap = maxLines > 1)
}

/**
 * The kind of a node as a pictogram, in the colour of the text around it: kind is never encoded in colour, which
 * belongs to the state (DESIGN §6.1). Threads are strands, a coroutine is a loop, a scope is a frame around things,
 * a context change is a swap, a task is a play button, a pool is a grid.
 */
@Composable
fun KindIcon(kind: NodeKind, modifier: Modifier = Modifier, color: Color = palette.text) {
    Canvas(modifier.size(14.dp)) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), stroke.width, StrokeCap.Round)
        when (kind) {
            NodeKind.THREAD -> for (x in listOf(0.25f, 0.5f, 0.75f)) line(x, 0.12f, x, 0.88f)
            NodeKind.COROUTINE -> {
                drawArc(color, startAngle = -20f, sweepAngle = 290f, useCenter = false, topLeft = Offset(s * 0.15f, s * 0.15f), size = Size(s * 0.7f, s * 0.7f), style = stroke)
                line(0.85f, 0.4f, 0.85f, 0.08f)
                line(0.85f, 0.4f, 0.55f, 0.36f)
            }
            NodeKind.SCOPE -> {
                drawRoundRect(color, Offset(s * 0.1f, s * 0.14f), Size(s * 0.8f, s * 0.72f), CornerRadius(s * 0.18f), stroke)
                drawCircle(color, s * 0.09f, Offset(s * 0.36f, s * 0.5f))
                drawCircle(color, s * 0.09f, Offset(s * 0.64f, s * 0.5f))
            }
            NodeKind.CONTEXT_CHANGE -> {
                line(0.1f, 0.33f, 0.9f, 0.33f)
                line(0.9f, 0.33f, 0.68f, 0.13f)
                line(0.9f, 0.67f, 0.1f, 0.67f)
                line(0.1f, 0.67f, 0.32f, 0.87f)
            }
            NodeKind.TASK -> drawPath(
                Path().apply {
                    moveTo(s * 0.24f, s * 0.12f)
                    lineTo(s * 0.86f, s * 0.5f)
                    lineTo(s * 0.24f, s * 0.88f)
                    close()
                },
                color,
            )
            NodeKind.POOL -> for (x in listOf(0.12f, 0.56f)) for (y in listOf(0.12f, 0.56f)) {
                drawRoundRect(color, Offset(s * x, s * y), Size(s * 0.32f, s * 0.32f), CornerRadius(s * 0.06f))
            }
            NodeKind.UNSPECIFIED -> drawCircle(color, s * 0.16f, center, style = stroke)
        }
    }
}

val NodeState.label: String get() = name.lowercase()

@Composable
fun StateBadge(state: NodeState, modifier: Modifier = Modifier) {
    val color = palette.of(state)
    Row(
        modifier.clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = 0.14f)).padding(start = 6.dp, end = 8.dp).height(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Final states get a hollow dot: nothing is going on there any more.
        val dot = Modifier.size(7.dp).clip(CircleShape)
        Box(if (state.isFinal) dot.border(1.5.dp, color, CircleShape) else dot.background(color))
        Label(state.label, style = Type.small.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

@Composable
fun Chip(text: String, color: Color = palette.textDim, modifier: Modifier = Modifier, style: TextStyle = Type.codeSmall) {
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Label(text, style = style, color = color)
    }
}

@Composable
fun PaneHeader(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(palette.header).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title.uppercase(), style = Type.label, color = palette.textDim, modifier = Modifier.weight(1f))
        trailing()
    }
    Divider()
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(palette.border))
}

/** Background for a list row: selection wins over "related to the selection", which wins over hover. */
fun Modifier.rowBackground(selected: Boolean, related: Boolean = false): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color = when {
        selected -> palette.selection
        related -> palette.related
        hovered -> palette.hover
        else -> Color.Transparent
    }
    hoverable(interaction).background(color)
}

fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/** Scrolls only when the item is not already fully in view, so clicking around does not make the list jump. */
suspend fun LazyListState.revealItem(index: Int) {
    if (index < 0) return
    val visible = layoutInfo.visibleItemsInfo
    val fullyVisible = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= layoutInfo.viewportEndOffset }
    if (!fullyVisible) scrollToItem((index - 3).coerceAtLeast(0))
}
