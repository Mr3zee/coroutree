package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.source.CoroutreeDir
import kotlinx.coroutree.gui.source.FlatJson
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.TraceSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionsTest {
    private val dir: File = Files.createTempDirectory("coroutree-gui-test").toFile().also { it.deleteOnExit() }

    private fun sessionJson(pid: Long, startedAt: Long, ended: Boolean, port: Int = 5555) =
        """{"pid":$pid,"port":$port,"token":"té\"k","traceFile":"/abs/x.ctrace","taskPath":":app:run","buildId":"b1","command":"samples.MainKt","startedAt":$startedAt,"ended":$ended}"""

    @Test
    fun parsesTheAgentsSessionFile() {
        val info = SessionInfo.parse(sessionJson(123, 1726000000000, ended = false))
        assertEquals(123, info.pid)
        assertEquals(5555, info.port)
        assertEquals("té\"k", info.token)
        assertEquals(File("/abs/x.ctrace"), info.traceFile)
        assertEquals(":app:run · samples.MainKt", info.title)
        assertEquals(false, info.ended)
    }

    @Test
    fun flatJsonHandlesEscapesAndRejectsNesting() {
        assertEquals(mapOf("a" to "x\nyA", "b" to -12L, "c" to 1.5, "d" to null, "e" to true), FlatJson.parse(""" { "a" : "x\nyA", "b": -12, "c": 1.5, "d": null, "e": true } """))
        assertEquals(emptyMap(), FlatJson.parse("{}"))
        assertFailsWith<FlatJson.ParseException> { FlatJson.parse("""{"a":{"b":1}}""") }
        assertFailsWith<FlatJson.ParseException> { FlatJson.parse("""{"a":1""") }
    }

    @Test
    fun listsSessionsAndTracesNewestFirstAndSkipsGarbage() {
        File(dir, "sessions/b1").mkdirs()
        File(dir, "sessions/b2").mkdirs()
        File(dir, "traces/b1").mkdirs()
        File(dir, "sessions/b1/1.json").writeText(sessionJson(1, 100, ended = true))
        File(dir, "sessions/b2/2.json").writeText(sessionJson(2, 200, ended = false))
        File(dir, "sessions/b2/3.json").writeText("{ half-written")
        val older = File(dir, "traces/b1/run-1.ctrace").apply { writeText("x"); setLastModified(1_000_000) }
        val newer = File(dir, "traces/b1/run-2.ctrace").apply { writeText("x"); setLastModified(2_000_000) }
        File(dir, "traces/b1/notes.txt").writeText("x")

        val coroutreeDir = CoroutreeDir(dir)
        assertEquals(listOf(2L, 1L), coroutreeDir.sessions().map { it.pid })
        assertEquals(listOf(newer, older), coroutreeDir.traces())
    }

    @Test
    fun latestPrefersAReachableRunningSession() {
        File(dir, "sessions/b1").mkdirs()
        File(dir, "traces/b1").mkdirs()
        File(dir, "sessions/b1/1.json").writeText(sessionJson(1, 300, ended = true))
        File(dir, "sessions/b1/2.json").writeText(sessionJson(2, 200, ended = false))
        File(dir, "sessions/b1/3.json").writeText(sessionJson(3, 100, ended = false))
        val trace = File(dir, "traces/b1/run.ctrace").apply { writeText("x") }
        val coroutreeDir = CoroutreeDir(dir)

        val live = coroutreeDir.latest(reachable = { it.pid == 3L })
        assertTrue(live is TraceSource.Session && live.info.pid == 3L, "a crashed session (2) is skipped, an ended one (1) never probed")
        assertEquals(TraceSource.TraceFile(trace), coroutreeDir.latest(reachable = { false }))
        assertNull(CoroutreeDir(File(dir, "nothing-here")).latest(reachable = { true }))
    }
}
