package kotlinx.coroutree.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutree.gui.ide.IdeOpener
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.source.SessionRegistry
import kotlinx.coroutree.gui.source.TraceFeed
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.StackFrameDef
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.title

/**
 * [catchUpTo] is the snapshot on screen when a reconnect replaced the feed. The new feed replays from the first byte.
 * The view shows its snapshots only after they have caught up, so the graph never shrinks on the way.
 */
class OpenTrace(val feed: TraceFeed, val viewModel: TraceViewModel = TraceViewModel(), private val catchUpTo: TraceSnapshot? = null) {
    /** Whether [snapshot], the feed's latest, replaces what the view shows. */
    fun shows(snapshot: TraceSnapshot, status: FeedStatus): Boolean =
        catchUpTo == null || snapshot.events.size >= catchUpTo.events.size || status.terminal
}

class AppState(
    private val scope: CoroutineScope,
    private val ideOpener: IdeOpener,
    val registry: SessionRegistry = SessionRegistry(),
) {
    var current: OpenTrace? by mutableStateOf(null)
        private set

    var message: String? by mutableStateOf(null)
        private set
    private var messageJob: Job? = null

    fun open(source: TraceSource) {
        close()
        current = OpenTrace(TraceFeed(source, scope))
    }

    /** Leaves the captured trace on screen and the JVM running. */
    fun disconnect() {
        val open = current ?: return
        scope.launch { open.feed.disconnect() }
    }

    /**
     * Attaches to the same JVM again, which replays the trace from the start.
     * Node ids stay valid for the JVM's lifetime, so the selection and the viewport carry over.
     */
    fun reconnect() {
        val open = current ?: return
        val source = open.feed.source as? TraceSource.Session ?: return
        scope.launch { open.feed.close() }
        current = OpenTrace(TraceFeed(source, scope), open.viewModel, catchUpTo = open.feed.snapshot.value)
    }

    fun close() {
        val closing = current ?: return
        current = null
        scope.launch { closing.feed.close() }
    }

    /** Opens [frame] in the IDE: its file and line when the source index has it, its class otherwise. */
    fun openInIde(frame: StackFrameDef) {
        val snapshot = current?.viewModel?.snapshot ?: return
        val location = snapshot.sources.resolve(frame)
        // The top-level class: a lambda or an inner class has no file of its own.
        val className = frame.className.substringBefore('$')
        val target = location?.let { IdeOpener.Target.Source(it) } ?: IdeOpener.Target.Symbol(className, snapshot.header?.projectDir ?: "")
        val what = location?.let { if (it.line > 0) "${it.path}:${it.line}" else it.path } ?: className
        scope.launch {
            when (val outcome = ideOpener.open(target)) {
                is IdeOpener.Outcome.Opened -> show("Opened $what in ${outcome.where}")
                is IdeOpener.Outcome.Failed -> show(outcome.message)
            }
        }
    }

    /** Opens the source site of a node, or reports that the trace has none for it. */
    fun openNodeInIde(nodeId: Long) {
        val snapshot = current?.viewModel?.snapshot ?: return
        val node = snapshot.node(nodeId) ?: return
        val site = snapshot.frame(node.info.siteFrame)
        if (site == null) show("${node.title} has no source location in the trace") else openInIde(site)
    }

    fun show(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(5000)
            message = null
        }
    }
}
