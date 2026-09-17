package kotlinx.coroutree.gui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import kotlinx.coroutree.gui.demo.DemoTrace
import kotlinx.coroutree.gui.graph.assertInvariant
import kotlinx.coroutree.gui.ui.CoroutreeTheme
import kotlinx.coroutree.gui.ui.GraphPane
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.StackFrameDef
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The graph pane as the user meets it: boxes to click, a canvas to pan and zoom, a toolbar, a minimap, a hover card. */
@OptIn(ExperimentalTestApi::class)
class GraphPaneUiTest {
    private val snapshot = demoSnapshot()
    private val viewModel = TraceViewModel()
    private val view get() = viewModel.graphView
    private val opened = mutableListOf<StackFrameDef>()

    private fun test(initial: TraceSnapshot = snapshot, width: Int = 900, height: Int = 520, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        viewModel.snapshot = initial
        setContent {
            CoroutreeTheme(dark = false) {
                Box(Modifier.size(width.dp, height.dp)) { GraphPane(viewModel, onOpenFrame = { opened += it }) }
            }
        }
        waitForIdle()
        block()
    }

    /** Where the graph canvas is within the pane, to turn a point of the canvas into one the node under test understands. */
    private fun ComposeUiTest.onCanvas(nodeId: Long): Offset = viewModel.centreInPane(nodeId, density.density)

    private fun ComposeUiTest.assertDrawnWhereTheLayoutSays(nodeId: Long) {
        val canvas = onNodeWithTag("graph").getBoundsInRoot()
        val box = onNodeWithTag("node-$nodeId").getBoundsInRoot()
        val expected = view.viewport.toScreen(view.rect(nodeId)!!)
        assertTrue(abs((box.left - canvas.left).value - expected.left) < 1.5f && abs((box.top - canvas.top).value - expected.top) < 1.5f, "node $nodeId is at $box, expected $expected")
        assertTrue(abs((box.right - box.left).value - expected.width) < 1.5f, "node $nodeId is ${box.right - box.left} wide on screen, expected ${expected.width}")
    }

    @Test
    fun opensFittedWithEveryNodeOfTheGraphABoxOnTheCanvas() = test {
        assertTrue(view.autoFit && view.wholeGraphInView)
        assertEquals(16, viewModel.layout.boxes.size)
        for (box in viewModel.layout.boxes) assertDrawnWhereTheLayoutSays(box.id)
        onNodeWithTag("graph-counts").assertTextEquals("16 nodes · 7 hidden")
        onAllNodesWithTag("minimap").assertCountEquals(0) // nothing is outside the pane
    }

    @Test
    fun clickingABoxSelectsItAndClickingTheBackgroundClearsTheSelection() = test {
        val audit = snapshot.named("audit")
        onNodeWithTag("node-${audit.id}").assertIsNotSelected().performClick()
        waitForIdle()
        assertEquals(audit.id, viewModel.selectedNodeId)
        onNodeWithTag("node-${audit.id}").assertIsSelected()

        onNodeWithTag("graph").performMouseInput { click(Offset(4f, 4f)) }
        waitForIdle()
        assertNull(viewModel.selectedNodeId)
        onNodeWithTag("node-${audit.id}").assertIsNotSelected()
    }

    @Test
    fun anEventThatNamesAnotherNodeMarksBothBoxes() = test {
        val scope = snapshot.constructed("coroutineScope")
        val payment = snapshot.named("payment")
        viewModel.selectEvent(scope.events.first { it.kind == EventKind.EXCEPTION_PROPAGATED })
        waitForIdle()
        onNodeWithTag("node-${scope.id}").assertIsSelected().assertStateDescription("failed, highlighted")
        onNodeWithTag("node-${payment.id}").assertIsNotSelected().assertStateDescription("failed, highlighted")
        onNodeWithTag("node-${snapshot.named("audit").id}").assertStateDescription("cancelled")
    }

