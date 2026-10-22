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

/**
 * [catchUpTo] is the snapshot on screen when a reconnect replaced the feed. The new feed replays from the first byte,
 * so its snapshots are shown only once they have caught up: the graph never shrinks on the way.
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
     * Replays the trace from the start with the current selection and viewport.
     * Node ids remain valid for the JVM's lifetime.
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

    fun openInIde(frame: StackFrameDef) {
        val location = current?.viewModel?.snapshot?.sources?.resolve(frame)
        if (location == null) {
            show("${frame.className} is not part of the project sources")
            return
        }
        scope.launch {
            when (val outcome = ideOpener.open(location)) {
                is IdeOpener.Outcome.Opened -> show("Opened ${location.path}:${location.line} in the IDE")
                is IdeOpener.Outcome.Failed -> show(outcome.message)
            }
        }
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
