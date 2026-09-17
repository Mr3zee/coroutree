package kotlinx.coroutree.gui.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutree.gui.view.GraphViewState
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.NodeBox
import kotlinx.coroutree.model.StackFrameDef
import kotlin.math.exp

private const val GLIDE_MILLIS = 320

/** Below this zoom a box is a coloured rectangle: its text would be a smudge, and thousands of them are in view. */
private const val DETAIL_ZOOM = 0.35f
private const val MAX_DETAILED_BOXES = 700

/** Boxes this far outside the pane are composed already, so that panning does not reveal holes. */
private const val CULL_MARGIN = 160

private const val SCROLL_STEP = 48f

/**
 * The concurrency tree as a graph (DESIGN §6.1): toolbar and legend on top, below them the canvas with the edges, the
 * boxes over it, the minimap in a corner. Where things are is the business of `view/`: the layout engine says where
 * every box and line goes, [GraphViewState] how that maps to the pane and how it changes; this only draws and listens.
 */
@Composable
fun GraphPane(viewModel: TraceViewModel, onOpenFrame: (StackFrameDef) -> Unit, modifier: Modifier = Modifier) {
    val state = viewModel.graphView
    val layout = viewModel.layout
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val hover = remember(scope) { HoverCardState(scope) }

    // Order matters: a reveal that arrives together with a new layout pans over the new layout.
    LaunchedEffect(layout) {
        hover.dismiss()
        if (state.show(layout, viewModel.selectedNodeId)) {
            animate(0f, 1f, animationSpec = tween(GLIDE_MILLIS, easing = FastOutSlowInEasing)) { value, _ -> state.advance(value) }
        }
    }
    LaunchedEffect(viewModel.graphReveal) { viewModel.graphReveal?.let { state.reveal(it.target) } }

    // The boxes that get a composable of their own, or null when they are too small or too many for that.
    val detailed: List<NodeBox>? by remember(state) {
        derivedStateOf {
            if (state.viewport.zoom < DETAIL_ZOOM) null
            else state.layout.boxesIn(state.viewport.visibleWorld(state.width, state.height).inflated(CULL_MARGIN)).takeIf { it.size <= MAX_DETAILED_BOXES }
        }
    }

    fun openSite(nodeId: Long) {
        viewModel.snapshot.node(nodeId)?.info?.siteFrame?.let(viewModel.snapshot::frame)?.let(onOpenFrame)
    }

    var pointer by remember { mutableStateOf(Offset.Zero) }

    Column(modifier.background(palette.surface)) {
        PaneHeader("Concurrency tree") { GraphToolbar(viewModel, state) }
        GraphLegend(viewModel)
        Divider()
        ContextMenuArea(items = {
            val id = state.nodeAt(pointer.x / density, pointer.y / density) ?: return@ContextMenuArea emptyList()
            val site = viewModel.snapshot.node(id)?.info?.siteFrame?.let(viewModel.snapshot::frame)
            listOfNotNull(
                site?.let { ContextMenuItem("Open in IDE") { onOpenFrame(it) } },
                ContextMenuItem("Zoom to node") {
                    viewModel.selectNode(id)
                    state.zoomTo(id)
                },
            )
        }) {
            Box(
                Modifier.fillMaxSize().clipToBounds().background(palette.canvas)
                    .onSizeChanged { state.resize(it.width / density, it.height / density) }
                    .testTag("graph")
                    .pointerInput(state) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                pointer = change.position
                                if (event.type != PointerEventType.Scroll) continue
                                hover.dismiss()
                                val delta = change.scrollDelta
                                if (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed) {
                                    state.zoom(exp(-delta.y * 0.2f), change.position.x / density, change.position.y / density)
                                } else {
                                    state.pan(-delta.x * SCROLL_STEP, -delta.y * SCROLL_STEP)
                                }
                                change.consume()
                            }
                        }
                    }
                    .pointerInput(state) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            hover.dismiss()
                            state.pan(drag.x / density, drag.y / density)
                        }
                    }
                    .pointerInput(state) {
                        // Boxes with a composable of their own take their clicks; this gets the rest: small boxes and the background.
                        detectTapGestures { position ->
                            val id = state.nodeAt(position.x / density, position.y / density)
                            if (id != null) viewModel.selectNode(id) else viewModel.clearSelection()
                        }
                    },
            ) {
                GraphEdges(viewModel, state, drawBoxes = detailed == null)
                detailed?.let { boxes -> GraphNodes(viewModel, state, boxes, hover) }
                if (state.layout.isEmpty) {
                    val hidden = viewModel.graph.hiddenNodes
                    Label(
                        if (hidden == 0) "No nodes yet" else "All $hidden nodes so far are library machinery — switch on “library / pools” to see them",
                        Modifier.align(Alignment.Center), color = palette.textDim,
                    )
                } else if (!state.autoFit && !state.wholeGraphInView) {
                    // While the pane follows the graph all of it is in view, or will be when the glide ends.
                    Minimap(viewModel, state, Modifier.align(Alignment.BottomEnd).padding(10.dp))
                }
                HoverCard(viewModel, state, hover, ::openSite)
            }
        }
    }
}

@Composable
private fun GraphToolbar(viewModel: TraceViewModel, state: GraphViewState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val graph = viewModel.graph
        val hidden = if (graph.hiddenNodes > 0 && !viewModel.options.showLibrary) " · ${graph.hiddenNodes} hidden" else ""
        Label("${graph.model.nodes.size} nodes$hidden", Modifier.padding(end = 10.dp).testTag("graph-counts"), Type.small, palette.textDim)
        ToolButton("−", "zoom-out") { state.zoom(1 / ZOOM_STEP) }
        Label("${(state.viewport.zoom * 100).toInt()}%", Modifier.padding(horizontal = 4.dp).testTag("zoom-level"), Type.small, palette.textDim)
        ToolButton("+", "zoom-in") { state.zoom(ZOOM_STEP) }
        ToolButton("Fit", "zoom-fit", active = state.autoFit) { state.fit() }
        ToolButton("Selection", "zoom-selection", enabled = viewModel.selectedNodeId?.let(state.layout::box) != null) {
            viewModel.selectedNodeId?.let(state::zoomTo)
        }
    }
}

private const val ZOOM_STEP = 1.25f

@Composable
private fun GraphLegend(viewModel: TraceViewModel) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(palette.header).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LegendEntry("parent → child", null)
        for (kind in EdgeKind.entries) {
            LegendToggle(kind, on = kind in viewModel.options.linkKinds) { viewModel.toggleLinks(kind) }
        }
        Box(Modifier.weight(1f))
        ToggleChip("labels", "toggle-labels", viewModel.options.labels) { viewModel.showLabels(it) }
        ToggleChip("library / pools", "toggle-library", viewModel.options.showLibrary) { viewModel.showLibrary(it) }
    }
}
