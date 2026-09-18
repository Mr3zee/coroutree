package kotlinx.coroutree.gui.source

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutree.gui.demo.DemoGate
import kotlinx.coroutree.gui.demo.DemoTrace
import kotlinx.coroutree.gui.view.PaceCommand
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.TraceReader
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.TraceStore
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class FeedStatus(val label: String) {
    CONNECTING("connecting"),

    /** Attached to a running JVM. */
    LIVE("live"),

    /** Reading a file. */
    LOADING("loading"),
    RECORDED("recorded"),

    /** Was live; the JVM exited or the connection dropped. */
    ENDED("ended"),
    FAILED("failed"),
}

/**
 * Reads a [TraceSource] in the background and publishes what it has folded so far.
 *
 * Snapshots go out at most every [publishIntervalMillis] however fast frames arrive: a snapshot costs a copy of the
 * node map, and nobody can watch more than a few updates a second anyway.
 *
 * While it is attached to a running JVM the feed is also the way to it: [send] writes a command of execution control to
 * the same socket the trace comes from. Nothing comes back for a command but the trace itself, which says what the
 * agent's gate is set to from then on.
 */
class TraceFeed(
    val source: TraceSource,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val publishIntervalMillis: Long = 100,
    private val demoFrameDelayMillis: Long = 40,
) {
    private val store = TraceStore()
    private val _snapshot = MutableStateFlow(TraceSnapshot.EMPTY)
    private val _status = MutableStateFlow(FeedStatus.CONNECTING)
    private val _failure = MutableStateFlow<String?>(null)

    val snapshot: StateFlow<TraceSnapshot> get() = _snapshot
    val status: StateFlow<FeedStatus> get() = _status
    val failure: StateFlow<String?> get() = _failure

    // Where commands go while the feed is live: the socket of the session, or the stand-in gate of the demo.
    private val commandLock = Any()
    @Volatile
    private var commands: OutputStream? = null
    @Volatile
    private var demoGate: DemoGate? = null

    /**
     * Sends a command of execution control to the JVM this feed follows. Does nothing when there is no JVM at the other
     * end (a recorded trace, a session that has ended); whether the JVM has a gate at all is for the caller to know
     * from the trace header.
     */
    fun send(command: PaceCommand) {
        demoGate?.let { return it.command(command) }
        val out = commands ?: return
        // Not on the caller's thread, which is the UI's: a socket write may block.
        scope.launch(ioDispatcher) {
            try {
                synchronized(commandLock) {
                    out.write((command.line + "\n").toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
            } catch (_: IOException) {
                // The JVM is gone; the read side notices and ends the feed.
            }
        }
    }

    private val job: Job = scope.launch(ioDispatcher) {
        val publisher = launch {
            while (isActive) {
                delay(publishIntervalMillis)
                publish()
            }
        }
        var terminal = FeedStatus.FAILED
        try {
            terminal = read()
        } catch (e: Exception) {
            // Closing the feed fails the blocked read with an IOException; that is the cancellation, not a failure.
            ensureActive()
            _failure.value = e.message ?: e.javaClass.simpleName
        } finally {
            publisher.cancel()
            synchronized(store) { store.endOfStream() }
            // The last snapshot goes out before the status says it is over: whoever follows the feed while it is
            // live must get to see how it ended.
            publish()
            _status.value = terminal
        }
    }

    suspend fun close() = job.cancelAndJoin()

    private fun publish() {
        _snapshot.value = synchronized(store) { store.snapshot() }
    }

    /** Reads the source to its end and returns the status the feed ends in. */
    private suspend fun read(): FeedStatus {
        when (val source = source) {
            is TraceSource.Demo -> {
                _status.value = if (source.live) FeedStatus.LIVE else FeedStatus.LOADING
                val gate = if (source.live) DemoGate(::accept).also { demoGate = it } else null
                try {
                    for (frame in DemoTrace.frames()) {
                        if (gate != null && frame.pace != null) continue // played live, the gate speaks for itself
                        frame.event?.let { gate?.await(it) }
                        accept(frame)
                        if (frame.header != null) gate?.announce()
                        if (source.live && frame.event != null) delay(demoFrameDelayMillis)
                    }
                } finally {
                    demoGate = null
                }
                return if (source.live) FeedStatus.ENDED else FeedStatus.RECORDED
            }
            is TraceSource.TraceFile -> return readFile(source.file)
            is TraceSource.Session -> {
                val socket = if (source.info.ended) null else runCatching { source.info.connect() }.getOrNull()
                if (socket != null) {
                    _status.value = FeedStatus.LIVE
                    commands = socket.getOutputStream()
                    val frames = try {
                        pump(socket, socket.getInputStream())
                    } catch (e: IOException) {
                        // A JVM that is killed resets the connection instead of closing it. Either way it is gone.
                        currentCoroutineContext().ensureActive()
                        accepted
                    } finally {
                        commands = null
                    }
                    if (frames > 0) return FeedStatus.ENDED
                    // Connected, but to something that hung up without a word: a stale descriptor whose port now
                    // belongs to somebody else. Same as not connecting at all.
                }
                // Finished, crashed, or never listening: the file has everything that was captured.
                return readFile(source.info.traceFile ?: error("The session is over and names no trace file"))
            }
        }
    }

    private suspend fun readFile(file: File): FeedStatus {
        _status.value = FeedStatus.LOADING
        val input = FileInputStream(file)
        pump(input, input)
        return FeedStatus.RECORDED
    }

    /** Frames accepted so far, by [pump]. */
    @Volatile
    private var accepted = 0

    // A blocking socket read notices neither cancellation nor interruption, so cancellation closes the resource
    // under the reader, which fails the pending read. That includes the very first read, of the file signature.
    private suspend fun pump(resource: Closeable, input: InputStream): Int = coroutineScope {
        // Undispatched: the closer must be waiting before the read blocks, or a cancellation in between would
        // cancel it unstarted and leave nobody to close the resource.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { resource.close() }
            }
        }
        try {
            runInterruptible {
                val reader = TraceReader(input)
                while (true) {
                    accept(reader.next() ?: break)
                    accepted++
                }
            }
        } finally {
            closer.cancel()
        }
        accepted
    }

    private fun accept(frame: Frame) = synchronized(store) { store.accept(frame) }
}
