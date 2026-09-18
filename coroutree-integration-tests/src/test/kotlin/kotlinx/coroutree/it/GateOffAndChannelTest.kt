package kotlinx.coroutree.it

import kotlinx.coroutree.model.PaceDef
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Execution control switched off, which has to mean that its machinery is not there (DESIGN §3.1 "Switched off",
 * §10 "Off"), and the control channel as a channel: what it does with what is not a command, and with several clients.
 */
@Timeout(180)
class GateOffAndChannelTest {
    @Test
    fun switchedOffThereIsNoGateInTheJvmAndCommandsDoNothing() {
        startPaceControl("Off", mapOf("pace" to "false")).use { program ->
            assertFalse(program.session.paceable, program.session.text)
            val client = program.connect()
            program.awaitOutput("ready")
            client.send("pause")
            client.send("step 3")
            client.send("pace 1000000000")
            client.assertGoesOn("the program runs whatever it is sent", atLeast = 200)

            // Not idle: absent. No gate object, no settings, no time kept per node, no thread that reads commands.
            val histogram = program.started.jcmd("GC.class_histogram")
            for (absent in listOf("kotlinx.coroutree.runtime.Pace ", "kotlinx.coroutree.runtime.PaceScope", "kotlinx.coroutree.runtime.Pace\$NodeRef")) {
                assertFalse(histogram.lines().any { absent in "$it " }, "instances of ${absent.trim()} in a JVM with pace=false")
            }
            assertTrue(histogram.lines().any { "kotlinx.coroutree.runtime.ThreadNode" in it }, "the histogram does show the agent's objects:\n${histogram.take(2000)}")
            val threads = program.started.threadDump()
            assertTrue("coroutree-live-client" in threads, "the stream is served as ever")
            assertFalse("coroutree-live-control" in threads, "but nobody reads what a client sends")

            val run = program.stopAndAwaitExit()
            assertEquals(false, run.snapshot.header?.paceable)
            assertTrue(run.snapshot.paceChanges.isEmpty())
            assertTraceIsFreeOfExecutionControl(run.traceFile)
        }
    }

