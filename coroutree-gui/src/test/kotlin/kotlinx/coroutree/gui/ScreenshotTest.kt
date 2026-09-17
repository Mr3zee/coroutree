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

    @Test
    fun nodeAndEventSelected() {
        for (dark in listOf(false, true)) {
            val viewModel = TraceViewModel().also { it.snapshot = snapshot }
            viewModel.selectEvent(snapshot.named("payment").events.first { it.kind == EventKind.EXCEPTION_THROWN })
            capture(if (dark) "event-selected-dark" else "event-selected-light") { Screen(viewModel, dark, FeedStatus.RECORDED) }
        }
    }

    @Test
    fun nodeWithCrossLinksSelected() {
        val viewModel = TraceViewModel().also { it.snapshot = snapshot }
        viewModel.navigateTo(snapshot.named("metrics").id)
        capture("links-selected-light") { Screen(viewModel, dark = false, FeedStatus.RECORDED) }
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
