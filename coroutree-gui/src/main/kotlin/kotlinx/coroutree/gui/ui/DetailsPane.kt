package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutree.gui.view.Formatting
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.ContextElementKind
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.HandledBy
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.PropagationDirection
import kotlinx.coroutree.model.StackFrameDef
import kotlinx.coroutree.model.tree.CrossLink
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.describe
import kotlinx.coroutree.model.tree.qualified
import kotlinx.coroutree.model.tree.title

private val LABEL_WIDTH = 132.dp

@Composable
fun DetailsPane(viewModel: TraceViewModel, onOpenFrame: (StackFrameDef) -> Unit, modifier: Modifier = Modifier) {
    val snapshot = viewModel.snapshot
    val node = viewModel.selectedNode
    val event = viewModel.selectedEvent
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.selectedNodeId, viewModel.selectedEventSeq) { listState.scrollToItem(0) }

    Column(modifier.background(palette.surface)) {
        PaneHeader(if (event != null) "Event" else "Node")
        if (node == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label("Select a node or an event", color = palette.textDim)
            }
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("details").padding(bottom = 8.dp), state = listState) {
                if (event != null) eventDetails(snapshot, event, viewModel::navigateTo, onOpenFrame)
                nodeDetails(snapshot, node, viewModel, onOpenFrame)
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

private fun LazyListScope.eventDetails(snapshot: TraceSnapshot, event: Event, onNavigate: (Long) -> Unit, onOpenFrame: (StackFrameDef) -> Unit) {
    item {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(Formatting.enumLabel(event.kind), color = palette.of(event.kind), style = Type.small.copy(fontWeight = FontWeight.Medium))
                Label("#${event.seq}", style = Type.code, color = palette.textDim)
                Label("${Formatting.relativeTime(event.timeNanos)} ms", style = Type.code, color = palette.textDim)
            }
            Label(snapshot.describe(event), style = Type.title, maxLines = 3)
        }
    }
    item {
        Properties {
            NodeProperty("Node", snapshot, event.nodeId, onNavigate)
            if (event.otherNodeId != 0L) NodeProperty("Other node", snapshot, event.otherNodeId, onNavigate)
            if (event.threadId != 0L) NodeProperty("Captured on", snapshot, event.threadId, onNavigate)
            if (event.blockReason != BlockReason.UNSPECIFIED) Property("Reason", Formatting.enumLabel(event.blockReason))
            if (event.handledBy != HandledBy.UNSPECIFIED) Property("Handled by", Formatting.enumLabel(event.handledBy))
            if (event.direction != PropagationDirection.UNSPECIFIED) Property("Direction", Formatting.enumLabel(event.direction))
            if (event.finalState != NodeState.UNSPECIFIED) Property("Final state", Formatting.enumLabel(event.finalState))
            for (change in event.contextDiff) Property("Context", change.describe(), code = true)
        }
    }
    event.exception?.let { exception ->
        item { SectionTitle("Exception") }
        item {
            Properties {
                Property("Class", exception.className, code = true)
                if (exception.message.isNotEmpty()) Property("Message", exception.message, maxLines = 4)
                if (exception.cancellation) Property("Cancellation", "yes — a normal way to stop, not a failure")
                if (exception.identity != 0) Property("Instance", "@" + Integer.toHexString(exception.identity), code = true)
            }
        }
        if (exception.stack.isNotEmpty()) {
            item { SubsectionTitle("Thrown at") }
            stack(snapshot, exception.stack, "thrown", onOpenFrame)
        }
    }
    if (event.stack.isNotEmpty()) {
        item { SectionTitle(if (event.kind == EventKind.SUSPENDED) "Coroutine stack" else "Captured stack") }
        stack(snapshot, event.stack, "stack", onOpenFrame)
    }
    item {
        Spacer(Modifier.height(10.dp))
        Divider()
    }
}

