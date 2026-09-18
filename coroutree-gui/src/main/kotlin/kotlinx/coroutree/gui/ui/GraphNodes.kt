package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutree.gui.view.Formatting
import kotlinx.coroutree.gui.view.GraphViewState
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.NodeBox
import kotlinx.coroutree.gui.view.graphTitle
import kotlinx.coroutree.gui.view.label
import kotlinx.coroutree.model.ContextElementKind
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.PaceSetting
import kotlinx.coroutree.model.tree.describe
import kotlinx.coroutree.model.tree.shortLocation
import kotlin.math.roundToInt

/**
 * The boxes in view, each a composable placed at its world coordinates; the viewport is a layer transform over all of
 * them, so panning and zooming move a layer instead of laying anything out again. During a glide only placement runs.
 */
@Composable
fun GraphNodes(viewModel: TraceViewModel, state: GraphViewState, boxes: List<NodeBox>, hover: HoverCardState) {
    val snapshot = viewModel.snapshot
    val highlight = viewModel.highlight
    Layout(
        content = {
            for (box in boxes) {
                val node = snapshot.node(box.id) ?: continue
                key(box.id) {
                    NodeView(
                        node = node,
                        dimmed = box.node.dimmed,
                        selected = box.id == viewModel.selectedNodeId,
                        related = box.id in highlight.nodes,
                        pace = viewModel.ownPace(box.id),
                        onSelect = { viewModel.selectNode(box.id) },
                        hover = hover,
                        modifier = Modifier.graphicsLayer { alpha = state.alpha(box.id) },
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val viewport = state.viewport
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = viewport.zoom
            scaleY = viewport.zoom
            translationX = viewport.panX * density
            translationY = viewport.panY * density
        },
    ) { measurables, constraints ->
        val present = boxes.filter { snapshot.node(it.id) != null }
        val placeables = measurables.mapIndexed { i, measurable ->
            val rect = present[i].rect
            measurable.measure(Constraints.fixed(rect.width.dp.roundToPx(), rect.height.dp.roundToPx()))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                val rect = state.rect(present[i].id) ?: return@forEachIndexed
                placeable.place((rect.left * density).roundToInt(), (rect.top * density).roundToInt())
            }
        }
    }
}

/**
 * A node of the graph: kind icon and title, and under them the state in a word. The box is coloured by the state
 * and by nothing else. Everything longer (site, context, thread) is in the hover card and the details pane.
 */
@Composable
private fun NodeView(node: NodeSnapshot, dimmed: Boolean, selected: Boolean, related: Boolean, pace: PaceSetting?, onSelect: () -> Unit, hover: HoverCardState, modifier: Modifier) {
    val color = palette.of(node.state)
    val shape = RoundedCornerShape(6.dp)
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction, node.id) {
        interaction.interactions.collect {
            when (it) {
                is HoverInteraction.Enter -> hover.enter(node.id)
                is HoverInteraction.Exit -> hover.leave()
            }
        }
    }
    val fade = if (dimmed) 0.5f else 1f
    // The mark of a setting lies over a corner of the box: the box is as large as the layout made it, with or without.
    // The lower right one, beside the state, which is a short word; the title above it takes the whole width.
    Box(modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize()
            .clip(shape)
            .background(palette.surface)
            // Finished nodes recede: nothing is going on there any more.
            .background(color.copy(alpha = (if (node.state.isFinal) 0.09f else 0.2f) * fade))
            .then(
                when {
                    selected -> Modifier.border(2.5.dp, palette.accent, shape)
                    related -> Modifier.border(2.dp, palette.accent.copy(alpha = 0.75f), shape)
                    else -> Modifier.border(1.25.dp, color.copy(alpha = (if (node.state.isFinal) 0.6f else 0.95f) * fade), shape)
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSelect)
            .testTag("node-${node.id}")
            .semantics {
                this.selected = selected
                stateDescription = node.state.label + (if (related) ", highlighted" else "") + (pace?.let { ", subtree " + it.describe() } ?: "")
            }
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KindIcon(node.info.kind, color = palette.text.copy(alpha = 0.8f * fade))
            Label(node.graphTitle, style = Type.code.copy(fontWeight = if (dimmed) FontWeight.Normal else FontWeight.Medium), color = palette.text.copy(alpha = fade))
        }
        Row(Modifier.padding(start = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val dot = Modifier.size(6.dp).clip(CircleShape)
            Box(if (node.state.isFinal) dot.border(1.5.dp, color.copy(alpha = fade), CircleShape) else dot.background(color.copy(alpha = fade)))
            Label(node.state.label, style = Type.small.copy(fontWeight = FontWeight.Medium), color = color.copy(alpha = fade))
        }
    }
    if (pace != null) PaceMarker(pace, Modifier.align(Alignment.BottomEnd).padding(4.dp).testTag("pace-marker-${node.id}"))
    }
}

/**
 * Which node's hover card is up. It comes after the pointer has rested on a box for a moment, and stays while the
 * pointer is on the box or on the card, so that what is on the card can be clicked.
 */
class HoverCardState(private val scope: CoroutineScope) {
    var nodeId: Long? by mutableStateOf(null)
        private set
    private var pending: Job? = null

    fun enter(id: Long) = schedule(SHOW_DELAY) { nodeId = id }

    fun leave() = schedule(HIDE_DELAY) { nodeId = null }

    /** The pointer reached the card: whatever was about to hide it is off. */
    fun keep() {
        pending?.cancel()
    }

    fun dismiss() {
        pending?.cancel()
        nodeId = null
    }

    private fun schedule(millis: Long, action: () -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(millis)
            action()
        }
    }

    private companion object {
        const val SHOW_DELAY = 450L
        const val HIDE_DELAY = 250L
    }
}

