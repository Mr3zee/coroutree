package kotlinx.coroutree.gui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutree.gui.demo.DemoTrace
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.TraceFeed
import kotlinx.coroutree.gui.source.TraceSource
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceFeedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frames = DemoTrace.frames()
    private val expected = snapshotOf(frames)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun awaitStatus(feed: TraceFeed, status: FeedStatus) = runBlocking {
        withTimeout(10_000) { feed.status.first { it == status } }
        // The final snapshot is published right after the status flips.
        withTimeout(10_000) { feed.snapshot.first { it.complete } }
    }

    private fun session(port: Int, traceFile: File?, ended: Boolean = false) =
        SessionInfo(null, pid = 1, port = port, token = "secret", traceFile = traceFile, taskPath = ":run", buildId = "b", command = "Main", startedAt = 1, ended = ended)

    @Test
    fun loadsARecordedFile() {
        val file = Files.createTempFile("feed", ".ctrace").toFile().apply { writeBytes(traceBytes(frames)) }
        val feed = TraceFeed(TraceSource.TraceFile(file), scope)
        val snapshot = awaitStatus(feed, FeedStatus.RECORDED)
        assertEquals(expected.events.size, snapshot.events.size)
        assertEquals(expected.nodes.keys, snapshot.nodes.keys)
        assertEquals(":shop:run", snapshot.header?.taskPath)
    }

    @Test
    fun reportsAnUnreadableFileInsteadOfFailing() {
        val file = Files.createTempFile("feed", ".ctrace").toFile().apply { writeText("definitely not a trace") }
        val feed = TraceFeed(TraceSource.TraceFile(file), scope)
        awaitStatus(feed, FeedStatus.FAILED)
        assertTrue("signature" in feed.failure.value.orEmpty())
    }

    @Test
    fun followsALiveSessionUntilItEnds() {
        val bytes = traceBytes(frames)
        val half = bytes.size / 2
        val secondHalf = CountDownLatch(1)
        var receivedToken = ""
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                server.accept().use { client ->
                    receivedToken = client.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                    val out = client.getOutputStream()
                    out.write(bytes, 0, half)
                    out.flush()
                    secondHalf.await()
                    out.write(bytes, half, bytes.size - half)
                }
            }
            val feed = TraceFeed(TraceSource.Session(session(server.localPort, traceFile = null)), scope, publishIntervalMillis = 20)
            val partial = runBlocking { withTimeout(10_000) { feed.snapshot.first { it.events.isNotEmpty() } } }
            assertEquals(FeedStatus.LIVE, feed.status.value)
            assertTrue(partial.events.size < expected.events.size && !partial.complete, "frames show up while the stream is still open")

            secondHalf.countDown()
            val snapshot = awaitStatus(feed, FeedStatus.ENDED)
            assertEquals("secret", receivedToken)
            assertEquals(expected.events.size, snapshot.events.size)
        }
    }

    @Test
    fun fallsBackToTheTraceFileWhenNobodyListens() {
        val file = Files.createTempFile("feed", ".ctrace").toFile().apply { writeBytes(traceBytes(frames)) }
        val deadPort = ServerSocket(0).use { it.localPort }
        for (info in listOf(session(deadPort, file), session(deadPort, file, ended = true))) {
            val snapshot = awaitStatus(TraceFeed(TraceSource.Session(info), scope), FeedStatus.RECORDED)
            assertEquals(expected.events.size, snapshot.events.size)
        }
    }

    @Test
    fun closingAbandonsABlockedLiveRead() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) { runCatching { server.accept().getInputStream().read(ByteArray(1024)); Thread.sleep(60_000) } }
            val feed = TraceFeed(TraceSource.Session(session(server.localPort, traceFile = null)), scope)
            runBlocking {
                withTimeout(10_000) { feed.status.first { it == FeedStatus.LIVE } }
                withTimeout(10_000) { feed.close() }
            }
        }
    }

    @Test
    fun demoStreamsLikeALiveSession() {
        val feed = TraceFeed(TraceSource.Demo(live = true), scope, publishIntervalMillis = 10, demoFrameDelayMillis = 1)
        val snapshot = awaitStatus(feed, FeedStatus.ENDED)
        assertEquals(expected.events.size, snapshot.events.size)
    }
}
