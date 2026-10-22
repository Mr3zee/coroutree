package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.SessionRegistry
import kotlinx.coroutree.gui.source.SessionState
import kotlinx.coroutree.gui.source.TraceSource
import java.io.File
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Finding what to open in the registry when what is in it is not tidy: descriptors of other agent versions, sessions
 * whose JVM crashed and left the file saying "running", pids given out again, files that are not where they belong.
 */
class SessionDiscoveryTest {
    private val dir: File = Files.createTempDirectory("coroutree-discovery").toFile()
    private val registry = SessionRegistry(dir)

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val ownPid = ProcessHandle.current().pid()

    /** A pid that no process has: the first free one from 4,000,000 up, above what the platforms give out. */
    private val nobodysPid = generateSequence(4_000_000L) { it + 1 }.first { ProcessHandle.of(it).isEmpty }

    private fun session(pid: Long, startedAt: Long, port: Int, ended: Boolean = false, trace: String = "traces/$pid.ctrace") =
        """{"pid":$pid,"port":$port,"token":"t$pid","traceFile":"${File(dir, trace).path.replace("\\", "\\\\")}","taskPath":":run","buildId":"b","command":"Main","startedAt":$startedAt,"ended":$ended}"""

    private fun write(path: String, text: String): File = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }

    /** A port nobody listens on: it was ours a moment ago. */
    private fun deadPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    // ------------------------------------------------------------------ the descriptor

    @Test
    fun aDescriptorOfAnotherAgentVersionIsReadForWhatItHas() {
        // An older agent: fewer keys. A newer one: keys this GUI has never heard of.
        val old = SessionInfo.parse("""{"pid":7,"port":4000,"token":"x"}""")
        assertEquals(SessionInfo(null, pid = 7, port = 4000, token = "x", traceFile = null, taskPath = "", buildId = "", command = "", startedAt = 0, ended = false, paceable = false), old)
        val new = SessionInfo.parse("""{"pid":7,"port":4000,"token":"x","protocol":3,"host":"localhost","tls":false,"traceFile":"/t/x.ctrace","ended":true,"paceable":true}""")
        assertEquals(old.copy(traceFile = File("/t/x.ctrace"), ended = true, paceable = true), new)
        // Keys of the wrong type count as missing. They don't take the listing down.
        val odd = SessionInfo.parse("""{"pid":"7","port":null,"token":12,"traceFile":false,"ended":"true","paceable":1,"startedAt":1.0e3}""")
        assertEquals(SessionInfo(null, 0, 0, "", null, "", "", "", startedAt = 1000, ended = false, paceable = false), odd)
        assertFalse(odd.live, "no port: a run without a socket")
        assertTrue(old.live)
    }

    @Test
    fun aSessionIsCalledByItsTaskAndCommandOrElseByItsPid() {
        fun title(json: String) = SessionInfo.parse(json).title
        assertEquals(":app:run · demo.MainKt", title("""{"pid":7,"taskPath":":app:run","command":"demo.MainKt"}"""))
        assertEquals(":app:test", title("""{"pid":7,"taskPath":":app:test"}"""))
        assertEquals("demo.MainKt --fast", title("""{"pid":7,"command":"demo.MainKt --fast"}"""), "started without Gradle")
        assertEquals("pid 7", title("""{"pid":7}"""))
    }

    // ------------------------------------------------------------------ the directory

    @Test
    fun onlyFilesWhereTheAgentPutsThemAreListed() {
        write("1.json", session(1, 100, 1))
        write("deeper/3.json", session(3, 300, 1))
        write("4.json.tmp", session(4, 400, 1)) // still being written under another name
        File(dir, "5.json").mkdirs() // a directory that looks like a file
        write("6.json", "") // created, not yet written

        assertEquals(listOf(1L), registry.sessions().map { it.pid })
        assertEquals(File(dir, "1.json"), registry.sessions().single().file, "a session remembers its file")
    }

    @Test
    fun aDirectoryWithNothingInItOrThatIsNotThereOffersNothing() {
        for (empty in listOf(dir, File(dir, "missing"), write("a-file", "x"))) {
            val registry = SessionRegistry(empty)
            assertEquals(emptyList(), registry.sessions())
            assertEquals(emptyList(), registry.entries())
            assertNull(registry.latest())
            assertEquals(0, registry.prune())
        }
    }

    @Test
    fun sessionsAreListedNewestFirstWhateverTheyAre() {
        write("1.json", session(1, 300, 1))
        write("2.json", session(2, 500, 1, ended = true))
        write("3.json", session(3, 100, 1))
        write("4.json", session(4, 400, 1))
        assertEquals(listOf(2L, 4L, 1L, 3L), registry.sessions().map { it.pid })
    }

    // ------------------------------------------------------------------ running, ended, crashed

    /** A crashed JVM leaves its descriptor without an end marker. Only the process and its socket tell. */
    @Test
    fun aSessionRunsIfItsJvmIsAliveAndItsSocketAnswers() {
        val probes = LinkedBlockingQueue<Int>()
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    while (true) server.accept().use { probes += it.getInputStream().read() }
                }
            }
            val now = System.currentTimeMillis()
            write("alive.json", session(ownPid, now, server.localPort))
            write("gone.json", session(nobodysPid, now - 1, server.localPort)) // the port answers, but it is somebody else's now
            write("hung-up.json", session(ownPid, now - 2, deadPort())) // the JVM is alive, the agent's socket is closed: shutting down, or worse
            write("ended.json", session(ownPid, now - 3, server.localPort, ended = true))
            write("silent.json", session(ownPid, now - 4, 0)) // started with live=false: running, nothing to connect to

            val entries = registry.entries()
            assertEquals(listOf("alive", "gone", "hung-up", "ended", "silent"), entries.map { it.info.file!!.nameWithoutExtension }, "newest first")
            assertEquals(listOf(SessionState.RUNNING, SessionState.CRASHED, SessionState.UNREACHABLE, SessionState.ENDED, SessionState.RUNNING), entries.map { it.state })
            assertEquals(-1, probes.poll(10, TimeUnit.SECONDS), "the probe hangs up without a word: it does not authenticate, the agent starts no replay")
            assertNull(probes.poll(100, TimeUnit.MILLISECONDS), "one probe: a dead JVM's port is never tried, nor an ended session's, nor a session without one")

            val latest = assertIs<TraceSource.Session>(registry.latest(), "the newest session that runs")
            assertEquals(server.localPort, latest.info.port)
        }
    }

    @Test
    fun aPidGivenOutAgainIsNotTheJvmOfTheDescriptor() {
        val processStart = ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(0L)
        val mine = SessionInfo.parse(session(ownPid, System.currentTimeMillis(), 1))
        assertTrue(SessionRegistry.isAlive(mine))
        assertTrue(SessionRegistry.isAlive(mine.copy(startedAt = 0)), "an agent that did not say when: believed")
        if (processStart > 0) {
            assertFalse(SessionRegistry.isAlive(mine.copy(startedAt = processStart - 3_600_000)), "a JVM that started an hour after the descriptor was written is another one")
            assertFalse(SessionRegistry.isAlive(mine.copy(startedAt = processStart + 3_600_000)), "a JVM that started an hour before the agent took the time is another one too")
        }
        assertFalse(SessionRegistry.isAlive(mine.copy(pid = nobodysPid)))
    }

    @Test
    fun latestFallsBackToTheNewestRunWhoseTraceIsThere() {
        write("1.json", session(1, 100, 1, trace = "traces/old.ctrace"))
        write("2.json", session(2, 200, 1, ended = true, trace = "traces/new.ctrace"))
        write("3.json", session(3, 300, 1, ended = true, trace = "traces/gone.ctrace"))
        write("traces/old.ctrace", "x")
        write("traces/new.ctrace", "x")
        val latest = assertIs<TraceSource.Session>(registry.latest(reachable = { false }))
        assertEquals(2L, latest.info.pid, "the newest with a trace; 3's trace is gone, 1 crashed and is older")
        assertEquals("new.ctrace", latest.info.traceFile?.name)
        File(dir, "traces").deleteRecursively()
        assertNull(registry.latest(reachable = { false }), "nothing to open")
    }

    @Test
    fun withoutAnyJvmAnsweringNothingRuns() {
        write("1.json", session(nobodysPid, 100, deadPort()))
        assertEquals(listOf(SessionState.CRASHED), registry.entries().map { it.state })
        assertTrue(registry.sessions().none { it.ended }, "the file still says it runs")
        assertFalse(registry.entries().single().openable, "and the trace file is not there either")
    }

    // ------------------------------------------------------------------ pruning

    @Test
    fun pruningForgetsRunsWithoutATraceAndAllButTheNewestFew() {
        val now = System.currentTimeMillis()
        write("running.json", session(ownPid, now, 1))
        write("running-no-trace.json", session(ownPid, now - 1, 1, trace = "traces/not-yet.ctrace"))
        write("unreachable.json", session(ownPid, now - 2, 1, trace = "traces/unreachable.ctrace"))
        write("traces/unreachable.ctrace", "x")
        for (i in 1..5) {
            write("ended-$i.json", session(100L + i, 100L + i, 1, ended = true, trace = "traces/$i.ctrace"))
            write("traces/$i.ctrace", "x")
        }
        write("ended-no-trace.json", session(200, 200, 1, ended = true, trace = "traces/cleaned.ctrace"))
        write("crashed.json", session(nobodysPid, 50, 1, trace = "traces/crashed.ctrace"))
        write("traces/crashed.ctrace", "x")

        val reachable = { info: SessionInfo -> info.file?.name != "unreachable.json" }
        assertEquals(4, registry.prune(keep = 3, reachable))
        assertEquals(
            setOf("running.json", "running-no-trace.json", "unreachable.json", "ended-5.json", "ended-4.json", "ended-3.json"),
            dir.listFiles()!!.filter { it.isFile }.map { it.name }.toSet(),
            "a JVM that exists is never touched, whatever its trace or socket; of the rest the three newest with a trace stay",
        )
        assertEquals(0, registry.prune(keep = 3, reachable), "nothing more to forget")
    }
}
