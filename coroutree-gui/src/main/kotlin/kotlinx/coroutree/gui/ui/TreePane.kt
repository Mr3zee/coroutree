package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutree.gui.view.LinkMark
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.gui.view.TreeRow
import kotlinx.coroutree.model.ContextElementKind
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.describe
import kotlinx.coroutree.model.tree.shortLocation
import kotlinx.coroutree.model.tree.title

@Composable
fun TreePane(viewModel: TraceViewModel, modifier: Modifier = Modifier) {
    val rows = viewModel.rows
    val listState = rememberLazyListState()

    // Rows are keyed by node id, so the list keeps its scroll anchor while a live trace inserts rows above it.
    LaunchedEffect(viewModel.treeReveal) {
        val reveal = viewModel.treeReveal ?: return@LaunchedEffect
        listState.revealItem(viewModel.rowIndex(reveal.target))
    }

    Column(modifier.background(palette.surface)) {
        PaneHeader("Concurrency tree") {
            Label("${viewModel.snapshot.nodes.size} nodes", style = Type.small, color = palette.textDim)
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("tree"), state = listState) {
                items(rows, key = { it.node.id }) { row ->
                    TreeRowView(
                        row = row,
                        snapshot = viewModel.snapshot,
                        selected = row.node.id == viewModel.selectedNodeId,
                        marks = viewModel.linkMarks[row.node.id].orEmpty(),
                        onSelect = { viewModel.selectNode(row.node.id) },
                        onToggle = { viewModel.toggle(row) },
                    )
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** Scrolls only when the item is not already fully in view, so clicking around does not make the list jump. */
suspend fun LazyListState.revealItem(index: Int) {
    if (index < 0) return
    val visible = layoutInfo.visibleItemsInfo
    val fullyVisible = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= layoutInfo.viewportEndOffset }
    if (!fullyVisible) scrollToItem((index - 3).coerceAtLeast(0))
}

@Composable
private fun TreeRowView(
    row: TreeRow,
    snapshot: TraceSnapshot,
    selected: Boolean,
    marks: List<LinkMark>,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val node = row.node
    val contentAlpha = if (row.dimmed) 0.6f else 1f
    Row(
        Modifier.fillMaxWidth().height(26.dp)
            .rowBackground(selected, related = marks.isNotEmpty())
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSelect)
            .testTag("node-${node.id}")
            .padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width((6 + row.depth * 16).dp))
        Box(
            Modifier.size(18.dp).then(if (row.hasChildren) Modifier.clickable(onClick = onToggle).testTag("toggle-${node.id}") else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (row.hasChildren) {
                BasicText(if (row.expanded) "▾" else "▸", style = TextStyle(color = palette.textDim, fontSize = 12.sp))
            }
        }
        KindGlyph(node.info.kind, dimmed = row.dimmed)
        Spacer(Modifier.width(7.dp))

        Row(Modifier.weight(1f).alpha(contentAlpha), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(
                if (node.placeholder) "node #${node.id}" else node.title,
                style = Type.code.copy(fontWeight = if (row.dimmed) FontWeight.Normal else FontWeight.Medium),
                modifier = Modifier.weight(1f, fill = false),
            )
            snapshot.frame(node.info.siteFrame)?.shortLocation?.takeIf { it.isNotEmpty() }?.let {
                Label(it, style = Type.codeSmall, color = palette.textDim)
            }
            if (!row.expanded && row.hasChildren) Label("(${node.children.size})", style = Type.codeSmall, color = palette.textDim)
            // The name is already in the title; the dispatcher is what people look for first.
            val diff = node.contextDiff.filter { it.kind != ContextElementKind.NAME }.sortedBy { it.kind != ContextElementKind.DISPATCHER }
            if (diff.isNotEmpty()) {
                // The row has room for where things went, not for where they came from; the details pane has both.
                val compact = diff.joinToString("  ") { if (it.added || it.removed) it.describe() else "${it.key.substringAfterLast('.')} → ${it.newValue}" }
                Label(compact, style = Type.codeSmall, color = palette.contextChange, modifier = Modifier.weight(1f, fill = false))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mark in marks) Chip(mark.label, color = palette.accent, style = Type.small)
            if (node.runsOn != 0L && node.info.kind != NodeKind.THREAD) {
                val thread = snapshot.node(node.runsOn)?.info?.name?.ifEmpty { null } ?: "thread #${node.runsOn}"
                Label("on $thread", style = Type.codeSmall, color = palette.textDim)
            }
            StateBadge(node.state, Modifier.alpha(contentAlpha))
        }
    }
}
