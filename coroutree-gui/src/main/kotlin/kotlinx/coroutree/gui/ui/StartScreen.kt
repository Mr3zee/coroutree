package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutree.gui.source.SessionEntry
import kotlinx.coroutree.gui.source.SessionRegistry
import kotlinx.coroutree.gui.source.SessionState
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.gui.view.Formatting
import java.io.File

/**
 * What the registry offers, re-read every couple of seconds for as long as the caller stays in the composition: a JVM
 * that starts under the agent appears by itself, one that ends moves down. Pruned once, on the way in.
 */
@Composable
fun rememberSessions(registry: SessionRegistry): List<SessionEntry> {
    val sessions by produceState(emptyList(), registry) {
        withContext(Dispatchers.IO) { runCatching { registry.prune() } }
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { registry.entries() }.getOrDefault(emptyList()) }
            delay(2000)
        }
    }
    return sessions
}

@Composable
fun StartScreen(
    registryDir: File,
    sessions: List<SessionEntry>,
    onOpen: (TraceSource) -> Unit,
    onChooseTraceFile: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label("coroutree", style = Type.title.copy(fontSize = Type.title.fontSize * 1.6f), color = palette.accent)
            Label("See the concurrency tree of a Kotlin/JVM program: what ran where, in what order, and who cancelled whom.", color = palette.textDim, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("Open trace file…", onChooseTraceFile, primary = true)
                TextButton("Show a demo", { onOpen(TraceSource.Demo(live = false)) }, Modifier.testTag("demo"))
            }
            Label("JVMs started with the coroutree agent, from any build. A program that starts appears here by itself.", style = Type.small, color = palette.textDim, maxLines = 2)
            LazyColumn(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, palette.border, RoundedCornerShape(8.dp)).background(palette.surface).testTag("sessions")) {
                val (running, over) = sessions.partition { it.running }
                item { ListHeader("Running now", running.size) }
                items(running, key = { "s" + it.info.file?.path + it.info.pid }) { entry -> SessionRow(entry, onOpen) }
                val recent = over.filter { it.openable }
                item { ListHeader("Recent runs", recent.size) }
                items(recent, key = { "r" + it.info.file?.path + it.info.pid }) { entry -> SessionRow(entry, onOpen) }
            }
            Label(registryDir.path, style = Type.codeSmall, color = palette.textDim)
        }
    }
}

@Composable
private fun SessionRow(entry: SessionEntry, onOpen: (TraceSource) -> Unit) {
    val info = entry.info
    val detail = listOf("pid ${info.pid}", if (entry.running) "started ${Formatting.wallClock(info.startedAt)}" else Formatting.wallClock(info.startedAt)).filter { it.isNotEmpty() }.joinToString(" · ")
    val chip: Pair<String, Color>? = when {
        entry.running && info.live -> "live" to palette.active
        entry.running && info.liveFailed -> "socket failed" to palette.failed
        entry.running -> "no socket" to palette.textDim
        entry.state == SessionState.CRASHED -> "crashed" to palette.failed
        entry.state == SessionState.UNREACHABLE -> "unreachable" to palette.textDim
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().height(34.dp).rowBackground(selected = false).handCursor().clickable { onOpen(TraceSource.Session(info)) }.padding(horizontal = 12.dp).testTag("session-${info.pid}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chip != null) {
            Chip(chip.first, color = chip.second, style = Type.small)
            Spacer(Modifier.width(8.dp))
        }
        Label(info.title, Modifier.weight(1f), Type.code)
        Label(detail, style = Type.small, color = palette.textDim)
    }
}

@Composable
private fun ListHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().height(28.dp).background(palette.header).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(title.uppercase(), style = Type.label, color = palette.textDim, modifier = Modifier.weight(1f))
        Label(if (count == 0) "none" else count.toString(), style = Type.small, color = palette.textDim)
    }
}
