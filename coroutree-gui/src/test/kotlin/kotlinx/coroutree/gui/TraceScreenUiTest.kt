package kotlinx.coroutree.gui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.ui.CoroutreeTheme
import kotlinx.coroutree.gui.ui.TraceScreen
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.StackFrameDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TraceScreenUiTest {
    private val snapshot = demoSnapshot()
    private val viewModel = TraceViewModel().also { it.snapshot = snapshot }
    private val opened = mutableListOf<StackFrameDef>()

    private fun test(block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            CoroutreeTheme(dark = false) {
                TraceScreen(viewModel, "demo", FeedStatus.RECORDED, failure = null, onOpenFrame = { opened += it }, onClose = {})
            }
        }
        block()
    }

    @Test
    fun clickingANodeShowsItsDetailsAndItsSiteOpensInTheIde() = test {
        val payment = snapshot.named("payment")
        onNodeWithTag("node-${payment.id}").performClick()
        waitForIdle()
        assertEquals(payment.id, viewModel.selectedNodeId)
        onAllNodesWithText("kotlinx.coroutines.StandaloneCoroutine").assertCountEquals(1)

        onNodeWithTag("site").performClick()
        assertEquals(listOf("Checkout.kt" to 73), opened.map { it.fileName to it.line })
    }

    @Test
    fun clickingAnEventSelectsItsNode() = test {
        val first = snapshot.events.first()
        onNodeWithTag("event-${first.seq}").performClick()
        waitForIdle()
        assertEquals(first.seq, viewModel.selectedEventSeq)
        assertEquals(first.nodeId, viewModel.selectedNodeId)
    }

    @Test
    fun selectingANodeScrollsTheLogToItsLatestEvent() = test {
        val sms = snapshot.named("sms")
        val last = sms.events.last()
        assertTrue(last.kind == EventKind.FINISHED)
        onAllNodesWithTag("event-${last.seq}").assertCountEquals(0)
        onNodeWithTag("node-${sms.id}").performClick()
        waitForIdle()
        onAllNodesWithTag("event-${last.seq}").assertCountEquals(1)
    }

    @Test
    fun collapsingHidesTheSubtree() = test {
        val scope = snapshot.constructed("coroutineScope")
        val payment = snapshot.named("payment")
        onNodeWithTag("toggle-${scope.id}").performClick()
        waitForIdle()
        onAllNodesWithTag("node-${payment.id}").assertCountEquals(0)
        onNodeWithTag("toggle-${scope.id}").performClick()
        waitForIdle()
        onAllNodesWithTag("node-${payment.id}").assertCountEquals(1)
    }

    @Test
    fun diagnosticsCanBeDismissed() = test {
        onAllNodesWithTag("banner").assertCountEquals(1)
        onNodeWithTag("dismiss").performClick()
        waitForIdle()
        onAllNodesWithTag("banner").assertCountEquals(0)
    }
}
