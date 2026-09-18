package kotlinx.coroutree.it

import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.TraceReader
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.TraceStore
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.Socket
import kotlin.test.fail

/**
 * A client of a running JVM's live socket, the way a GUI is one: it reads the stream and may send commands of
 * execution control (DESIGN §3.1). What it has received so far can be looked at and waited on from the test's thread.
 */
class LiveClient(port: Int, token: String) : Closeable {
    private val socket = Socket(InetAddress.getLoopbackAddress(), port)
    private val lock = Object()
    private val received = ArrayList<Frame>()
    private var ended = false

    init {
        socket.getOutputStream().write("$token\n".toByteArray())
        socket.getOutputStream().flush()
    }

    private val reader = Thread({
        try {
            val trace = TraceReader(socket.getInputStream())
            while (true) {
                val frame = trace.next() ?: break
                synchronized(lock) {
                    received += frame
                    lock.notifyAll()
                }
            }
        } catch (_: Exception) {
            // closed, by either side
        } finally {
            synchronized(lock) {
                ended = true
                lock.notifyAll()
            }
        }
    }, "test-live-client").apply {
        isDaemon = true
        start()
    }

    /** One command line; `pause`, `step 3`, `pace 1000000 42`, … */
    fun send(command: String) {
        socket.getOutputStream().write("$command\n".toByteArray())
        socket.getOutputStream().flush()
    }

    /** Sends bytes as they are: for what is not a well-formed line. */
    fun sendRaw(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    val frames: List<Frame> get() = synchronized(lock) { ArrayList(received) }
    val events: List<Event> get() = frames.mapNotNull { it.event }
    val paceDefs: List<PaceDef> get() = frames.mapNotNull { it.pace }

    /** What has been received, folded the way a GUI folds it. */
    fun snapshot(): TraceSnapshot = TraceStore().apply {
        frames.forEach(::accept)
        endOfStream()
    }.snapshot()

    /** Steps so far: events that are not a continuation of the step before them. */
    val steps: Int get() = events.count { !it.sameStep }

    val streamEnded: Boolean get() = synchronized(lock) { ended }

    /** Waits until [condition] holds for what has been received, and returns it; fails after [timeoutMillis]. */
    fun await(what: String, timeoutMillis: Long = 30_000, condition: (List<Frame>) -> Boolean): List<Frame> {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        synchronized(lock) {
            while (true) {
                if (condition(received)) return ArrayList(received)
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (ended || left <= 0) {
                    fail("${if (ended) "The stream ended" else "Timed out"} waiting for $what. Received ${received.size} frames, events:\n" +
                        received.mapNotNull { it.event }.takeLast(30).joinToString("\n") { "  #${it.seq} ${it.kind} node=${it.nodeId} thread=${it.threadId}" })
                }
                lock.wait(left)
            }
        }
    }

    /** Whether [condition] comes to hold within [millis]; for "and nothing else happens". */
    fun holdsWithin(millis: Long, condition: (List<Frame>) -> Boolean): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000
        synchronized(lock) {
            while (true) {
                if (condition(received)) return true
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (ended || left <= 0) return false
                lock.wait(left)
            }
        }
    }

    fun awaitEnd(timeoutMillis: Long = 60_000) {
        reader.join(timeoutMillis)
        if (reader.isAlive) fail("the live stream did not end in $timeoutMillis ms")
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** The session descriptor of a JVM started with `live=true` by [startUnderAgent], once the agent has written it. */
class LiveSession(val port: Int, val token: String, val paceable: Boolean, val text: String) {
    fun connect(): LiveClient = LiveClient(port, token)
}

fun AgentProcess.awaitSession(timeoutMillis: Long = 30_000): LiveSession {
    val descriptor = File(sessionsDir, "${process.pid()}.json")
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (System.nanoTime() < deadline) {
        if (descriptor.exists()) {
            val text = descriptor.readText()
            return LiveSession(text.jsonValue("port").toInt(), text.jsonValue("token"), text.jsonValue("paceable") == "true", text)
        }
        if (!process.isAlive) fail("$mainClass ended before it published a live session. Output:\n$output")
        Thread.sleep(10)
    }
    fail("Timed out waiting for the session descriptor $descriptor. Output:\n$output")
}

/** Value of a top-level scalar field of the flat JSON object the agent writes. */
fun String.jsonValue(key: String): String {
    val match = Regex("\"" + Regex.escape(key) + "\":(\"((?:[^\"\\\\]|\\\\.)*)\"|[^,}]+)").find(this) ?: fail("no \"$key\" in $this")
    return (match.groups[2]?.value ?: match.groupValues[1]).replace("\\\\", "\\").replace("\\\"", "\"").trim()
}
