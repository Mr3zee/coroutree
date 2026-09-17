package kotlinx.coroutree.gui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
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

/** The three panes together: what a click in one of them does to the others. The graph pane on its own is GraphPaneUiTest. */
@OptIn(ExperimentalTestApi::class)
class TraceScreenUiTest {
    private val snapshot = demoSnapshot()
    private val viewModel = TraceViewModel().also { it.snapshot = snapshot }
    private val opened = mutableListOf<StackFrameDef>()

    private fun test(block: ComposeUiTest.() -> Unit) = runComposeUiTest {
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
    fun clickingAnEventSelectsItsNodeInTheGraph() = test {
        val first = snapshot.events.first()
        onNodeWithTag("event-${first.seq}").performClick()
        waitForIdle()
        assertEquals(first.seq, viewModel.selectedEventSeq)
        assertEquals(first.nodeId, viewModel.selectedNodeId)
        onNodeWithTag("node-${first.nodeId}").assertIsSelected()
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
    fun followingAReferenceInTheDetailsPaneSelectsThatNodeInTheGraph() = test {
        val payment = snapshot.named("payment")
        val scope = snapshot.constructed("coroutineScope")
        onNodeWithTag("node-${payment.id}").performClick()
        waitForIdle()
        onNodeWithTag("ref-Parent").performClick()
        waitForIdle()
        assertEquals(scope.id, viewModel.selectedNodeId)
        onNodeWithTag("node-${scope.id}").assertIsSelected()
    }

    @Test
    fun anEventInsideTheLibraryBringsTheLibraryIntoTheGraph() = test {
        val selector = snapshot.named("http-selector")
        onAllNodesWithTag("node-${selector.id}").assertCountEquals(0)
        viewModel.selectEvent(selector.events.first { it.kind == EventKind.SUSPENDED })
        waitForIdle()
        onNodeWithTag("node-${selector.id}").assertIsSelected()
        assertTrue(viewModel.graphView.isInView(selector.id))
    }

    @Test
    fun thereIsNoOutline() = test {
        onAllNodesWithTag("tree").assertCountEquals(0)
        for (node in snapshot.nodes.values) onAllNodesWithTag("toggle-${node.id}").assertCountEquals(0)
        onNodeWithTag("graph").assertExists()
    }

    @Test
    fun diagnosticsCanBeDismissed() = test {
        onAllNodesWithTag("banner").assertCountEquals(1)
        onNodeWithTag("dismiss").performClick()
        waitForIdle()
        onAllNodesWithTag("banner").assertCountEquals(0)
    }
}

/** Centre of a node's box in the graph pane, in pixels of the pane. */
fun TraceViewModel.centreInPane(nodeId: Long, density: Float): Offset {
    val rect = graphView.viewport.toScreen(graphView.rect(nodeId)!!)
    return Offset(rect.centerX * density, rect.centerY * density)
}