private fun LazyListScope.nodeDetails(snapshot: TraceSnapshot, node: NodeSnapshot, viewModel: TraceViewModel, onOpenFrame: (StackFrameDef) -> Unit) {
    val info = node.info
    item {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KindGlyph(info.kind)
            Label(if (node.placeholder) "node #${node.id}" else node.title, Modifier.weight(1f, fill = false), Type.title.copy(fontFamily = Type.code.fontFamily))
            StateBadge(node.state)
        }
    }
    snapshot.frame(info.siteFrame)?.let { site ->
        item { FrameLine(snapshot, site, onOpenFrame, Modifier.testTag("site")) }
    }
    item {
        Properties {
            Property("Kind", Formatting.enumLabel(info.kind))
            if (info.construct.isNotEmpty()) Property("Construct", info.construct, code = true)
            if (info.implClass.isNotEmpty()) Property("Class", info.implClass, code = true)
            if (info.origin != Origin.UNSPECIFIED) Property("Origin", Formatting.enumLabel(info.origin))
            if (info.parentId != 0L) NodeProperty("Parent", snapshot, info.parentId, viewModel::navigateTo) else Property("Parent", "none — a root")
            if (info.creatorId != 0L && info.creatorId != info.parentId) NodeProperty("Created by", snapshot, info.creatorId, viewModel::navigateTo)
            if (node.runsOn != 0L) NodeProperty("Runs on", snapshot, node.runsOn, viewModel::navigateTo)
            info.thread?.let { thread ->
                Property("Thread", listOfNotNull("tid ${thread.tid}", "virtual".takeIf { thread.virtual }, "daemon".takeIf { thread.daemon }).joinToString(", "))
            }
            if (node.placeholder) Property("Note", "never defined in the trace; known only by reference")
        }
    }
    if (info.context.isNotEmpty()) {
        item { SectionTitle("Context") }
        item {
            Properties {
                for (element in info.context) {
                    val value = element.value.ifEmpty { "this node" } + if (element.threadContextElement) "  (ThreadContextElement)" else ""
                    val name = when (element.kind) {
                        ContextElementKind.NAME -> "Name"
                        ContextElementKind.EXCEPTION_HANDLER -> "Exception handler"
                        else -> element.key.substringAfterLast('.')
                    }
                    Property(name, value, code = true)
                }
            }
        }
    }
    if (node.links.isNotEmpty()) {
        item { SectionTitle("Links") }
        items(node.links) { link -> LinkLine(snapshot, node.id, link, viewModel::navigateTo) }
    }
    item { SectionTitle("Events (${node.events.size})") }
    items(node.events.size, key = { "e" + node.events[it].seq }) { index ->
        val event = node.events[index]
        Row(
            Modifier.fillMaxWidth().height(22.dp)
                .rowBackground(selected = event.seq == viewModel.selectedEventSeq)
                .clickable { viewModel.selectEvent(event) }
                .padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(Formatting.relativeTime(event.timeNanos), Modifier.width(84.dp).padding(end = 12.dp), Type.codeSmall.copy(textAlign = TextAlign.End), palette.textDim)
            Box(Modifier.width(3.dp).height(12.dp).background(palette.of(event.kind)))
            Spacer(Modifier.width(8.dp))
            Label(snapshot.describe(event, viewpoint = node.id), Modifier.weight(1f))
        }
    }
}

private fun LazyListScope.stack(snapshot: TraceSnapshot, frameIds: List<Int>, keyPrefix: String, onOpenFrame: (StackFrameDef) -> Unit) {
    items(frameIds.size, key = { "$keyPrefix-$it" }) { index ->
        val frame = snapshot.frame(frameIds[index])
        if (frame != null) FrameLine(snapshot, frame, onOpenFrame) else Label("frame #${frameIds[index]}", Modifier.padding(horizontal = 12.dp), Type.code, palette.textDim)
    }
}

/** A stack frame. Frames that map to a project source file stand out and open in the IDE on click. */
@Composable
private fun FrameLine(snapshot: TraceSnapshot, frame: StackFrameDef, onOpenFrame: (StackFrameDef) -> Unit, modifier: Modifier = Modifier) {
    val openable = snapshot.sources.isKnown(frame)
    Row(
        modifier.fillMaxWidth().height(20.dp)
            .then(if (openable) Modifier.rowBackground(selected = false).handCursor().clickable { onOpenFrame(frame) } else Modifier)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(if (openable) "↗" else " ", Modifier.width(16.dp), Type.codeSmall, palette.accent)
        Label(frame.qualified, Modifier.weight(1f), Type.codeSmall, if (openable) palette.text else palette.textDim)
    }
}

@Composable
private fun LinkLine(snapshot: TraceSnapshot, self: Long, link: CrossLink, onNavigate: (Long) -> Unit) {
    val outgoing = link.from == self
    val otherId = if (outgoing) link.to else link.from
    val verb = when (link.kind) {
        CrossLink.Kind.LAUNCHED_FROM -> if (outgoing) "launched" else "launched by"
        CrossLink.Kind.CANCELS -> if (outgoing) "cancelled" else "cancelled by"
        CrossLink.Kind.INTERRUPTS -> if (outgoing) "interrupted" else "interrupted by"
    }
    Row(
        Modifier.fillMaxWidth().height(22.dp).rowBackground(selected = false).handCursor().clickable { onNavigate(otherId) }.padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(verb, Modifier.width(LABEL_WIDTH - 8.dp), color = palette.textDim)
        Label(snapshot.node(otherId)?.title ?: "node #$otherId", Modifier.weight(1f, fill = false), Type.code, palette.accent)
        Label("#${link.seq}", style = Type.codeSmall, color = palette.textDim)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Label(text.uppercase(), Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp), Type.label, palette.textDim)
}

@Composable
private fun SubsectionTitle(text: String) {
    Label(text, Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp), Type.small, palette.textDim)
}

@Composable
private fun Properties(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
}

@Composable
private fun Property(name: String, value: String, code: Boolean = false, maxLines: Int = 2, onClick: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Label(name, Modifier.width(LABEL_WIDTH).padding(end = 8.dp), color = palette.textDim)
        Label(
            value,
            Modifier.weight(1f).then(if (onClick != null) Modifier.handCursor().clickable(onClick = onClick) else Modifier),
            style = if (code) Type.code else Type.body,
            color = if (onClick != null) palette.accent else palette.text,
            maxLines = maxLines,
        )
    }
}

@Composable
private fun NodeProperty(name: String, snapshot: TraceSnapshot, id: Long, onNavigate: (Long) -> Unit) {
    Property(name, snapshot.node(id)?.title ?: "node #$id", code = true, onClick = { onNavigate(id) })
}
