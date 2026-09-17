package kotlinx.coroutree.gui.ui

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
import androidx.compose.ui.graphics.Color
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

@Composable
fun KindGlyph(kind: NodeKind, dimmed: Boolean = false, modifier: Modifier = Modifier) {
    val letter = when (kind) {
        NodeKind.THREAD -> "T"
        NodeKind.COROUTINE -> "C"
        NodeKind.SCOPE -> "S"
        NodeKind.CONTEXT_CHANGE -> "X"
        NodeKind.TASK -> "K"
        NodeKind.POOL -> "P"
        NodeKind.UNSPECIFIED -> "?"
    }
    val color = palette.of(kind).copy(alpha = if (dimmed) 0.55f else 1f)
    Box(
        modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = color.alpha * 0.18f)).border(1.dp, color.copy(alpha = color.alpha * 0.55f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(letter, style = TextStyle(color = color, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold))
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
