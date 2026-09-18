package kotlinx.coroutree.gui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.ui.CoroutreeTheme
import kotlinx.coroutree.gui.ui.TraceScreen
import kotlinx.coroutree.gui.ui.subtreeControlItems
import kotlinx.coroutree.gui.view.SpeedScale
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.tree.TraceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Execution control on screen (DESIGN §6): the toolbar of a live, paceable session, the same for a subtree in the
 * details pane and the node's menu, the marks in the graph and in the log, the keys. Commands are caught where the
 * feed would send them; the "agent" answers by way of a new snapshot, as the real one does by way of the stream.
 */
@OptIn(ExperimentalTestApi::class)
class PaceUiTest {
    private val demo = DemoTrace()
    private val sent = mutableListOf<String>()

    /** The demo trace as a stream that is still running, with whatever the agent has said about its gate so far. */
    private class DemoTrace {
        private val frames = kotlinx.coroutree.gui.demo.DemoTrace.frames().filter { it.pace == null }
        fun snapshot(vararg pace: PaceDef) = TraceStore().apply {
            frames.forEach(::accept)
            pace.forEach { accept(Frame(pace = it)) }
        }.snapshot()
    }

    private fun setting(scope: Long = 0, interval: Long = 0, paused: Boolean = false, reason: PaceDef.Reason = PaceDef.Reason.CONTROLLER) =
        PaceDef(scopeNodeId = scope, intervalNanos = interval, paused = paused, reason = reason)

    private fun live(vararg pace: PaceDef) = TraceViewModel().also {
        it.snapshot = demo.snapshot(*pace)
        it.commands = { command -> sent += command.line }
    }