    @Test
    fun legendTogglesTakeCrossLinksOutOfTheGraphAndPutThemBack() = test {
        for (kind in EdgeKind.entries) onNodeWithTag("links-${kind.name}").assertIsOn()
        assertTrue(viewModel.layout.routes.any { it.link.kind == EdgeKind.CANCELS })

        onNodeWithTag("links-CANCELS").performClick()
        waitForIdle()
        onNodeWithTag("links-CANCELS").assertIsOff()
        assertTrue(view.layout.routes.none { it.link.kind == EdgeKind.CANCELS })
        assertTrue(view.layout.routes.any { it.link.kind == EdgeKind.LAUNCHED_FROM })

        onNodeWithTag("links-CANCELS").performClick()
        waitForIdle()
        onNodeWithTag("links-CANCELS").assertIsOn()
        assertTrue(view.layout.routes.any { it.link.kind == EdgeKind.CANCELS })

        onNodeWithTag("toggle-labels").assertIsOn().performClick()
        waitForIdle()
        assertTrue(view.layout.routes.all { it.label == null })
    }

    @Test
    fun libraryToggleBringsPoolsAndLibraryInternalsIntoTheGraph() = test {
        val pool = snapshot.named("DefaultDispatcher")
        val selector = snapshot.named("http-selector")
        onNodeWithTag("toggle-library").assertIsOff()
        onAllNodesWithTag("node-${pool.id}").assertCountEquals(0)

        onNodeWithTag("toggle-library").performClick()
        waitForIdle()
        onNodeWithTag("toggle-library").assertIsOn()
        onNodeWithTag("graph-counts").assertTextEquals("23 nodes")
        assertTrue(view.settled && view.wholeGraphInView, "the pane still follows the graph")
        for (id in listOf(pool.id, selector.id)) assertDrawnWhereTheLayoutSays(id)

        onNodeWithTag("toggle-library").performClick()
        waitForIdle()
        onAllNodesWithTag("node-${selector.id}").assertCountEquals(0)
    }

    @Test
    fun runsOnAppearsForTheSelectedNodeOnly() = test {
        onNodeWithTag("toggle-library").performClick()
        waitForIdle()
        val receipt = snapshot.named("receipt")
        assertTrue(view.layout.routes.none { it.link.kind == EdgeKind.RUNS_ON })
        onNodeWithTag("node-${receipt.id}").performClick()
        waitForIdle()
        assertEquals(listOf(receipt.id to receipt.runsOn), view.layout.routes.filter { it.link.kind == EdgeKind.RUNS_ON }.map { it.link.from to it.link.to })
        assertInvariant(view.layout, "what is on screen")
    }

    @Test
    fun zoomButtonsZoomAndFitAndGoToTheSelection() = test {
        val fitted = view.viewport
        onNodeWithTag("zoom-level").assertTextEquals("${(fitted.zoom * 100).toInt()}%")
        onNodeWithTag("zoom-in").performClick()
        waitForIdle()
        assertEquals(fitted.zoom * 1.25f, view.viewport.zoom, 0.001f)
        assertFalse(view.autoFit)
        onNodeWithTag("zoom-level").assertTextEquals("${(view.viewport.zoom * 100).toInt()}%")
        onNodeWithTag("zoom-out").performClick()
        onNodeWithTag("zoom-out").performClick()
        waitForIdle()
        assertEquals(fitted.zoom, view.viewport.zoom, 0.001f, "no further out than it takes to see everything")

        onNodeWithTag("zoom-fit").performClick()
        waitForIdle()
        assertEquals(fitted, view.viewport)
        assertTrue(view.autoFit)

        val email = snapshot.named("email")
        onNodeWithTag("zoom-selection").performClick() // nothing is selected: nothing happens
        assertEquals(fitted, view.viewport)
        onNodeWithTag("node-${email.id}").performClick()
        onNodeWithTag("zoom-selection").performClick()
        waitForIdle()
        assertEquals(1f, view.viewport.zoom)
        val onScreen = view.viewport.toScreen(view.rect(email.id)!!)
        assertEquals(view.width / 2, onScreen.centerX, 0.5f)
        assertEquals(view.height / 2, onScreen.centerY, 0.5f)
        assertDrawnWhereTheLayoutSays(email.id)
        onNodeWithTag("minimap").assertExists() // now there is somewhere else to go
    }

