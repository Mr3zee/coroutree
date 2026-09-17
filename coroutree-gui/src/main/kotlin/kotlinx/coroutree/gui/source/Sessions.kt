package kotlinx.coroutree.gui.source

import kotlinx.coroutree.model.TraceFormat
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** The agent's session file: how to reach an instrumented JVM while it runs, and where its trace is. */
data class SessionInfo(
    val file: File?,
    val pid: Long,
    val port: Int,
    val token: String,
    val traceFile: File?,
    val taskPath: String,
    val buildId: String,
    val command: String,
    val startedAt: Long,
    val ended: Boolean,
) {
    val title: String get() = listOf(taskPath, command).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "pid $pid" }

    /**
     * Connects and authenticates. What comes back is a whole trace from its first byte, growing until the JVM exits.
     */
    fun connect(timeoutMillis: Int = CONNECT_TIMEOUT_MILLIS): Socket {
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
            val json = FlatJson.parse(text)
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
            )
        }

        fun read(file: File): SessionInfo = parse(file.readText(), file)
    }
}

/** A build's `build/coroutree` directory. */
class CoroutreeDir(val dir: File) {
    /** Newest first. Files that cannot be parsed (half-written, foreign) are skipped. */
    fun sessions(): List<SessionInfo> =
        filesTwoLevelsDown("sessions", "json")
            .mapNotNull { runCatching { SessionInfo.read(it) }.getOrNull() }
            .sortedByDescending { it.startedAt }

    /** Newest first. */
    fun traces(): List<File> = filesTwoLevelsDown("traces", TraceFormat.FILE_EXTENSION).sortedByDescending { it.lastModified() }

    /**
     * What `--open-latest` opens: the newest session that is still running and [reachable], or else the newest trace.
     */
    fun latest(reachable: (SessionInfo) -> Boolean = ::isReachable): TraceSource? {
        sessions().firstOrNull { !it.ended && reachable(it) }?.let { return TraceSource.Session(it) }
        return traces().firstOrNull()?.let { TraceSource.TraceFile(it) }
    }

    private fun filesTwoLevelsDown(child: String, extension: String): List<File> =
        File(dir, child).listFiles { f -> f.isDirectory }.orEmpty()
            .flatMap { it.listFiles { f -> f.isFile && f.extension == extension }.orEmpty().asList() }

    companion object {
        // A crashed JVM leaves a session file that still says "running"; only a connection tells.
        // The probe does not authenticate, so the agent drops it without starting a replay.
        fun isReachable(session: SessionInfo): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), session.port), 300) }
            true
        } catch (_: Exception) {
            false
        }
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