    private fun test(viewModel: TraceViewModel, status: FeedStatus = FeedStatus.LIVE, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            CoroutreeTheme(dark = false) { TraceScreen(viewModel, "demo", status, failure = null, onOpenFrame = {}, onClose = {}) }
        }
        block()
    }

    @Test
    fun aRecordedTraceHasNoControlsButShowsWhatWasSet() {
        val viewModel = TraceViewModel().also { it.snapshot = demoSnapshot() }
        test(viewModel, FeedStatus.RECORDED) {
            onAllNodesWithTag("pace-bar").assertCountEquals(0)
            onAllNodesWithTag("paused").assertCountEquals(0)
            val receipt = viewModel.snapshot.named("receipt")
            onNodeWithTag("pace-marker-${receipt.id}").assertExists()
            onAllNodesWithTag("pace-marker-${viewModel.snapshot.named("payment").id}").assertCountEquals(0)
            onNodeWithTag("pace-change-0").assertTextContains("as configured", substring = true)
            onNodeWithTag("node-${receipt.id}").performClick()
            waitForIdle()
            onNodeWithTag("node-pace").assertTextContains("1 event in 2 s per sequence — set on this node, for its subtree")
            onAllNodesWithTag("node-pace-toggle").assertCountEquals(0)
        }
    }

    @Test
    fun aJvmWithoutAGateHasNoControlsEither() {
        val viewModel = live()
        viewModel.snapshot = TraceStore().apply {
            kotlinx.coroutree.gui.demo.DemoTrace.frames().filter { it.pace == null }.forEach { frame ->
                accept(frame.header?.let { Frame(header = it.copy(paceable = false)) } ?: frame)
            }
        }.snapshot()
        test(viewModel) {
            onAllNodesWithTag("pace-bar").assertCountEquals(0)
            onNodeWithTag("graph").performKeyInput { pressKey(Key.Spacebar) }
            assertEquals(emptyList(), sent)
        }
    }

    @Test
    fun theToolbarSendsCommandsAndShowsWhatComesBack() {
        val viewModel = live(setting(reason = PaceDef.Reason.CONFIG))
        test(viewModel) {
            onNodeWithTag("pace-setting").assertTextContains("full speed")
            onNodeWithTag("pace-toggle").assertTextContains("Pause")
            onNodeWithTag("pace-toggle").performClick()
            onNodeWithTag("pace-step").performClick()
            waitForIdle()
            assertEquals(listOf("pause", "step 1"), sent)
            onNodeWithTag("pace-setting").assertTextContains("full speed") // nothing has come back yet: nothing has changed
            onAllNodesWithTag("paused").assertCountEquals(0)

            viewModel.snapshot = demo.snapshot(setting(reason = PaceDef.Reason.CONFIG), setting(paused = true, interval = 500_000_000))
            waitForIdle()
            onNodeWithTag("pace-setting").assertTextContains("paused (2 events/s per sequence when resumed)")
            onNodeWithTag("pace-toggle").assertTextContains("Resume")
            onNodeWithTag("paused").assertExists()
            onNodeWithTag("pace-toggle").performClick()
            waitForIdle()
            assertEquals("resume", sent.last())
        }
    }

    @Test
    fun theSliderSetsThePaceAndFollowsTheStream() {
        val viewModel = live(setting(reason = PaceDef.Reason.CONFIG))
        test(viewModel) {
            fun position() = onNodeWithTag("pace-slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            assertEquals(1f, position(), "no limit: at the far end")
            onNodeWithTag("pace-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(SpeedScale.UNLIMITED_FROM / 2) }
            waitForIdle()
            assertEquals(listOf("pace 100000000"), sent)
            assertEquals(1f, position(), "it shows what is, not what was asked for")

            viewModel.snapshot = demo.snapshot(setting(interval = 100_000_000))
            waitForIdle()
            assertEquals(SpeedScale.positionOf(100_000_000), position())
            onNodeWithTag("pace-setting").assertTextContains("10 events/s per sequence")

            // Dragged to the slow end and let go.
            onNodeWithTag("pace-slider").performTouchInput { swipe(Offset(width * 0.5f, height / 2f), Offset(0f, height / 2f), durationMillis = 100) }
            waitForIdle()
            assertEquals("pace 10000000000", sent.last())
            // Clicked at the very end: no limit.
            onNodeWithTag("pace-slider").performTouchInput { down(Offset(width - 1f, height / 2f)); up() }
            waitForIdle()
            assertEquals("pace 0", sent.last())
        }
    }

    @Test
    fun spaceAndTheArrowKeyControlTheProgram() {
        val viewModel = live(setting(paused = true))
        test(viewModel) {
            onNodeWithTag("graph").performKeyInput { pressKey(Key.DirectionRight) }
            onNodeWithTag("graph").performKeyInput { pressKey(Key.DirectionRight) }
            onNodeWithTag("graph").performKeyInput { pressKey(Key.Spacebar) }
            waitForIdle()
            assertEquals(listOf("step 1", "step 1", "resume"), sent)
        }
    }

    @Test
    fun aSessionThatStartsPausedSaysSo() {
        val viewModel = live(setting(paused = true, reason = PaceDef.Reason.CONFIG))
        test(viewModel) {
            onNodeWithTag("paused-at-start").assertTextContains("Paused at start", substring = true)
            viewModel.snapshot = demo.snapshot(setting(paused = true, reason = PaceDef.Reason.CONFIG), setting(paused = true).copy(steps = 1))
            waitForIdle()
            onAllNodesWithTag("paused-at-start").assertCountEquals(0)
            onNodeWithTag("paused").assertExists()
        }
    }

    @Test
    fun aSubtreeIsControlledFromTheDetailsPaneAndMarkedInTheGraph() {
        val viewModel = live(setting(reason = PaceDef.Reason.CONFIG))
        val sms = viewModel.snapshot.named("sms") // finished in the demo: nothing to control
        val receipt = viewModel.snapshot.named("receipt")
        test(viewModel) {
            onNodeWithTag("node-${receipt.id}").performClick()
            waitForIdle()
            onNodeWithTag("node-pace").assertTextContains("full speed — the program's")
            onAllNodesWithTag("node-pace-inherit").assertCountEquals(0)
            onNodeWithTag("node-pace-toggle").performClick()
            onNodeWithTag("node-pace-step").performClick()
            waitForIdle()
            assertEquals(listOf("pause ${receipt.id}", "step 1 ${receipt.id}"), sent)
            onAllNodesWithTag("pace-marker-${receipt.id}").assertCountEquals(0)

            viewModel.snapshot = demo.snapshot(setting(reason = PaceDef.Reason.CONFIG), setting(scope = receipt.id, paused = true))
            waitForIdle()
            onNodeWithTag("pace-marker-${receipt.id}").assertExists()
            onNodeWithTag("node-pace").assertTextContains("paused — set on this node, for its subtree")
            val description = onNodeWithTag("node-${receipt.id}").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
            assertTrue("subtree paused" in description.orEmpty(), description)
            onNodeWithTag("node-pace-toggle").assertTextContains("Resume")
            onNodeWithTag("node-pace-inherit").performClick()
            waitForIdle()
            assertEquals("inherit ${receipt.id}", sent.last())

            onNodeWithTag("node-${sms.id}").performClick()
            waitForIdle()
            onAllNodesWithTag("node-pace-toggle").assertCountEquals(0)
        }
    }

    @Test
    fun theLayoutDoesNotMoveWhenANodeGetsAMark() {
        val viewModel = live(setting(reason = PaceDef.Reason.CONFIG))
        val receipt = viewModel.snapshot.named("receipt")
        val before = viewModel.layout
        viewModel.snapshot = demo.snapshot(setting(reason = PaceDef.Reason.CONFIG), setting(scope = receipt.id, paused = true))
        assertTrue(before === viewModel.layout, "a setting is not structure: the graph is not laid out again for it")
    }

    @Test
    fun theNodeMenuOffersTheSameForTheSubtree() {
        val viewModel = live(setting(reason = PaceDef.Reason.CONFIG))
        val receipt = viewModel.snapshot.named("receipt")
        assertEquals(listOf("Pause subtree", "Step subtree"), subtreeControlItems(viewModel, receipt.id).map { it.label })
        subtreeControlItems(viewModel, receipt.id).forEach { it.onClick() }
        assertEquals(listOf("pause ${receipt.id}", "step 1 ${receipt.id}"), sent)

        viewModel.snapshot = demo.snapshot(setting(scope = receipt.id, paused = true))
        val items = subtreeControlItems(viewModel, receipt.id)
        assertEquals(listOf("Resume subtree", "Step subtree", "Follow the program's speed"), items.map { it.label })
        items.last().onClick()
        assertEquals("inherit ${receipt.id}", sent.last())

        assertEquals(emptyList(), subtreeControlItems(viewModel, viewModel.snapshot.named("sms").id), "a node that has ended takes no setting")
        viewModel.commands = null
        assertEquals(emptyList(), subtreeControlItems(viewModel, receipt.id))
    }

    @Test
    fun changesOfTheGateAreRowsOfTheLog() {
        val viewModel = live(setting(reason = PaceDef.Reason.CONFIG), setting(paused = true).copy(afterSeq = 2), setting().copy(afterSeq = 2))
        // Looked at as a recording: a live log keeps to its tail, and the first row is what is asked for here.
        test(viewModel, FeedStatus.RECORDED) {
            onNodeWithTag("pace-change-0").assertTextContains("execution control: runs at full speed (as configured)")
            onNodeWithTag("pace-change-1").assertTextContains("execution control: paused")
            onNodeWithTag("pace-change-2").assertTextContains("execution control: runs at full speed")
        }
        assertEquals(viewModel.snapshot.events.size + 3, viewModel.eventLog.size)
    }
}
