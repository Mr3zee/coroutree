package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.source.Json
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.SessionRegistry
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        assertEquals(mapOf("a" to "x\nyA", "b" to -12L, "c" to 1.5, "d" to null, "e" to true), Json.parse(""" { "a" : "x\nyA", "b": -12, "c": 1.5, "d": null, "e": true } """))
        assertEquals(emptyMap(), Json.parse("{}"))
        assertEquals(mapOf("a" to mapOf("b" to 1L)), Json.parse("""{"a":{"b":1}}"""))
        assertFailsWith<Json.ParseException> { Json.parse("""{"a":1""") }
    }

    @Test
    fun listsSessionsNewestFirstAndSkipsGarbage() {
        File(dir, "1.json").writeText(sessionJson(1, 100, ended = true))
        File(dir, "2.json").writeText(sessionJson(2, 200, ended = false))
        File(dir, "3.json").writeText("{ half-written")
        File(dir, "notes.txt").writeText("x")
        assertEquals(listOf(2L, 1L), SessionRegistry(dir).sessions().map { it.pid })
    }

    @Test
    fun theDefaultDirectoryIsTheAgentsToo() {
        // AgentConfig.defaultSessionsDir in the agent, spelled out again: the two share no code.
        assertEquals(File(System.getProperty("user.home"), ".coroutree/sessions"), SessionRegistry.defaultDir())
        assertEquals(SessionRegistry.defaultDir(), SessionRegistry().dir)
    }
}
