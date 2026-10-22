package kotlinx.coroutree.gui.source

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

data class SessionInfo(
    val file: File?,
    val pid: Long,
    /** Socket port, or 0 if the JVM started without a live socket. */
    val port: Int,
    val token: String,
    val traceFile: File?,
    val taskPath: String,
    val buildId: String,
    val command: String,
    val startedAt: Long,
    val ended: Boolean,
    val paceable: Boolean = false,
    /** The agent was configured for a socket and couldn't open one. The trace carries the reason as a warning. */
    val liveFailed: Boolean = false,
) {
    val title: String get() = listOf(taskPath, command).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "pid $pid" }

    /** Whether there was ever a socket to connect to. */
    val live: Boolean get() = port > 0

    /**
     * The socket replays the trace from its first byte, then streams new frames until the JVM exits.
     */
    fun connect(timeoutMillis: Int = CONNECT_TIMEOUT_MILLIS): Socket {
        require(live) { "The session was recorded without a live socket" }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMillis)
            socket.getOutputStream().apply {
                write((token + "\n").toByteArray(Charsets.US_ASCII))
                flush()
            }
            return socket
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 1000

        fun parse(text: String, file: File? = null): SessionInfo {
            val json = Json.parse(text)
            fun string(key: String) = json[key] as? String ?: ""
            fun long(key: String) = (json[key] as? Number)?.toLong() ?: 0L
            return SessionInfo(
                file = file,
                pid = long("pid"),
                port = long("port").toInt(),
                token = string("token"),
                traceFile = string("traceFile").takeIf { it.isNotEmpty() }?.let(::File),
                taskPath = string("taskPath"),
                buildId = string("buildId"),
                command = string("command"),
                startedAt = long("startedAt"),
                ended = json["ended"] == true,
                paceable = json["paceable"] == true,
                liveFailed = json["liveFailed"] == true,
            )
        }

        fun read(file: File): SessionInfo = parse(file.readText(), file)
    }
}

enum class SessionState {
    RUNNING,

    ENDED,

    /** The descriptor has no end marker and the JVM is gone. */
    CRASHED,

    /**
     * The JVM exists but its socket doesn't answer: it is shutting down (the socket closes before the descriptor is
     * rewritten), or its live server is gone.
     */
    UNREACHABLE,
}

data class SessionEntry(val info: SessionInfo, val state: SessionState) {
    val running: Boolean get() = state == SessionState.RUNNING

    /** The process exists, reachable or not. */
    val jvmAlive: Boolean get() = state == SessionState.RUNNING || state == SessionState.UNREACHABLE

    val openable: Boolean get() = running || info.traceFile?.isFile == true
}

/**
 * Reads the shared user registry, including JVMs started by other builds.
 */
class SessionRegistry(val dir: File = defaultDir()) {
    /** Newest first. Skips unreadable or invalid descriptors. */
    fun sessions(): List<SessionInfo> =
        dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { SessionInfo.read(it) }.getOrNull() }
            .sortedByDescending { it.startedAt }

    /**
     * A crashed JVM leaves its descriptor without an end marker. So a session is [SessionState.RUNNING] only when
     * its process is alive and its socket, if it has one, answers.
     */
    fun entries(reachable: (SessionInfo) -> Boolean = ::isReachable): List<SessionEntry> = sessions().map { info ->
        val state = when {
            info.ended -> SessionState.ENDED
            !isAlive(info) -> SessionState.CRASHED
            !info.live || reachable(info) -> SessionState.RUNNING
            else -> SessionState.UNREACHABLE
        }
        SessionEntry(info, state)
    }

    /** The newest JVM that runs, else the newest run whose trace is still there. */
    fun latest(reachable: (SessionInfo) -> Boolean = ::isReachable): TraceSource? {
        val entries = entries(reachable)
        val latest = entries.firstOrNull { it.running } ?: entries.firstOrNull { it.openable } ?: return null
        return TraceSource.Session(latest.info)
    }

    /**
     * Deletes the descriptors of JVMs that are gone whose trace file is missing, and all but the newest [keep] of
     * the rest. A descriptor whose JVM exists is never deleted, reachable or not.
     */
    fun prune(keep: Int = KEEP, reachable: (SessionInfo) -> Boolean = ::isReachable): Int {
        var deleted = 0
        var kept = 0
        for (entry in entries(reachable)) {
            if (entry.jvmAlive) continue
            val forget = entry.info.traceFile?.isFile != true || ++kept > keep
            if (forget && entry.info.file?.delete() == true) deleted++
        }
        return deleted
    }

    companion object {
        const val KEEP = 50

        /** Must match the agent's default sessions directory in AgentConfig. */
        fun defaultDir(): File = File(System.getProperty("user.home", "."), ".coroutree/sessions")

        fun isAlive(session: SessionInfo): Boolean {
            val process = ProcessHandle.of(session.pid).orElse(null)?.takeIf { it.isAlive } ?: return false
            val started = process.info().startInstant().orElse(null) ?: return true
            // The process starts shortly before the agent records startedAt. A start well after or well before that
            // is another process with the same pid.
            return session.startedAt <= 0 || abs(started.toEpochMilli() - session.startedAt) <= PID_REUSE_MARGIN_MILLIS
        }

        // The probe doesn't authenticate, so the agent closes it without a replay of the trace.
        fun isReachable(session: SessionInfo): Boolean = session.live && try {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), session.port), 300) }
            true
        } catch (_: Exception) {
            false
        }

        private const val PID_REUSE_MARGIN_MILLIS = 60_000L
    }
}

sealed interface TraceSource {
    val title: String

    data class TraceFile(val file: File) : TraceSource {
        override val title: String get() = file.name
    }

    data class Session(val info: SessionInfo) : TraceSource {
        override val title: String get() = info.title
    }

    data class Demo(val live: Boolean) : TraceSource {
        override val title: String get() = "demo"
    }
}
