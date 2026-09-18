package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutree.gui.view.EventLogItems
import kotlinx.coroutree.gui.view.Formatting
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.describe
import kotlinx.coroutree.model.tree.title

private val SEQ_WIDTH = 64.dp
private val TIME_WIDTH = 96.dp
private val NODE_WIDTH = 260.dp

@Composable
fun EventLogPane(viewModel: TraceViewModel, live: Boolean, modifier: Modifier = Modifier) {
    val events = viewModel.snapshot.events
    // The events, and between them the changes of execution control: a recorded trace shows where the program was
    // paused, stepped and slowed down, which is where the gaps in its timestamps come from.
    val log = viewModel.eventLog
    val listState = rememberLazyListState()

    // Follow the tail of a live trace until the user scrolls away from it; scrolling back to the end resumes.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (!scrolling) follow = !listState.canScrollForward }
    }
    LaunchedEffect(log.size, live) {
        if (live && follow && log.size > 0) listState.scrollToItem(log.size - 1)
    }
    LaunchedEffect(viewModel.logReveal) {
        val reveal = viewModel.logReveal ?: return@LaunchedEffect
        listState.revealItem(viewModel.logIndex(reveal.target))
        follow = !listState.canScrollForward
    }

    Column(modifier.background(palette.surface)) {
        PaneHeader("Event log") {
            if (live) Label(if (follow) "following" else "paused — scroll to the end to follow", style = Type.small, color = palette.textDim)
            Spacer(Modifier.width(12.dp))
            Label("${events.size} events", style = Type.small, color = palette.textDim)
        }
        Row(Modifier.fillMaxWidth().height(22.dp).background(palette.header).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("#", Modifier.width(SEQ_WIDTH).padding(end = 12.dp), Type.small.copy(textAlign = TextAlign.End), palette.textDim)
            Label("ms", Modifier.width(TIME_WIDTH).padding(end = 16.dp), Type.small.copy(textAlign = TextAlign.End), palette.textDim)
            Label("node", Modifier.width(NODE_WIDTH), Type.small, palette.textDim)
            Label("event", Modifier.weight(1f), Type.small, palette.textDim)
        }
        Divider()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("log"), state = listState) {
                items(count = log.size, key = log::key) { index ->
                    when (val item = log[index]) {
                        is EventLogItems.Item.Of -> EventRow(
                            event = item.event,
                            snapshot = viewModel.snapshot,
                            selected = item.event.seq == viewModel.selectedEventSeq,
                            related = viewModel.isHighlighted(item.event),
                            onSelect = { viewModel.selectEvent(item.event) },
                        )
                        is EventLogItems.Item.Pace -> PaceRow(item.change, item.index, viewModel.snapshot) {
                            if (item.change.scopeNodeId != 0L) viewModel.navigateTo(item.change.scopeNodeId)
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** A change of execution control: not an event (the program did nothing), hence no number, and set apart. */
@Composable
private fun PaceRow(change: PaceDef, index: Int, snapshot: TraceSnapshot, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(22.dp)
            .background(palette.suspended.copy(alpha = 0.08f))
            .then(if (change.scopeNodeId != 0L) Modifier.handCursor().clickable(onClick = onSelect) else Modifier)
            .testTag("pace-change-$index")
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("‖", Modifier.width(SEQ_WIDTH).padding(end = 12.dp), Type.codeSmall.copy(textAlign = TextAlign.End), palette.suspended)
        Label(Formatting.relativeTime(change.timeNanos), Modifier.width(TIME_WIDTH).padding(end = 16.dp), Type.codeSmall.copy(textAlign = TextAlign.End), palette.textDim)
        Label(snapshot.describe(change), Modifier.weight(1f), Type.body.copy(fontWeight = FontWeight.Medium), palette.suspended)
    }
}

@Composable
private fun EventRow(event: Event, snapshot: TraceSnapshot, selected: Boolean, related: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(22.dp)
            .rowBackground(selected, related)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSelect)
            .testTag("event-${event.seq}")
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(event.seq.toString(), Modifier.width(SEQ_WIDTH).padding(end = 12.dp), Type.codeSmall.copy(textAlign = TextAlign.End), palette.textDim)
        Label(Formatting.relativeTime(event.timeNanos), Modifier.width(TIME_WIDTH).padding(end = 16.dp), Type.codeSmall.copy(textAlign = TextAlign.End), palette.textDim)
        Label(snapshot.node(event.nodeId)?.title ?: "node #${event.nodeId}", Modifier.width(NODE_WIDTH).padding(end = 12.dp), Type.code)
        Box(Modifier.width(3.dp).height(12.dp).clip(RoundedCornerShape(2.dp)).background(palette.of(event.kind)))
        Spacer(Modifier.width(8.dp))
        Label(snapshot.describe(event), Modifier.weight(1f), Type.body)
    }
}
