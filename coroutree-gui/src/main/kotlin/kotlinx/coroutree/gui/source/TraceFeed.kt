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

    LIVE("live"),

    LOADING("loading"),
    RECORDED("recorded"),

    /** The JVM exited or the live connection closed. */
    ENDED("ended"),

    /** The user disconnected. The JVM runs on and the captured trace stays on screen. */
    DISCONNECTED("disconnected"),

    /** Read from the file of a session that isn't over: the JVM runs without a socket, doesn't answer, or crashed. Everything up to the last flush. */
    PARTIAL("partial"),
    FAILED("failed");

    /** Nothing more arrives. */
    val terminal: Boolean
        get() = when (this) {
            CONNECTING, LIVE, LOADING -> false
            RECORDED, ENDED, DISCONNECTED, PARTIAL, FAILED -> true
        }
}

/**
 * Reads [source] in the background and publishes what it has folded so far as [snapshot].
 * Snapshots go out at most every [publishIntervalMillis], whatever the frame rate, because each one copies the node map.
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

    private val commandLock = Any()
    @Volatile
    private var commands: OutputStream? = null
    @Volatile
    private var demoGate: DemoGate? = null

    /**
     * Does nothing when no JVM is at the other end (a recorded trace, an ended session).
     * The caller reads from the trace header whether the JVM has a gate.
     */
    fun send(command: PaceCommand) {
        demoGate?.let { return it.command(command) }
        val out = commands ?: return
        // The caller's thread is the UI thread, and a socket write can block.
        scope.launch(ioDispatcher) {
            try {
                synchronized(commandLock) {
                    out.write((command.line + "\n").toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
            } catch (_: IOException) {
                // The read side handles connection failure and ends the feed.
            }
        }
    }

    // Must be set before cancellation so the reader reports a user disconnection.
    @Volatile
    private var disconnecting = false

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
            // Closing the feed fails the blocked read with an IOException: that is the cancellation, not a failure.
            ensureActive()
            _failure.value = e.message ?: e.javaClass.simpleName
        } finally {
            publisher.cancel()
            synchronized(store) { store.endOfStream() }
            // The last snapshot goes out before the terminal status, so a follower that stops at the status sees the end.
            publish()
            _status.value = if (disconnecting) FeedStatus.DISCONNECTED else terminal
        }
    }

    suspend fun close() = job.cancelAndJoin()

    /**
     * Ends the feed with [FeedStatus.DISCONNECTED]. The snapshot stays. If this was the last controller, the agent's
     * gate fails open: back to the configured pace, nothing paused.
     */
    suspend fun disconnect() {
        disconnecting = true
        close()
    }

    private fun publish() {
        _snapshot.value = synchronized(store) { store.snapshot() }
    }

    private suspend fun read(): FeedStatus {
        when (val source = source) {
            is TraceSource.Demo -> {
                _status.value = if (source.live) FeedStatus.LIVE else FeedStatus.LOADING
                val gate = if (source.live) DemoGate(::accept).also { demoGate = it } else null
                try {
                    for (frame in DemoTrace.frames()) {
                        if (gate != null && frame.pace != null) continue // the live demo gate emits its own settings
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
                val socket = if (source.info.ended || !source.info.live) null else runCatching { source.info.connect() }.getOrNull()
                if (socket != null) {
                    _status.value = FeedStatus.LIVE
                    commands = socket.getOutputStream()
                    val frames = try {
                        pump(socket, socket.getInputStream())
                    } catch (e: IOException) {
                        // A killed JVM resets the connection instead of closing it. Either way the frames read so far are the trace.
                        currentCoroutineContext().ensureActive()
                        accepted
                    } finally {
                        commands = null
                    }
                    if (frames > 0) return FeedStatus.ENDED
                    // An empty replay means the port belongs to another process now: the same as no connection.
                }
                // Finished, crashed, or never listening: the file has everything that was captured.
                readFile(source.info.traceFile ?: error("The session is over and names no trace file"))
                return if (source.info.ended) FeedStatus.RECORDED else FeedStatus.PARTIAL
            }
        }
    }

    private suspend fun readFile(file: File): FeedStatus {
        _status.value = FeedStatus.LOADING
        val input = FileInputStream(file)
        pump(input, input)
        return FeedStatus.RECORDED
    }

    @Volatile
    private var accepted = 0

    // A blocking socket read notices neither cancellation nor interruption, so cancellation closes the resource
    // under the reader. That fails the pending read, the first one (the signature) included.
    private suspend fun pump(resource: Closeable, input: InputStream): Int = coroutineScope {
        // Undispatched, so the closer waits before the read blocks. Otherwise a cancellation in between cancels it
        // unstarted, and nobody closes the resource.
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
