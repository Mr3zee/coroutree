package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.StackFrameDef
import kotlinx.coroutree.model.TraceHeader
import org.jetbrains.compose.splitpane.ExperimentalSplitPaneApi
import org.jetbrains.compose.splitpane.HorizontalSplitPane
import org.jetbrains.compose.splitpane.SplitPaneScope
import org.jetbrains.compose.splitpane.VerticalSplitPane
import org.jetbrains.compose.splitpane.rememberSplitPaneState
import java.awt.Cursor

/** One open trace: top bar, diagnostics, and the three panes. Stateless apart from split positions and dismissals. */
@OptIn(ExperimentalSplitPaneApi::class)
@Composable
fun TraceScreen(
    viewModel: TraceViewModel,
    sourceTitle: String,
    status: FeedStatus,
    failure: String?,
    onOpenFrame: (StackFrameDef) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(palette.background)) {
        TopBar(viewModel.snapshot.header, sourceTitle, status, onClose)
        Divider()
        if (failure != null) Banner("Could not read the trace: $failure", palette.failed, onDismiss = null)
        DiagnosticBanners(viewModel.snapshot.diagnostics)

        VerticalSplitPane(splitPaneState = rememberSplitPaneState(0.64f)) {
            first(minSize = 120.dp) {
                HorizontalSplitPane(splitPaneState = rememberSplitPaneState(0.68f)) {
                    first(minSize = 240.dp) { GraphPane(viewModel, onOpenFrame) }
                    second(minSize = 220.dp) { DetailsPane(viewModel, onOpenFrame) }
                    paneSplitter(vertical = false)
                }
            }
            second(minSize = 80.dp) { EventLogPane(viewModel, live = status == FeedStatus.LIVE) }
            paneSplitter(vertical = true)
        }
    }
}

@OptIn(ExperimentalSplitPaneApi::class)
private fun SplitPaneScope.paneSplitter(vertical: Boolean) {
    splitter {
        visiblePart {
            Box((if (vertical) Modifier.fillMaxWidth().height(1.dp) else Modifier.fillMaxHeight().width(1.dp)).background(palette.border))
        }
        handle {
            val cursor = PointerIcon(Cursor(if (vertical) Cursor.N_RESIZE_CURSOR else Cursor.E_RESIZE_CURSOR))
            Box(
                (if (vertical) Modifier.fillMaxWidth().height(7.dp) else Modifier.fillMaxHeight().width(7.dp))
                    .markAsHandle().pointerHoverIcon(cursor)
            )
        }
    }
}

@Composable
private fun TopBar(header: TraceHeader?, sourceTitle: String, status: FeedStatus, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(palette.header).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("coroutree", style = Type.title, color = palette.accent)
        StatusPill(status)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val task = header?.taskPath?.ifEmpty { null }
            Label(task ?: sourceTitle, style = Type.code.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f, fill = false))
            if (header != null) {
                val command = header.jvm.command
                if (command.isNotEmpty()) Label(command, Modifier.weight(2f, fill = false), Type.code, palette.textDim)
                if (header.jvm.pid != 0L) Chip("pid ${header.jvm.pid}")
                if (header.jvm.javaVersion.isNotEmpty()) Chip("java ${header.jvm.javaVersion}")
            }
        }
        TextButton("Open…", onClose, Modifier.testTag("open-another"))
    }
}

@Composable
private fun StatusPill(status: FeedStatus) {
    val color = when (status) {
        FeedStatus.LIVE -> palette.active
        FeedStatus.CONNECTING, FeedStatus.LOADING -> palette.suspended
        FeedStatus.RECORDED, FeedStatus.ENDED -> palette.finished
        FeedStatus.FAILED -> palette.failed
    }
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.16f)).height(20.dp).padding(start = 7.dp, end = 9.dp).testTag("status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Label(status.label.uppercase(), style = Type.label, color = color)
    }
}

@Composable
private fun DiagnosticBanners(diagnostics: List<Diagnostic>) {
    // Dismissal is per message text: the same warning repeated by a chatty agent stays dismissed.
    var dismissed by remember { mutableStateOf(emptySet<String>()) }
    for (diagnostic in diagnostics.distinctBy { it.message }.filter { it.message !in dismissed }.take(3)) {
        val color = when (diagnostic.severity) {
            Diagnostic.Severity.ERROR -> palette.failed
            Diagnostic.Severity.WARNING -> palette.warningText
            else -> palette.textDim
        }
        Banner(diagnostic.message, color, onDismiss = { dismissed = dismissed + diagnostic.message })
    }
}

@Composable
private fun Banner(text: String, color: Color, onDismiss: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().background(if (color == palette.warningText) palette.warningSurface else color.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp).testTag("banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("!", style = Type.body.copy(fontWeight = FontWeight.Bold), color = color)
        Label(text, Modifier.weight(1f), color = color, maxLines = 3)
        if (onDismiss != null) {
            Label("Dismiss", Modifier.clip(RoundedCornerShape(4.dp)).handCursor().clickable(onClick = onDismiss).padding(horizontal = 6.dp, vertical = 2.dp).testTag("dismiss"), Type.small.copy(fontWeight = FontWeight.Medium), color)
        }
    }
    Divider()
}

@Composable
fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val foreground = if (primary) (if (palette.dark) Color(0xFF10131A) else Color.White) else palette.accent
    Box(
        modifier.clip(RoundedCornerShape(6.dp))
            .then(if (primary) Modifier.background(palette.accent) else Modifier.rowBackground(selected = false))
            .handCursor().clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Label(text, style = Type.body.copy(fontWeight = FontWeight.Medium), color = foreground)
    }
}
