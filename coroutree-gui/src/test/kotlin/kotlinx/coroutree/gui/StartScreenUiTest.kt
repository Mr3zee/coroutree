package kotlinx.coroutree.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.source.SessionEntry
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.SessionState
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.gui.ui.CoroutreeTheme
import kotlinx.coroutree.gui.ui.StartScreen
import kotlinx.coroutree.gui.ui.TraceScreen
import kotlinx.coroutree.gui.view.TraceViewModel
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The session list, and the buttons that connect and disconnect: what is offered when, and what a click asks for. */
@OptIn(ExperimentalTestApi::class)
class StartScreenUiTest {
    private val traces: File = Files.createTempDirectory("coroutree-start").toFile().also { it.deleteOnExit() }

    private fun session(pid: Long, state: SessionState, port: Int = 5555, trace: Boolean = true, liveFailed: Boolean = false) = SessionEntry(
        SessionInfo(null, pid, port, "t", File(traces, "$pid.ctrace").also { if (trace) it.writeText("x") }, ":app:run", "b", "demo.MainKt", pid, ended = state == SessionState.ENDED, liveFailed = liveFailed),
        state,
    )

    @Test
    fun runningJvmsAreListedFirstThenRecentRunsThatStillHaveATrace() = runComposeUiTest {
        val opened = mutableListOf<TraceSource>()
        val sessions = listOf(
            session(1, SessionState.RUNNING),
            session(2, SessionState.RUNNING, port = 0),
            session(3, SessionState.ENDED),
            session(4, SessionState.CRASHED),
            session(5, SessionState.ENDED, trace = false),
            session(6, SessionState.CRASHED, trace = false),
            session(7, SessionState.UNREACHABLE),
            session(8, SessionState.RUNNING, port = 0, liveFailed = true),
        )
        setContent { CoroutreeTheme(dark = false) { StartScreen(traces, sessions, onOpen = opened::add, onChooseTraceFile = {}) } }

        for (pid in listOf(1L, 2L, 3L, 4L, 7L, 8L)) onNodeWithTag("session-$pid").assertExists()
        for (pid in listOf(5L, 6L)) onNodeWithTag("session-$pid").assertDoesNotExist()
        onAllNodesWithText("live").assertCountEquals(1)
        onAllNodesWithText("no socket").assertCountEquals(1)
        onAllNodesWithText("socket failed").assertCountEquals(1)
        onAllNodesWithText("crashed").assertCountEquals(1)
        onAllNodesWithText("unreachable").assertCountEquals(1)

        onNodeWithTag("session-4").performClick()
        onNodeWithTag("demo").performClick()
        assertEquals(listOf(TraceSource.Session(sessions[3].info), TraceSource.Demo(live = false)), opened)
    }

    @Test
    fun theTraceScreenOffersDisconnectWhileLiveAndReconnectAfterwards() = runComposeUiTest {
        val viewModel = TraceViewModel().also { it.snapshot = demoSnapshot() }
        val pressed = mutableListOf<String>()
        var status by mutableStateOf(FeedStatus.LIVE)
        setContent {
            CoroutreeTheme(dark = false) {
                TraceScreen(
                    viewModel, "demo", status, failure = null, onOpenFrame = {}, onClose = { pressed += "sessions" },
                    onDisconnect = if (status == FeedStatus.LIVE) ({ pressed += "disconnect"; status = FeedStatus.DISCONNECTED }) else null,
                    onReconnect = if (status == FeedStatus.DISCONNECTED) ({ pressed += "reconnect" }) else null,
                )
            }
        }
        onNodeWithTag("reconnect").assertDoesNotExist()
        onNodeWithTag("disconnect").performClick()
        waitForIdle()
        onNodeWithTag("disconnect").assertDoesNotExist()
        onNodeWithTag("reconnect").performClick()
        onNodeWithTag("open-another").performClick()
        assertEquals(listOf("disconnect", "reconnect", "sessions"), pressed)
    }
}
