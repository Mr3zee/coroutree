package kotlinx.coroutree.gui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutree.gui.demo.DemoTrace
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.TraceFeed
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.gui.view.PaceCommand
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.tree.PaceSetting
import kotlinx.coroutree.model.tree.TraceSnapshot
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The feed as the way to the agent's gate: commands go out on the socket the trace comes in on; the demo answers them itself. */
class PaceFeedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun session(port: Int) = SessionInfo(null, pid = 1, port = port, token = "secret", traceFile = null, taskPath = ":run", buildId = "b", command = "Main", startedAt = 1, ended = false, paceable = true)

    private fun <T> await(what: String, block: suspend () -> T): T = runBlocking { withTimeoutOrNull(10_000) { block() } ?: error("timed out waiting for $what") }

    @Test
    fun commandsGoOutOnTheSocketTheTraceComesInOn() {
        val lines = LinkedBlockingQueue<String>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                server.accept().use { client ->
                    val out = client.getOutputStream()
                    out.write(traceBytes(DemoTrace.frames().take(3)))
                    out.flush()
                    client.getInputStream().bufferedReader(Charsets.US_ASCII).forEachLine(lines::add)
                }
            }
            val feed = TraceFeed(TraceSource.Session(session(server.localPort)), scope, publishIntervalMillis = 10)
            await("the feed to be live") { feed.status.first { it == FeedStatus.LIVE } }
            feed.send(PaceCommand.Pause())
            feed.send(PaceCommand.Step(3, node = 12))
            feed.send(PaceCommand.SetPace(250_000_000))
            feed.send(PaceCommand.Inherit(12))
            val received = (1..5).map { lines.poll(10, TimeUnit.SECONDS) }
            assertEquals("secret", received[0], "the token first, as ever")
            assertEquals(setOf("pause", "step 3 12", "pace 250000000", "inherit 12"), received.drop(1).toSet())
            runBlocking { feed.close() }
            feed.send(PaceCommand.Resume()) // to nobody: nothing happens, nothing is thrown
        }
    }

    @Test
    fun aRecordedTraceHasNobodyToSendTo() {
        val file = Files.createTempFile("feed", ".ctrace").toFile().apply { writeBytes(traceBytes(DemoTrace.frames())) }
        val feed = TraceFeed(TraceSource.TraceFile(file), scope)
        await("the file") { feed.status.first { it == FeedStatus.RECORDED } }
        feed.send(PaceCommand.Pause())
        assertEquals(5, feed.snapshot.value.paceChanges.size, "the settings the recording tells of, and no new one")
    }

    @Test
    fun sessionDescriptorsSayWhetherTheJvmHasAGate() {
        val text = """{"pid":1,"port":2,"token":"t","traceFile":"/x","taskPath":":run","buildId":"b","command":"M","startedAt":3,"paceable":true,"ended":false}"""
        assertTrue(SessionInfo.parse(text).paceable)
        assertFalse(SessionInfo.parse(text.replace("true", "false")).paceable)
        assertFalse(SessionInfo.parse(text.replace(",\"paceable\":true", "")).paceable, "an agent from before execution control")
    }

    // ------------------------------------------------------------------ the demo's stand-in gate

    /** The demo as a live session, once it is one: a command sent before there is anybody to take it goes nowhere. */
    private suspend fun demo(frameDelayMillis: Long = 1): TraceFeed {
        val feed = TraceFeed(TraceSource.Demo(live = true), scope, publishIntervalMillis = 5, demoFrameDelayMillis = frameDelayMillis)
        withTimeout(10_000) { feed.status.first { it == FeedStatus.LIVE } }
        withTimeout(10_000) { feed.snapshot.first { it.header != null } }
        return feed
    }

    private suspend fun TraceFeed.awaitSnapshot(condition: (TraceSnapshot) -> Boolean) = withTimeout(10_000) { snapshot.first(condition) }

    @Test
    fun theLiveDemoCanBePausedSteppedAndResumed() = runBlocking {
        val total = DemoTrace.frames().count { it.event != null }
        val feed = demo(frameDelayMillis = 15)
        feed.send(PaceCommand.Pause())
        feed.awaitSnapshot { it.pace.global?.paused == true }
        delay(150)
        val standing = feed.snapshot.value.events.size
        delay(250)
        assertEquals(standing, feed.snapshot.value.events.size, "paused, the demo does not go on")
        assertTrue(standing < total - 10, "it was paused long before its end: $standing of $total")

        feed.send(PaceCommand.Step(3))
        feed.awaitSnapshot { it.events.size == standing + 3 }
        delay(200)
        assertEquals(standing + 3, feed.snapshot.value.events.size, "exactly three")
        assertEquals(3, feed.snapshot.value.paceChanges.last().steps)

        feed.send(PaceCommand.Resume())
        val done = feed.awaitSnapshot { it.complete }
        assertEquals(total, done.events.size)
        assertEquals(FeedStatus.ENDED, feed.status.value)
        assertEquals(listOf(PaceDef.Reason.CONFIG, PaceDef.Reason.CONTROLLER, PaceDef.Reason.CONTROLLER, PaceDef.Reason.CONTROLLER), done.paceChanges.map { it.reason }, "played live, only what was really asked for is in the stream")
    }

    @Test
    fun theLiveDemoKnowsSubtreesAndIgnoresNodesItHasNotSeen() = runBlocking {
        val feed = demo()
        feed.send(PaceCommand.Pause(node = 123_456))
        val main = feed.awaitSnapshot { it.nodes.values.any { node -> node.info.name == "main" } }.named("main")
        feed.send(PaceCommand.SetPace(40_000_000, node = main.id))
        val set = feed.awaitSnapshot { main.id in it.pace.nodes }
        assertEquals(PaceSetting(40_000_000, false), set.pace.nodes[main.id])
        assertNull(set.pace.nodes[123_456])
        feed.send(PaceCommand.Inherit(main.id))
        val dropped = feed.awaitSnapshot { it.pace.nodes.isEmpty() && it.paceChanges.any { change -> change.dropped } }
        assertTrue(dropped.paceChanges.last { it.scopeNodeId == main.id }.dropped)
        feed.awaitSnapshot { it.complete }
        Unit
    }
}
