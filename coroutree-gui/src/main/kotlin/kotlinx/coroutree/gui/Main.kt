package kotlinx.coroutree.gui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutree.gui.ide.IdeOpener
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.SessionRegistry
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.gui.ui.App
import kotlinx.coroutree.gui.ui.CoroutreeTheme
import kotlinx.coroutree.gui.ui.controlKeyOf
import kotlinx.coroutree.model.TraceFormat
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = try {
        CliOptions.parse(args.asList())
    } catch (e: CliOptions.UsageException) {
        System.err.println(e.message)
        System.err.println(CliOptions.USAGE)
        exitProcess(2)
    }
    val initialSource = initialSource(options)

    application {
        val scope = rememberCoroutineScope()
        val state = remember {
            AppState(scope, IdeOpener(options.ideCommand), SessionRegistry(options.sessionsDir ?: SessionRegistry.defaultDir())).also { app ->
                initialSource?.let(app::open)
            }
        }
        val title = state.current?.feed?.source?.title?.let { "coroutree — $it" } ?: "coroutree"
        Window(
            onCloseRequest = ::exitApplication,
            title = title,
            state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
            // Wherever the focus is: Space pauses and resumes the program, → steps it (when there is one to control).
            onPreviewKeyEvent = { event -> controlKeyOf(event)?.let { key -> state.current?.viewModel?.handleKey(key) } ?: false },
        ) {
            CoroutreeTheme {
                App(state, onChooseTraceFile = { chooseTraceFile(window)?.let { state.open(TraceSource.TraceFile(it)) } })
            }
        }
    }
}

/** What to show right away, or `null` for the session list. A bad argument is reported and ends up there too. */
private fun initialSource(options: CliOptions): TraceSource? = try {
    when {
        options.demo != null -> TraceSource.Demo(live = options.demo == CliOptions.DemoMode.LIVE)
        options.session != null -> TraceSource.Session(SessionInfo.read(options.session))
        options.trace != null -> TraceSource.TraceFile(options.trace.also { require(it.isFile) { "No such trace file: $it" } })
        else -> null
    }
} catch (e: Exception) {
    System.err.println("coroutree: ${e.message}")
    null
}

private fun chooseTraceFile(parent: Frame): File? {
    val dialog = FileDialog(parent, "Open coroutree trace", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith("." + TraceFormat.FILE_EXTENSION) }
    dialog.isVisible = true
    return dialog.files.firstOrNull()
}
