package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.source.CoroutreeDir
import kotlinx.coroutree.gui.source.SessionInfo
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
 * Finding what to open in a build's coroutree directory when what is in it is not tidy: descriptors of other agent
 * versions, sessions whose JVM crashed and left the file saying "running", files that are not where they belong.
 */
class SessionDiscoveryTest {
    private val dir: File = Files.createTempDirectory("coroutree-discovery").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun session(pid: Long, startedAt: Long, port: Int, ended: Boolean = false) =
        """{"pid":$pid,"port":$port,"token":"t$pid","traceFile":"${File(dir, "traces/b/$pid.ctrace").path.replace("\\", "\\\\")}","taskPath":":run","buildId":"b","command":"Main","startedAt":$startedAt,"ended":$ended}"""

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
        // Keys of the wrong type are as good as missing; they do not take the listing down.
        val odd = SessionInfo.parse("""{"pid":"7","port":null,"token":12,"traceFile":false,"ended":"true","paceable":1,"startedAt":1.0e3}""")
        assertEquals(SessionInfo(null, 0, 0, "", null, "", "", "", startedAt = 1000, ended = false, paceable = false), odd)
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
        write("sessions/b1/1.json", session(1, 100, 1))
        write("sessions/2.json", session(2, 200, 1)) // not in a build's directory
        write("sessions/b1/deeper/3.json", session(3, 300, 1))
        write("sessions/b1/4.json.tmp", session(4, 400, 1)) // still being written under another name
        File(dir, "sessions/b1/5.json").mkdirs() // a directory that looks like a file
        write("sessions/b1/6.json", "") // created, not yet written
        write("traces/b1/a.ctrace", "x")
        write("traces/a.ctrace", "x")
        write("traces/b1/deeper/b.ctrace", "x")
        write("traces/b1/c.ctrace.part", "x")
        File(dir, "traces/b1/d.ctrace").mkdirs()

        val coroutreeDir = CoroutreeDir(dir)
        assertEquals(listOf(1L), coroutreeDir.sessions().map { it.pid })
        assertEquals(File(dir, "sessions/b1/1.json"), coroutreeDir.sessions().single().file, "a session remembers its file")
        assertEquals(listOf(File(dir, "traces/b1/a.ctrace")), coroutreeDir.traces())
    }

    @Test
    fun aDirectoryWithNothingInItOrThatIsNotThereOffersNothing() {
        for (empty in listOf(dir, File(dir, "missing"), write("a-file", "x"))) {
            val coroutreeDir = CoroutreeDir(empty)
            assertEquals(emptyList(), coroutreeDir.sessions())
            assertEquals(emptyList(), coroutreeDir.traces())
            assertNull(coroutreeDir.latest())
        }
    }

    @Test
    fun sessionsOfAllBuildsAreListedTogetherNewestFirst() {
        write("sessions/b1/1.json", session(1, 300, 1))
        write("sessions/b2/2.json", session(2, 500, 1, ended = true))
        write("sessions/b3/3.json", session(3, 100, 1))
        write("sessions/b1/4.json", session(4, 400, 1))
        assertEquals(listOf(2L, 4L, 1L, 3L), CoroutreeDir(dir).sessions().map { it.pid })
    }

    // ------------------------------------------------------------------ --open-latest

    /** "A crashed JVM leaves a session file that still says 'running'; only a connection tells." */
    @Test
    fun latestTellsALiveSessionFromACrashedOneByConnecting() {
        val trace = write("traces/b/run.ctrace", "x")
        val probes = LinkedBlockingQueue<Int>()
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    while (true) server.accept().use { probes += it.getInputStream().read() }
                }
            }
            write("sessions/b/crashed.json", session(1, 300, deadPort()))
            write("sessions/b/alive.json", session(2, 200, server.localPort))
            write("sessions/b/older.json", session(3, 100, server.localPort))
            write("sessions/b/ended.json", session(4, 400, server.localPort, ended = true))

            val latest = assertIs<TraceSource.Session>(CoroutreeDir(dir).latest(), "the newest session that answers")
            assertEquals(2L, latest.info.pid)
            assertEquals(-1, probes.poll(10, TimeUnit.SECONDS), "the probe hangs up without a word: it does not authenticate, the agent starts no replay")
            assertNull(probes.poll(), "one probe: the ended session is never tried, nor anything older than the one that answered")
        }
        // The same directory once that JVM is gone too.
        assertEquals(TraceSource.TraceFile(trace), CoroutreeDir(dir).latest(), "nobody answers: the newest trace")
    }

    @Test
    fun withoutASessionThatAnswersLatestIsTheNewestTrace() {
        val older = write("traces/b1/old.ctrace", "x").apply { setLastModified(1_000_000_000) }
        val newer = write("traces/b2/new.ctrace", "x").apply { setLastModified(2_000_000_000) }
        write("sessions/b1/1.json", session(1, 100, deadPort()))
        val coroutreeDir = CoroutreeDir(dir)
        assertEquals(TraceSource.TraceFile(newer), coroutreeDir.latest())
        assertEquals("new.ctrace", coroutreeDir.latest()?.title)
        assertEquals(listOf(newer, older), coroutreeDir.traces(), "across builds")

        newer.delete()
        older.delete()
        assertNull(coroutreeDir.latest(), "a crashed session and no trace: nothing to open")
        assertFalse(CoroutreeDir.isReachable(coroutreeDir.sessions().single()))
        assertTrue(coroutreeDir.sessions().none { it.ended }, "the file still says it runs")
    }
}
