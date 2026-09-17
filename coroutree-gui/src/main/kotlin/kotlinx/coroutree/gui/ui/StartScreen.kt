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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutree.gui.source.CoroutreeDir
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.gui.view.Formatting
import java.io.File

/** What a build directory currently offers. */
data class DirListing(val sessions: List<SessionInfo> = emptyList(), val traces: List<File> = emptyList())

/** Re-reads [dir] every couple of seconds for as long as the caller stays in the composition. */
@Composable
fun rememberDirListing(dir: File?): DirListing {
    val listing by produceState(DirListing(), dir) {
        while (dir != null) {
            value = withContext(Dispatchers.IO) {
                runCatching { CoroutreeDir(dir).let { DirListing(it.sessions(), it.traces()) } }.getOrDefault(DirListing())
            }
            delay(2000)
        }
    }
    return listing
}

@Composable
fun StartScreen(
    dir: File?,
    listing: DirListing,
    onOpen: (TraceSource) -> Unit,
    onChooseTraceFile: () -> Unit,
    onChooseDir: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label("coroutree", style = Type.title.copy(fontSize = Type.title.fontSize * 1.6f), color = palette.accent)
            Label("See the concurrency tree of a Kotlin/JVM program: what ran where, in what order, and who cancelled whom.", color = palette.textDim, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("Open trace file…", onChooseTraceFile, primary = true)
                TextButton(if (dir == null) "Choose build directory…" else "Change build directory…", onChooseDir)
                TextButton("Show a demo", { onOpen(TraceSource.Demo(live = false)) }, Modifier.testTag("demo"))
            }
            if (dir == null) {
                Label("Point at a build's build/coroutree directory to pick from its live sessions and recorded traces.", style = Type.small, color = palette.textDim, maxLines = 2)
            } else {
                Label(dir.path, style = Type.codeSmall, color = palette.textDim)
                LazyColumn(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, palette.border, RoundedCornerShape(8.dp)).background(palette.surface)) {
                    val running = listing.sessions.filter { !it.ended }
                    item { ListHeader("Running now", running.size) }
                    items(running, key = { "s" + it.file?.path + it.pid }) { session ->
                        ListRow(session.title, "pid ${session.pid} · started ${Formatting.wallClock(session.startedAt)}", live = true) { onOpen(TraceSource.Session(session)) }
                    }
                    item { ListHeader("Recorded traces", listing.traces.size) }
                    items(listing.traces, key = { "t" + it.path }) { trace ->
                        val subtitle = listOf(trace.parentFile?.name.orEmpty(), Formatting.wallClock(trace.lastModified())).filter { it.isNotEmpty() }.joinToString(" · ")
                        ListRow(trace.name, subtitle, live = false) { onOpen(TraceSource.TraceFile(trace)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().height(28.dp).background(palette.header).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(title.uppercase(), style = Type.label, color = palette.textDim, modifier = Modifier.weight(1f))
        Label(if (count == 0) "none" else count.toString(), style = Type.small, color = palette.textDim)
    }
}

@Composable
private fun ListRow(title: String, subtitle: String, live: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).rowBackground(selected = false).handCursor().clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Chip("live", color = palette.active, style = Type.small)
            Spacer(Modifier.width(8.dp))
        }
        Label(title, Modifier.weight(1f), Type.code)
        Label(subtitle, style = Type.small, color = palette.textDim)
    }
}
