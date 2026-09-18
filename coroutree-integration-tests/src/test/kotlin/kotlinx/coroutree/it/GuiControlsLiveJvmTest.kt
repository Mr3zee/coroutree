package kotlinx.coroutree.it

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.TraceFeed
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.gui.view.PaceCommand
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.tree.TraceSnapshot
import org.junit.jupiter.api.Timeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GUI's side of execution control against the real agent: the GUI's own feed and view model, no test client in
 * between. What the GUI shows is what came back in the stream, and what it sends is what the agent does.
 */
@Timeout(180)
class GuiControlsLiveJvmTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun TraceFeed.awaitSnapshot(what: String, condition: (TraceSnapshot) -> Boolean): TraceSnapshot =
        try {
            withTimeout(30_000) { snapshot.first(condition) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what; ${snapshot.value.events.size} events, pace ${snapshot.value.pace}", e)
        }

    @Test
    fun theGuiPausesStepsSlowsDownAndResumesARunningProgram() {
        startPaceControl("Gui-controls", mapOf("pace.paused" to "true")).use { program ->
            try {
                val descriptor = File(program.started.sessionsDir, "${program.started.process.pid()}.json")
                val info = SessionInfo.read(descriptor)
                assertTrue(info.paceable, "the session descriptor says that the JVM can be controlled")
                val feed = TraceFeed(TraceSource.Session(info), scope, publishIntervalMillis = 20)
                val viewModel = TraceViewModel()
                fun follow(snapshot: TraceSnapshot) = snapshot.also { viewModel.snapshot = it }

                runBlocking {
                    withTimeout(30_000) { feed.status.first { it == FeedStatus.LIVE } }
                    viewModel.commands = feed::send
                    follow(feed.awaitSnapshot("the configured setting") { it.pace.global != null })
                    assertTrue(viewModel.paceable)
                    assertTrue(viewModel.pausedAtStart, "the banner: paused at start — resume or step")
                    assertEquals(0, viewModel.snapshot.events.size)

                    viewModel.step(count = 4)
                    follow(feed.awaitSnapshot("four steps") { snapshot -> snapshot.events.count { !it.sameStep } == 4 })
                    delay(400)
                    assertEquals(4, feed.snapshot.value.events.count { !it.sameStep }, "exactly the four that were asked for")
                    follow(feed.snapshot.value)
                    assertFalse(viewModel.pausedAtStart)
                    assertEquals(true, viewModel.globalPace?.paused)

                    viewModel.togglePause() // it is paused: this resumes
                    follow(feed.awaitSnapshot("the resume") { it.pace.global?.paused == false })
                    program.awaitOutput("ready")

                    viewModel.setPace(50_000_000)
                    follow(feed.awaitSnapshot("the pace") { it.pace.global?.intervalNanos == 50_000_000L })
                    val left = feed.awaitSnapshot("the left thread") { snapshot -> snapshot.nodes.values.any { it.info.name == "left" } }.nodes.values.single { it.info.name == "left" }
                    viewModel.togglePause(left.id)
                    follow(feed.awaitSnapshot("the subtree's pause") { it.pace.nodes[left.id]?.paused == true })
                    assertEquals(50_000_000L, viewModel.ownPace(left.id)?.intervalNanos, "a subtree's setting starts as what governed it")
                    val ticker = viewModel.snapshot.nodes.values.first { it.info.name == "left-ticker-1" }
                    assertEquals(true, viewModel.governingPace(ticker.id)?.paused)

                    viewModel.inherit(left.id)
                    viewModel.setPace(0)
                    follow(feed.awaitSnapshot("full speed again") { it.pace.nodes.isEmpty() && it.pace.global?.isOpen == true })
                    assertEquals(
                        listOf(PaceDef.Reason.CONFIG) + List(6) { PaceDef.Reason.CONTROLLER },
                        viewModel.snapshot.paceChanges.map { it.reason },
                    )
                    assertEquals(viewModel.snapshot.events.size + 7, viewModel.eventLog.size, "and every change is a row of the log")
                }
                program.stopAndAwaitExit()
                runBlocking {
                    feed.awaitSnapshot("the end of the stream") { it.complete }
                    viewModel.snapshot = feed.snapshot.value
                    withTimeout(10_000) { feed.status.first { it == FeedStatus.ENDED } }
                    viewModel.commands = null
                    assertFalse(viewModel.paceable, "a program that has ended has nothing to control")
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun whenTheGuiGoesAwayTheProgramItPausedRunsOn() {
        startPaceControl("Gui-goes-away").use { program ->
            try {
                program.awaitOutput("ready")
                val info = SessionInfo.read(File(program.started.sessionsDir, "${program.started.process.pid()}.json"))
                val feed = TraceFeed(TraceSource.Session(info), scope, publishIntervalMillis = 20)
                val observer = program.connect()
                runBlocking {
                    withTimeout(30_000) { feed.status.first { it == FeedStatus.LIVE } }
                    feed.send(PaceCommand.Pause())
                    feed.awaitSnapshot("the pause") { it.pace.global?.paused == true }
                    observer.awaitQuiet()
                    feed.close() // the window is closed, or the GUI crashes: the same to the agent
                }
                observer.awaitPace("the fail-open") { it.reason == PaceDef.Reason.FAIL_OPEN && !it.paused }
                observer.assertGoesOn("the program runs on", atLeast = 50)
                program.stopAndAwaitExit()
            } finally {
                scope.cancel()
            }
        }
    }
}
