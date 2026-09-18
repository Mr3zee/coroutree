package kotlinx.coroutree.it

import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.TraceReader
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The live stream of a running JVM: discovery through the session descriptor, the token, replay from the start, the end. */
class LiveStreamTest {
    @Test
    fun liveStreamReplaysThePastFollowsThePresentAndEndsWithTheJvm() {
        val runDir = File(TestEnvironment.workDir, "LiveStream").apply { deleteRecursively(); mkdirs() }
        val sessions = File(runDir, "sessions")
        val traceFile = File(runDir, "trace.ctrace")
        val process = ProcessBuilder(
            TestEnvironment.java,
            "-javaagent:${TestEnvironment.agentJar}=trace.file=$traceFile,sessions.dir=$sessions,include=samples,task.path=:demo:run,build.id=b1",
            "-cp", TestEnvironment.samplesClasspath,
            "samples.InteractiveKt",
        ).directory(runDir).redirectErrorStream(true).redirectOutput(File(runDir, "output.txt")).start()
        try {
            val descriptor = File(sessions, "${process.pid()}.json")
            val session = awaitNotNull("session descriptor $descriptor") { descriptor.takeIf { it.exists() }?.readText() }
            assertEquals(process.pid().toString(), session.jsonValue("pid"))
            assertEquals("false", session.jsonValue("ended"))
            assertEquals(":demo:run", session.jsonValue("taskPath"))
            assertEquals("b1", session.jsonValue("buildId"))
            assertEquals(traceFile.path, session.jsonValue("traceFile"))
            val port = session.jsonValue("port").toInt()

            // A wrong token gets nothing.
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write("not the token\n".toByteArray())
                socket.soTimeout = 5000
                assertEquals(-1, socket.getInputStream().read(), "the server must hang up on a wrong token")
            }

            val live = mutableListOf<Frame>()
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write((session.jsonValue("token") + "\n").toByteArray())
                socket.soTimeout = 30_000
                val reader = TraceReader(socket.getInputStream())
                // The program is already blocked on stdin when we connect or gets there soon: both past and present.
                while (true) {
                    val frame = reader.next() ?: fail("stream ended before the program blocked on stdin")
                    live += frame
                    if (frame.event?.kind == EventKind.THREAD_BLOCKED && frame.event?.blockReason == BlockReason.IO) break
                }
                assertNotNull(live.first().header, "a live stream starts like a trace file")
                assertTrue(process.isAlive)

                process.outputStream.use { it.write("done\n".toByteArray()) }
                while (true) live += reader.next() ?: break
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), File(runDir, "output.txt").readText())

            val recorded = traceFile.inputStream().use { TraceReader(it).frames().toList() }
            assertEquals(recorded, live, "the live stream and the trace file are the same frames")
            assertTrue(live.any { it.event?.kind == EventKind.THREAD_UNBLOCKED })
            assertEquals("true", descriptor.readText().jsonValue("ended"))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun liveCanBeSwitchedOff() {
        val run = runUnderAgent(
            "samples.StructuredConcurrencyKt",
            runName = "LiveOff",
            agentOptions = mapOf("live" to "false", "sessions.dir" to File(TestEnvironment.workDir, "LiveOff/sessions").path),
        )
        assertEquals(0, run.exitCode, run.output)
        assertNull(File(TestEnvironment.workDir, "LiveOff/sessions").listFiles()?.firstOrNull(), "no session without live streaming")
    }

    private fun <T : Any> awaitNotNull(what: String, timeoutMillis: Long = 30_000, probe: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(20)
        }
        fail("Timed out waiting for $what")
    }
}
