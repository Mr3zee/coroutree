package kotlinx.coroutree.gui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.ui.CoroutreeTheme
import kotlinx.coroutree.gui.ui.DirListing
import kotlinx.coroutree.gui.ui.StartScreen
import kotlinx.coroutree.gui.ui.TraceScreen
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.tree.TraceStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the main screens offscreen into build/screenshots. Asserts only that rendering works;
 * whether the result looks right is for eyes to judge.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotTest {
    private val outputDir = File(System.getProperty("coroutree.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val snapshot = demoSnapshot()

    private fun capture(name: String, width: Int = 1360, height: Int = 860, content: @Composable () -> Unit) {
        runDesktopComposeUiTest(width, height) {
            setContent(content)
            waitForIdle()
            val file = File(outputDir, "$name.png")
            ImageIO.write(captureToImage().toAwtImage(), "png", file)
            assertTrue(file.length() > 10_000, "suspiciously small render: $file")
        }
    }

    @Composable
    private fun Screen(viewModel: TraceViewModel, dark: Boolean, status: FeedStatus) {
        CoroutreeTheme(dark) { TraceScreen(viewModel, "demo", status, failure = null, onOpenFrame = {}, onClose = {}) }
    }

    @Test
    fun overview() {
        for (dark in listOf(false, true)) {
            val viewModel = TraceViewModel().also { it.snapshot = snapshot }
            capture(if (dark) "overview-dark" else "overview-light") { Screen(viewModel, dark, FeedStatus.LIVE) }
        }
    }

    /** Execution control: a live session that somebody has paused, with one subtree slowed down and selected. */
    @Test
    fun executionControl() {
        val receipt = snapshot.named("receipt")
        val running = TraceStore().apply {
            kotlinx.coroutree.gui.demo.DemoTrace.frames().forEach(::accept)
            accept(kotlinx.coroutree.model.Frame(pace = kotlinx.coroutree.model.PaceDef(afterSeq = snapshot.events.last().seq, intervalNanos = 250_000_000, paused = true, reason = kotlinx.coroutree.model.PaceDef.Reason.CONTROLLER)))
        }.snapshot()
        for (dark in listOf(false, true)) {
            val viewModel = TraceViewModel().also {
                it.snapshot = running
                it.commands = {}
            }
            viewModel.selectNode(receipt.id)
            capture(if (dark) "execution-control-dark" else "execution-control-light") { Screen(viewModel, dark, FeedStatus.LIVE) }
        }
    }

    /** An event that names another node: both nodes and the edge between them stand out. */
    @Test
    fun eventSelected() {
        for (dark in listOf(false, true)) {
            val viewModel = TraceViewModel().also { it.snapshot = snapshot }
            viewModel.selectEvent(snapshot.constructed("coroutineScope").events.first { it.kind == EventKind.EXCEPTION_PROPAGATED })
            capture(if (dark) "event-selected-dark" else "event-selected-light") { Screen(viewModel, dark, FeedStatus.RECORDED) }
        }
    }

    @Test
    fun nodeWithCrossLinksSelected() {
        val viewModel = TraceViewModel().also { it.snapshot = snapshot }
        viewModel.navigateTo(snapshot.named("metrics").id)
        capture("links-selected-light") { Screen(viewModel, dark = false, FeedStatus.RECORDED) }
    }

    /** Pools and library internals in the graph, dimmed, and with them the thread the selected coroutine runs on. */
    @Test
    fun libraryShown() {
        for (dark in listOf(false, true)) {
            val viewModel = TraceViewModel().also { it.snapshot = snapshot }
            viewModel.showLibrary(true)
            viewModel.selectNode(snapshot.named("receipt").id)
            capture(if (dark) "library-dark" else "library-light") { Screen(viewModel, dark, FeedStatus.RECORDED) }
        }
    }

    @Test
    fun zoomedToSelection() {
        val viewModel = TraceViewModel().also { it.snapshot = snapshot }
        val payment = snapshot.named("payment")
        viewModel.selectNode(payment.id)
        runDesktopComposeUiTest(1360, 860) {
            setContent { Screen(viewModel, dark = false, FeedStatus.RECORDED) }
            waitForIdle()
            viewModel.graphView.zoomTo(payment.id)
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(outputDir, "zoomed-light.png"))
        }
    }

    /** A few hundred nodes: fitted (lines a pixel wide, faint), and at a zoom where boxes are coloured rectangles and the minimap is up. */
    @Test
    fun largeGraph() {
        val viewModel = TraceViewModel().also { it.snapshot = snapshotOf(largeTrace(nodes = 700, links = 120, seed = 11)) }
        runDesktopComposeUiTest(1360, 860) {
            setContent { Screen(viewModel, dark = false, FeedStatus.RECORDED) }
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(outputDir, "large-fitted-light.png"))
            viewModel.graphView.zoom(0.25f / viewModel.graphView.viewport.zoom)
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(outputDir, "large-light.png"))
        }
    }

    /** A frame from the middle of a glide: the library has just been switched on. */
    @Test
    fun midGlide() {
        val viewModel = TraceViewModel().also { it.snapshot = snapshot }
        runDesktopComposeUiTest(1360, 860) {
            setContent { Screen(viewModel, dark = false, FeedStatus.LIVE) }
            waitForIdle()
            mainClock.autoAdvance = false
            viewModel.showLibrary(true)
            mainClock.advanceTimeBy(230)
            ImageIO.write(captureToImage().toAwtImage(), "png", File(outputDir, "glide-light.png"))
        }
    }

    /** `./gradlew :coroutree-gui:test -PscreenshotTrace=/path/to/trace.ctrace`: how a real trace looks, not the demo. */
    @Test
    fun recordedTrace() {
        val trace = System.getProperty("coroutree.screenshots.trace") ?: return
        val recorded = File(trace).inputStream().use(TraceStore::read)
        val viewModel = TraceViewModel().also { it.snapshot = recorded }
        recorded.events.lastOrNull { it.kind == EventKind.EXCEPTION_PROPAGATED }?.let(viewModel::selectEvent)
        capture("recorded-trace") { Screen(viewModel, dark = false, FeedStatus.RECORDED) }
    }

    @Test
    fun startScreen() {
        val dir = File("/Users/me/shop/build/coroutree")
        val listing = DirListing(
            sessions = listOf(SessionInfo(null, 4242, 5555, "t", null, ":shop:run", "b", "demo.shop.CheckoutKt", System.currentTimeMillis(), ended = false)),
            traces = listOf(File(dir, "traces/20260917-201500/run-4242.ctrace"), File(dir, "traces/20260917-194200/test-4100.ctrace")),
        )
        for (dark in listOf(false, true)) {
            capture(if (dark) "start-dark" else "start-light", width = 1000, height = 640) {
                CoroutreeTheme(dark) { StartScreen(dir, listing, onOpen = {}, onChooseTraceFile = {}, onChooseDir = {}) }
            }
        }
    }
}