    @Test
    fun draggingTheBackgroundAndScrollingPanTheCanvas() = test {
        val before = view.viewport
        onNodeWithTag("graph").performMouseInput {
            moveTo(Offset(10f, 10f))
            press()
            moveBy(Offset(60f, 35f))
            moveBy(Offset(60f, 35f))
            release()
        }
        waitForIdle()
        assertEquals(before.zoom, view.viewport.zoom)
        assertTrue(view.viewport.panX > before.panX + 100 && view.viewport.panY > before.panY + 50, "${view.viewport}")
        assertNull(viewModel.selectedNodeId, "a drag is not a click")

        val dragged = view.viewport
        onNodeWithTag("graph").performMouseInput { scroll(2f) }
        waitForIdle()
        assertTrue(view.viewport.panY < dragged.panY && view.viewport.panX == dragged.panX && view.viewport.zoom == dragged.zoom)
        for (box in viewModel.layout.boxesIn(view.viewport.visibleWorld(view.width, view.height))) {
            if (view.isInView(box.id)) assertDrawnWhereTheLayoutSays(box.id)
        }
    }

    @Test
    fun scrollingWithCtrlZoomsAroundThePointer() = test {
        val main = snapshot.named("main")
        val pointer = onCanvas(main.id)
        val before = view.viewport
        onNodeWithTag("graph").performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                this@test.onNodeWithTag("graph").performMouseInput {
                    moveTo(pointer)
                    scroll(-3f)
                }
            }
        }
        waitForIdle()
        assertTrue(view.viewport.zoom > before.zoom * 1.5f, "${view.viewport}")
        val after = onCanvas(main.id)
        assertTrue(abs(after.x - pointer.x) < 1f && abs(after.y - pointer.y) < 1f, "what was under the pointer still is: $pointer → $after")
    }

    @Test
    fun rightClickOnABoxOffersToOpenItsSourceInTheIde() = test {
        val payment = snapshot.named("payment")
        onNodeWithTag("node-${payment.id}").performMouseInput { rightClick() }
        waitForIdle()
        onNodeWithText("Open in IDE").performClick()
        waitForIdle()
        assertEquals(listOf("Checkout.kt" to 73), opened.map { it.fileName to it.line })

        onNodeWithTag("node-${payment.id}").performMouseInput { rightClick() }
        waitForIdle()
        onNodeWithText("Zoom to node").performClick()
        waitForIdle()
        assertEquals(payment.id, viewModel.selectedNodeId)
        assertEquals(1f, view.viewport.zoom)
    }

    @Test
    fun restingOnABoxBringsUpItsCardWhoseSiteOpensInTheIde() = test {
        val inventory = snapshot.named("inventory")
        onAllNodesWithTag("node-card").assertCountEquals(0)
        onNodeWithTag("node-${inventory.id}").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(300)
        onAllNodesWithTag("node-card").assertCountEquals(0) // not yet: the pointer may be passing by
        mainClock.advanceTimeBy(300)
        waitForIdle()
        onNodeWithTag("node-card").assertExists()
        onNodeWithText("Dispatcher: BlockingEventLoop@5e91993f → Dispatchers.Default").assertExists()

        // The card stays while the pointer travels onto it, so that its link can be clicked.
        onNodeWithTag("card-site").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(1000)
        onNodeWithTag("card-site").assertTextEquals("↗ Checkout.kt:17").performClick()
        assertEquals(listOf("Checkout.kt" to 17), opened.map { it.fileName to it.line })

        onNodeWithTag("graph").performMouseInput { moveTo(Offset(3f, 3f)) }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        onAllNodesWithTag("node-card").assertCountEquals(0)
    }

    @Test
    fun minimapShowsWhereThePaneIsAndMovesIt() = test {
        onNodeWithTag("zoom-in").performClick()
        onNodeWithTag("zoom-in").performClick()
        onNodeWithTag("zoom-in").performClick()
        waitForIdle()
        val sms = snapshot.named("sms")
        val main = snapshot.named("main")
        onNodeWithTag("minimap").performMouseInput { click(Offset(width - 12f, height - 12f)) } // bottom right of the graph
        waitForIdle()
        assertTrue(view.isInView(sms.id) && !view.isInView(main.id), "${view.viewport}")
        onNodeWithTag("minimap").performMouseInput { click(Offset(width * 0.45f, 14f)) } // top middle
        waitForIdle()
        assertTrue(view.isInView(main.id) && !view.isInView(sms.id), "${view.viewport}")
    }

    @Test
    fun anEventOfANodeOutsideThePaneBringsItIntoView() = test {
        val sms = snapshot.named("sms")
        view.zoomTo(snapshot.named("inventory").id)
        waitForIdle()
        assertFalse(view.isInView(sms.id))
        val zoom = view.viewport.zoom

        viewModel.selectEvent(sms.events.last())
        waitForIdle()
        assertTrue(view.isInView(sms.id))
        assertEquals(zoom, view.viewport.zoom, "revealing pans, it does not zoom")
        onNodeWithTag("node-${sms.id}").assertIsSelected()
        assertDrawnWhereTheLayoutSays(sms.id)
    }

    @Test
    fun boxesTooSmallToReadAreDrawnOnTheCanvasAndStillThereToClick() {
        val large = snapshotOf(largeTrace(nodes = 160, links = 25, seed = 5))
        test(initial = large) {
            assertTrue(view.viewport.zoom < 0.3f && view.wholeGraphInView, "fitted: ${view.viewport}")
            val id = large.nodes.keys.max()
            onAllNodesWithTag("node-$id").assertCountEquals(0) // no text to read at this size, so no composable either
            onNodeWithTag("graph").performMouseInput { click(onCanvas(id)) }
            waitForIdle()
            assertEquals(id, viewModel.selectedNodeId)

            onNodeWithTag("zoom-selection").performClick()
            waitForIdle()
            onNodeWithTag("node-$id").assertIsSelected()
            assertDrawnWhereTheLayoutSays(id)
            // Only what is in and around the pane is composed.
            val composed = view.layout.boxes.count { onAllNodesWithTag("node-${it.id}").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(composed in 1 until 60, "$composed of ${view.layout.boxes.size} boxes are composed")
        }
    }

    @Test
    fun aGrowingLiveTraceGlidesToEachNewLayoutAndStaysInView() = test(initial = TraceSnapshot.EMPTY) {
        onNodeWithText("No nodes yet").assertExists()
        val frames = DemoTrace.frames()
        var previous = view.layout
        var layouts = 0
        for (upTo in listOf(3, 12, 25, 40, 60, 90, frames.size)) {
            viewModel.snapshot = snapshotOf(frames.take(upTo))
            waitForIdle()
            assertTrue(view.settled, "after $upTo frames")
            assertTrue(view.autoFit && view.wholeGraphInView, "after $upTo frames the pane still shows all of the graph")
            if (view.layout !== previous) layouts++
            previous = view.layout
            assertInvariant(view.layout, "the layout after $upTo frames")
            for (box in view.layout.boxes) assertDrawnWhereTheLayoutSays(box.id)
        }
        assertTrue(layouts >= 5, "the graph grew in $layouts steps")
    }

    @Test
    fun midGlideBoxesAreBetweenTheirOldAndNewPlacesAndNewOnesFadeIn() = test {
        // The pool is the second root: it lands between main and metrics, and metrics has to make room.
        val main = snapshot.named("metrics")
        val settledAt = view.rect(main.id)!!
        mainClock.autoAdvance = false
        viewModel.showLibrary(true)
        mainClock.advanceTimeBy(140)
        assertFalse(view.settled)
        val pool = snapshot.named("DefaultDispatcher")
        assertTrue(view.alpha(pool.id) in 0.01f..0.99f, "a new box is fading in: ${view.alpha(pool.id)}")
        assertEquals(1f, view.alpha(main.id), "a box that was there already does not fade")
        val target = view.layout.box(main.id)!!.rect
        assertNotEquals(settledAt.left, target.left.toFloat(), "the pool pushes metrics aside")
        val now = view.rect(main.id)!!.left
        assertTrue(now > minOf(settledAt.left, target.left.toFloat()) && now < maxOf(settledAt.left, target.left.toFloat()), "$now is between ${settledAt.left} and ${target.left}")
        assertDrawnWhereTheLayoutSays(main.id)

        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(view.settled)
        assertEquals(target.left.toFloat(), view.rect(main.id)!!.left)
    }

    @Test
    fun aTraceOfNothingButLibraryNodesSaysWhereTheyAre() = test(
        initial = snapshotOf(trace { node(1, 0, origin = kotlinx.coroutree.model.Origin.LIBRARY); node(2, 1, origin = kotlinx.coroutree.model.Origin.LIBRARY) }),
    ) {
        onNodeWithText("All 2 nodes so far are library machinery — switch on “library / pools” to see them").assertExists()
        onNodeWithTag("toggle-library").performClick()
        waitForIdle()
        onNodeWithTag("node-2").assertExists()
    }
}

private fun SemanticsNodeInteraction.assertStateDescription(expected: String): SemanticsNodeInteraction {
    assertEquals(expected, fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    return this
}