/** The tooltip of a node: what does not fit in its box, and the way to its source. */
@Composable
fun HoverCard(viewModel: TraceViewModel, state: GraphViewState, hover: HoverCardState, onOpenSite: (Long) -> Unit) {
    val id = hover.nodeId ?: return
    val node = viewModel.snapshot.node(id) ?: return
    val anchor = state.rect(id)?.let(state.viewport::toScreen) ?: return
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            when (it) {
                is HoverInteraction.Enter -> hover.keep()
                is HoverInteraction.Exit -> hover.leave()
            }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            // Below the box, or above it where there is no room below; never outside the pane.
            .layout { measurable, constraints ->
                val card = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val gap = 6.dp.roundToPx()
                    val below = (anchor.bottom * density).roundToInt() + gap
                    val above = (anchor.top * density).roundToInt() - gap - card.height
                    val y = if (below + card.height <= constraints.maxHeight || above < 0) below else above
                    val x = (anchor.left * density).roundToInt().coerceIn(0, (constraints.maxWidth - card.width).coerceAtLeast(0))
                    card.place(x, y.coerceIn(0, (constraints.maxHeight - card.height).coerceAtLeast(0)))
                }
            }
            .widthIn(max = 420.dp)
            .clip(shape).background(palette.surface).border(1.dp, palette.border, shape)
            .hoverable(interaction)
            .testTag("node-card")
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val snapshot = viewModel.snapshot
        Label(node.graphTitle, style = Type.code.copy(fontWeight = FontWeight.Medium))
        Label("${Formatting.enumLabel(node.info.kind)} · ${node.state.label}", style = Type.small, color = palette.textDim)
        snapshot.frame(node.info.siteFrame)?.let { site ->
            val openable = snapshot.sources.isKnown(site)
            Label(
                (if (openable) "↗ " else "") + site.shortLocation.ifEmpty { site.className },
                (if (openable) Modifier.handCursor().clickable { onOpenSite(id) } else Modifier).testTag("card-site"),
                Type.codeSmall, if (openable) palette.accent else palette.textDim,
            )
        }
        // The name is in the title already; the dispatcher is what people look for first.
        val diff = node.contextDiff.filter { it.kind != ContextElementKind.NAME }.sortedBy { it.kind != ContextElementKind.DISPATCHER }
        for (change in diff.take(4)) Label(change.describe(), style = Type.codeSmall, color = palette.contextChange)
        if (node.runsOn != 0L && node.info.kind != NodeKind.THREAD) {
            Label("on " + (snapshot.node(node.runsOn)?.graphTitle ?: "thread #${node.runsOn}"), style = Type.codeSmall, color = palette.textDim)
        }
        viewModel.ownPace(id)?.let { pace ->
            Label("subtree " + settingText(pace), Modifier.testTag("card-pace"), Type.small.copy(fontWeight = FontWeight.Medium), if (pace.paused) palette.suspended else palette.accent)
            if (node.info.kind != NodeKind.THREAD) Label(THREADS_NOT_COROUTINES, style = Type.small, color = palette.textDim, maxLines = 3)
        }
    }
}

@Composable
fun ToolButton(text: String, tag: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val color = when {
        !enabled -> palette.textDim.copy(alpha = 0.5f)
        active -> palette.accent
        else -> palette.text
    }
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .then(if (enabled) Modifier.rowBackground(selected = false).handCursor().clickable(onClick = onClick) else Modifier)
            .testTag(tag)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Label(text, style = Type.small.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

@Composable
fun ToggleChip(text: String, tag: String, on: Boolean, leading: @Composable () -> Unit = {}, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (on) palette.accent.copy(alpha = 0.13f) else palette.header)
            .handCursor()
            .toggleable(value = on, onValueChange = onChange)
            .testTag(tag)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading()
        Label(text, style = Type.small, color = if (on) palette.text else palette.textDim.copy(alpha = 0.7f))
    }
}

/** One line of the legend that cannot be switched off: the structural edge. */
@Composable
fun LegendEntry(text: String, kind: EdgeKind?) {
    Row(Modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LineSample(kind, on = true)
        Label(text, style = Type.small, color = palette.textDim)
    }
}

/** A cross-link type in the legend: what its lines look like, and the switch that takes them out of the graph. */
@Composable
fun LegendToggle(kind: EdgeKind, on: Boolean, onToggle: () -> Unit) {
    ToggleChip(kind.label, "links-${kind.name}", on, leading = { LineSample(kind, on) }) { onToggle() }
}

@Composable
private fun LineSample(kind: EdgeKind?, on: Boolean) {
    val color = (kind?.let { palette.of(it) } ?: palette.edge).copy(alpha = if (on) 1f else 0.4f)
    Canvas(Modifier.size(width = 30.dp, height = 10.dp)) {
        val y = size.height / 2
        val unit = 1.dp.toPx()
        val arrow = if (kind == null) 0f else 6 * unit
        drawLine(
            color, Offset(0f, y), Offset(size.width - arrow, y), 1.5f * unit,
            pathEffect = kind?.let { PathEffect.dashPathEffect(it.dashes().map { dash -> dash * unit }.toFloatArray()) },
        )
        if (kind != null) {
            drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width, y)
                    lineTo(size.width - arrow, y - 3 * unit)
                    lineTo(size.width - arrow, y + 3 * unit)
                    close()
                },
                color,
            )
        }
    }
}