    @Test
    fun switchedOnTheSameProbesFindTheGate() {
        // The counterpart of the test above, so that it cannot pass for the wrong reason.
        startPaceControl("On-probes").use { program ->
            val client = program.connect()
            program.awaitOutput("ready")
            client.command("pace 1000")
            val histogram = program.started.jcmd("GC.class_histogram")
            assertTrue(histogram.lines().any { "kotlinx.coroutree.runtime.Pace " in "$it " }, histogram.take(3000))
            assertTrue("coroutree-live-control" in program.started.threadDump())
            client.command("pace 0")
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun anUnpacedRunWithoutLiveSocketIsByteForByteFreeOfExecutionControl() {
        // The runs every other test class looks at: live=false, no pace configured. No gate, and the capture is M1's.
        for (sample in listOf("StructuredConcurrency", "Threads", "VirtualThreads")) {
            val run = SampleRuns[sample]
            assertEquals(false, run.snapshot.header?.paceable, sample)
            assertTraceIsFreeOfExecutionControl(run.traceFile)
        }
    }

    @Test
    fun whatIsNotACommandChangesNothingAndACommandMayComeInPieces() {
        startPaceControl("Channel-garbage").use { program ->
            val client = program.connect()
            program.awaitOutput("ready")
            client.send("")
            client.send("PAUSE")
            client.send("pause everything")
            client.send("step -1")
            client.send("pace 1e9")
            client.send("x".repeat(5_000) + " pause") // the tail of a line that is too long is not a command either
            client.sendRaw(ByteArray(300) { (it * 7).toByte() } + '\n'.code.toByte())
            client.sendRaw(byteArrayOf(0, 0, 0, '\n'.code.toByte()))
            client.assertGoesOn("the program runs on", atLeast = 100)
            assertEquals(listOf(PaceDef.Reason.CONFIG), client.paceDefs.map { it.reason })

            client.sendRaw("pa".toByteArray())
            Thread.sleep(100)
            client.sendRaw("use\r\n".toByteArray()) // and a Windows line end
            val paused = client.awaitPace("the pause") { it.reason == PaceDef.Reason.CONTROLLER }
            assertTrue(paused.paused)
            client.command("resume")
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun aLineThatNeverEndsCostsItsSenderTheChannelAndNobodyElseAnything() {
        startPaceControl("Channel-endless").use { program ->
            val rude = program.connect()
            val polite = program.connect()
            program.awaitOutput("ready")
            polite.command("pause")
            polite.awaitQuiet()
            // A controller that goes mad is a controller that is gone — but it never was one, so nothing reverts.
            runCatching { repeat(40) { rude.sendRaw(ByteArray(8192) { 'a'.code.toByte() }) } }
            polite.assertStandsStill("still paused", millis = 500)
            polite.command("resume")
            polite.assertGoesOn("and the channel of the other client works")
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun everyClientSeesTheSameSettingsLateJoinersIncluded() {
        startPaceControl("Channel-agree").use { program ->
            val one = program.connect()
            val two = program.connect()
            program.awaitOutput("ready")
            val left = one.nodeNamed("left")
            one.command("pace 3000000")
            two.command("pause", scope = left)
            one.command("step 2", scope = left)
            two.command("inherit", scope = left)
            one.command("pace 0")
            // Last command wins, and both learn it from the stream, not from what they sent.
            two.await("what the other client did") { frames -> frames.count { it.pace != null } >= 6 }
            val late = program.connect()
            late.await("the history of the gate") { frames -> frames.count { it.pace != null } >= 6 }
            assertEquals(one.paceDefs.take(6), two.paceDefs.take(6))
            assertEquals(one.paceDefs.take(6), late.paceDefs.take(6))
            assertEquals(
                listOf("CONFIG 0", "CONTROLLER 0", "CONTROLLER $left", "CONTROLLER $left", "CONTROLLER $left", "CONTROLLER 0"),
                late.paceDefs.take(6).map { "${it.reason} ${it.scopeNodeId}" },
            )
            assertTrue(late.snapshot().pace.nodes.isEmpty())
            program.stopAndAwaitExit()
        }
    }

    @Test
    fun aRecordedTraceTellsWhereTheSettingsChanged() {
        startPaceControl("Channel-after-seq").use { program ->
            val client = program.connect()
            program.awaitOutput("ready")
            client.assertGoesOn("the program runs", atLeast = 50)
            val paused = client.command("pause")
            client.awaitQuiet()
            val resumed = client.command("resume")
            client.assertGoesOn("the program runs", atLeast = 50)
            val run = program.stopAndAwaitExit()
            val events = run.snapshot.events
            assertTrue(paused.afterSeq in 1..events.size.toLong())
            assertTrue(resumed.afterSeq >= paused.afterSeq)
            // The pause lies in the gap it made: the event after it waited for the resume.
            val next = events.first { it.seq > resumed.afterSeq }
            assertTrue(next.timeNanos >= resumed.timeNanos - 1_000_000, "#${next.seq} at ${next.timeNanos} before the resume at ${resumed.timeNanos}")
            assertTrue(events.first { it.seq == paused.afterSeq }.timeNanos <= paused.timeNanos + 1_000_000)
        }
    }
}

/** Output of `jcmd <pid> <command>`. */
fun AgentProcess.jcmd(command: String): String {
    val jcmd = File(File(TestEnvironment.java).parentFile, "jcmd").path
    val process = ProcessBuilder(jcmd, this.process.pid().toString(), command).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().readText()
    process.waitFor(30, TimeUnit.SECONDS)
    return text
}

/**
 * The trace holds nothing that execution control adds to the format: no `Frame.pace` (5), no `TraceHeader.paceable`
 * (11), no `Event.held_nanos` (15) or `Event.same_step` (16). Read from the bytes, without the model.
 */
fun assertTraceIsFreeOfExecutionControl(trace: File) {
    val bytes = trace.readBytes()
    var position = 8
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[position++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
    /** Field numbers of the message in [from, to), with the ranges of its length-delimited fields. */
    fun fields(from: Int, to: Int): List<Triple<Int, Int, Int>> {
        val result = ArrayList<Triple<Int, Int, Int>>()
        position = from
        while (position < to) {
            val tag = varint().toInt()
            when (tag and 7) {
                0 -> { varint(); result += Triple(tag ushr 3, 0, 0) }
                1 -> { position += 8; result += Triple(tag ushr 3, 0, 0) }
                5 -> { position += 4; result += Triple(tag ushr 3, 0, 0) }
                2 -> {
                    val length = varint().toInt()
                    result += Triple(tag ushr 3, position, position + length)
                    position += length
                }
                else -> error("wire type ${tag and 7} at $position")
            }
        }
        return result
    }
    var frames = 0
    while (position < bytes.size) {
        val length = varint().toInt()
        val end = position + length
        for ((field, from, to) in fields(position, end)) {
            assertTrue(field != 5, "a PaceDef frame in ${trace.name}")
            val inner = fields(from, to).map { it.first }
            if (field == 1) assertFalse(11 in inner, "header.paceable in ${trace.name}")
            if (field == 2) assertFalse(15 in inner || 16 in inner, "held_nanos or same_step in ${trace.name}")
        }
        position = end
        frames++
    }
    assertTrue(frames > 10)
}
