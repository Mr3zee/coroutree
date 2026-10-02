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
import kotlinx.coroutree.gui.view.PaceCommand
import kotlinx.coroutree.model.TraceFormat
import kotlinx.coroutree.model.tree.TraceSnapshot
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a feed ends when its source does not end cleanly. A trace's "producer may die at any moment" (TraceReader), a
 * session file may outlive its JVM and its port may by then belong to somebody else (TraceFeed.read): none of that
 * is a failure, and what was captured is shown.
 */
class TraceFeedCornersTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frames = DemoTrace.frames()
    private val bytes = traceBytes(frames)
    private val temp: File = Files.createTempDirectory("coroutree-feed").toFile()

    /** Where each frame ends in [bytes]. */
    private val frameEnds: List<Int> = frames.indices.map { traceBytes(frames.take(it + 1)).size }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        temp.deleteRecursively()
    }

    private fun file(content: ByteArray): File = Files.createTempFile(temp.toPath(), "trace", ".ctrace").toFile().apply { writeBytes(content) }

    private fun session(port: Int, traceFile: File?, ended: Boolean = false) =
        SessionInfo(null, pid = 1, port = port, token = "secret", traceFile = traceFile, taskPath = ":run", buildId = "b", command = "Main", startedAt = 1, ended = ended)

    /**
     * Waits for the feed to be over and returns what it then shows. "The last snapshot goes out before the status
     * says it is over": at the moment a terminal status is seen, the snapshot is the final one.
     */
    private fun TraceFeed.awaitEnd(expected: FeedStatus): TraceSnapshot = runBlocking {
        val terminal = withTimeout(10_000) { status.first { it == FeedStatus.RECORDED || it == FeedStatus.ENDED || it == FeedStatus.FAILED } }
        val shown = snapshot.value
        assertEquals(expected, terminal, "failure: ${failure.value}")
        assertTrue(shown.complete, "the status said $terminal before the final snapshot was out")
        shown
    }

    /** What a reader must make of the first [length] bytes: the events of the frames that are whole. */
    private fun eventsWithin(length: Int): List<Long> = frames.filterIndexed { i, _ -> frameEnds[i] <= length }.mapNotNull { it.event?.seq }

    /** A server for one connection, which it handles with [serve]; the token line has been read by then. */
    private fun <T> withServer(serve: (Socket) -> Unit, block: (ServerSocket) -> T): T =
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        client.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                        serve(client)
                    }
                }
            }
            block(server)
        }

    // ------------------------------------------------------------------ files

    /** TraceReader: "a frame that is cut short at the end of the stream ends the trace normally instead of failing it". */
    @Test
    fun aFileCutAnywhereShowsEveryFrameThatIsWhole() {
        val signature = TraceFormat.MAGIC.size
        // Inside the last frame, inside a frame's length, right after a frame, in the middle of the header, and spread over the file.
        val cuts = (listOf(bytes.size - 1, bytes.size - 3, frameEnds[frameEnds.size - 2], frameEnds[frameEnds.size - 2] + 1, frameEnds[0] - 1, frameEnds[0], signature + 1) +
            (1..12).map { signature + (bytes.size - signature) * it / 13 }).distinct()
        for (cut in cuts) {
            val snapshot = TraceFeed(TraceSource.TraceFile(file(bytes.copyOf(cut))), scope).awaitEnd(FeedStatus.RECORDED)
            assertEquals(eventsWithin(cut), snapshot.events.map { it.seq }, "cut at $cut of ${bytes.size}")
        }
        assertTrue(eventsWithin(bytes.size - 1).size == eventsWithin(bytes.size).size - 1, "the last frame is an event, and one byte short of it is one event short")
    }

    @Test
    fun aFileWithNothingAfterTheSignatureIsAnEmptyTraceAndOneWithLessIsNotATrace() {
        val empty = TraceFeed(TraceSource.TraceFile(file(TraceFormat.MAGIC)), scope)
        val snapshot = empty.awaitEnd(FeedStatus.RECORDED)
        assertTrue(snapshot.events.isEmpty() && snapshot.nodes.isEmpty() && snapshot.header == null)
        assertNull(empty.failure.value)

        for (content in listOf(ByteArray(0), TraceFormat.MAGIC.copyOf(5), "COROTREF".toByteArray() + bytes.drop(8))) {
            val feed = TraceFeed(TraceSource.TraceFile(file(content)), scope)
            feed.awaitEnd(FeedStatus.FAILED)
            assertTrue("signature" in feed.failure.value.orEmpty(), "said why: ${feed.failure.value}")
        }
    }

    @Test
    fun aFileThatIsNotThereIsReportedNotThrown() {
        val feed = TraceFeed(TraceSource.TraceFile(File(temp, "gone.ctrace")), scope)
        val snapshot = feed.awaitEnd(FeedStatus.FAILED)
        assertTrue(snapshot.events.isEmpty())
        assertTrue("gone.ctrace" in feed.failure.value.orEmpty(), "said which: ${feed.failure.value}")
    }

    @Test
    fun aRecordedDemoIsTheWholeDemoAtOnceGateSettingsIncludedAndTakesNoCommands() {
        val feed = TraceFeed(TraceSource.Demo(live = false), scope)
        val snapshot = feed.awaitEnd(FeedStatus.RECORDED)
        val expected = snapshotOf(frames)
        assertEquals(expected.events, snapshot.events)
        assertEquals(expected.paceChanges, snapshot.paceChanges)
        assertEquals(expected.pace, snapshot.pace)
        feed.send(PaceCommand.Pause())
        assertEquals(expected.paceChanges, feed.snapshot.value.paceChanges, "there is no gate behind a recording")
    }

    // ------------------------------------------------------------------ live sessions

    @Test
    fun aJvmThatDiesMidFrameEndsTheSessionWithWhatItSent() {
        val cut = frameEnds[frames.size / 2] + 2 // two bytes into the next frame
        val snapshot = withServer({ client ->
            client.getOutputStream().apply { write(bytes, 0, cut); flush() }
        }) { server ->
            TraceFeed(TraceSource.Session(session(server.localPort, traceFile = null)), scope, publishIntervalMillis = 10).awaitEnd(FeedStatus.ENDED)
        }
        assertEquals(eventsWithin(cut), snapshot.events.map { it.seq })
    }

    /** TraceFeed.read: "A JVM that is killed resets the connection instead of closing it. Either way it is gone." */
    @Test
    fun aConnectionThatIsResetEndsTheSessionWithoutAFailure() {
        val seen = CountDownLatch(1)
        withServer({ client ->
            client.getOutputStream().apply { write(bytes, 0, frameEnds[frames.size / 2]); flush() }
            seen.await()
            client.setSoLinger(true, 0) // close with a reset, as the death of the process does
        }) { server ->
            val feed = TraceFeed(TraceSource.Session(session(server.localPort, traceFile = null)), scope, publishIntervalMillis = 10)
            val before = runBlocking { withTimeout(10_000) { feed.snapshot.first { it.events.size == eventsWithin(frameEnds[frames.size / 2]).size } } }
            assertEquals(FeedStatus.LIVE, feed.status.value)
            seen.countDown()
            val after = feed.awaitEnd(FeedStatus.ENDED)
            assertEquals(before.events, after.events)
            assertNull(feed.failure.value)
        }
    }

    /**
     * TraceFeed.read: "Connected, but to something that hung up without a word: a stale descriptor whose port now
     * belongs to somebody else. Same as not connecting at all." — and the same for somebody who answers in another tongue.
     */
    @Test
    fun aPortThatNowBelongsToSomebodyElseFallsBackToTheTraceFile() {
        val recorded = file(bytes)
        val strangers = listOf<(Socket) -> Unit>(
            { },
            { it.getOutputStream().apply { write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()); flush() } },
            { it.getOutputStream().apply { write(TraceFormat.MAGIC, 0, 4); flush() } },
        )
        for ((i, stranger) in strangers.withIndex()) {
            val snapshot = withServer(stranger) { server ->
                TraceFeed(TraceSource.Session(session(server.localPort, recorded)), scope).awaitEnd(FeedStatus.RECORDED)
            }
            assertEquals(eventsWithin(bytes.size), snapshot.events.map { it.seq }, "stranger $i")
        }
    }

    @Test
    fun aSessionWithNobodyToTalkToAndNoTraceFileSaysSo() {
        val unreachable = listOf(
            session(ServerSocket(0).use { it.localPort }, traceFile = null),
            session(1, traceFile = null, ended = true),
        )
        for (info in unreachable) {
            val feed = TraceFeed(TraceSource.Session(info), scope)
            feed.awaitEnd(FeedStatus.FAILED)
            assertTrue("no trace file" in feed.failure.value.orEmpty(), "said why: ${feed.failure.value}")
        }
        withServer({ }) { server ->
            val feed = TraceFeed(TraceSource.Session(session(server.localPort, traceFile = null)), scope)
            feed.awaitEnd(FeedStatus.FAILED)
            assertTrue("no trace file" in feed.failure.value.orEmpty(), "hung up on: ${feed.failure.value}")
        }
    }

    @Test
    fun aSessionThatSaysItHasEndedIsReadFromItsFileWithoutKnockingOnItsPort() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            val snapshot = TraceFeed(TraceSource.Session(session(server.localPort, file(bytes), ended = true)), scope).awaitEnd(FeedStatus.RECORDED)
            assertEquals(eventsWithin(bytes.size), snapshot.events.map { it.seq })
            server.soTimeout = 200
            assertFailsWith<SocketTimeoutException>("somebody connected") { server.accept() }
        }
    }

    @Test
    fun aSessionThatHasEndedTakesNoMoreCommands() {
        withServer({ client -> client.getOutputStream().apply { write(bytes); flush() } }) { server ->
            val feed = TraceFeed(TraceSource.Session(session(server.localPort, traceFile = null)), scope, publishIntervalMillis = 10)
            val snapshot = feed.awaitEnd(FeedStatus.ENDED)
            assertEquals(eventsWithin(bytes.size), snapshot.events.map { it.seq })
            feed.send(PaceCommand.Pause()) // to a JVM that is gone: nothing is written, nothing thrown
            runBlocking { feed.close() }
            assertEquals(FeedStatus.ENDED, feed.status.value, "closing what is over changes nothing")
        }
    }
}
