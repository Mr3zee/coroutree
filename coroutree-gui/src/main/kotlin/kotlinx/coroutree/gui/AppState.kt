package kotlinx.coroutree.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutree.gui.ide.IdeOpener
import kotlinx.coroutree.gui.source.CoroutreeDir
import kotlinx.coroutree.gui.source.TraceFeed
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.StackFrameDef
import java.io.File
import java.util.prefs.Preferences

/** A trace on screen: where it comes from and how it is being looked at. */
class OpenTrace(val feed: TraceFeed, val viewModel: TraceViewModel = TraceViewModel())

/** Everything that outlives a single trace: what is open, which build directory to offer, how to reach the IDE. */
class AppState(
    private val scope: CoroutineScope,
    private val ideOpener: IdeOpener,
    initialDir: File?,
    private val rememberDir: (File) -> Unit = {},
) {
    var current: OpenTrace? by mutableStateOf(null)
        private set
    var dir: File? by mutableStateOf(initialDir)
        private set

    /** Transient, self-dismissing note at the bottom of the window. */
    var message: String? by mutableStateOf(null)
        private set
    private var messageJob: Job? = null

    fun open(source: TraceSource) {
        close()
        current = OpenTrace(TraceFeed(source, scope))
    }

    fun close() {
        val closing = current ?: return
        current = null
        scope.launch { closing.feed.close() }
    }

    fun chooseDir(chosen: File) {
        dir = chosen
        rememberDir(chosen)
    }

    fun coroutreeDir(): CoroutreeDir? = dir?.takeIf { it.isDirectory }?.let(::CoroutreeDir)

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

    companion object {
        private const val LAST_DIR = "lastDir"
        private val preferences: Preferences? = runCatching { Preferences.userRoot().node("org/jetbrains/kotlinx/coroutree/gui") }.getOrNull()

        fun rememberedDir(): File? = runCatching { preferences?.get(LAST_DIR, null) }.getOrNull()?.let(::File)?.takeIf { it.isDirectory }

        fun rememberDir(dir: File) {
            runCatching { preferences?.put(LAST_DIR, dir.absolutePath) }
        }
    }
}
