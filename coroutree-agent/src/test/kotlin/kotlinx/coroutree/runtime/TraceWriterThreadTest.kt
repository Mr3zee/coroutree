package kotlinx.coroutree.runtime

import kotlinx.coroutree.runtime.TraceEvent.DiagnosticDef
import kotlinx.coroutree.runtime.TraceEvent.PaceDef
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * The one thread that writes the trace file: what hooks hand over from any number of threads is in the file, whole,
 * a moment later, and to its last byte when the JVM goes down.
 */
@Timeout(60)
internal class TraceWriterThreadTest {
    @TempDir
    lateinit var dir: File

    private val writers = ArrayList<TraceWriterThread>()

    private fun writer(file: File): TraceWriterThread {
        val writer = TraceWriterThread(file, CONFIG, 42)
        writers.add(writer)
        return writer
    }

    @AfterEach
    fun stopWriters() {
        for (writer in writers) if (writer.isAlive) writer.shutdown()
    }

    @Test
    fun theFileStartsWithTheHeaderBeforeTheWriterEvenRuns() {
        // Live clients and a GUI that opens the file find a trace from the first moment, in directories nobody made.
        val file = File(dir, "traces/b42/run.ctrace")
        writer(file)
        val frames = TraceFiles.read(file)
        assertEquals(1, frames.size)
        val header = frames[0].header
        assertNotNull(header)
        assertEquals("writer-test", header!!.buildId)
        assertEquals(42, header.startedAtEpochMillis)
    }

    @Test
    fun everythingHandedOverByRacingThreadsIsInTheFileWithNoNumberMissing() {
        val file = File(dir, "race.ctrace")
        val writer = writer(file)
        writer.start()

        val threads = 8
        val perThread = 5000
        val seq = AtomicLong()
        val go = CountDownLatch(1)
        val emitters = (0 until threads).map { thread ->
            Thread {
                go.await()
                repeat(perThread) {
                    // What Tracer.emit does: take a number, then enqueue. Another thread may get in between; some help it to.
                    val number = seq.incrementAndGet()
                    if ((number and 15L) == thread.toLong()) Thread.yield()
                    // A few distinct frames shared by all: interning must not be confused by the interleaving.
                    writer.enqueue(event(number, StackFrameRef("com.acme.Worker", "step${number % 5}", "Worker.kt", (number % 5).toInt())))
                }
            }.apply { start() }
        }
        go.countDown()
        for (emitter in emitters) emitter.join()
        writer.shutdown()

        assertTrue(writer.isClosed())
        val seen = HashSet<Long>()
        val defined = HashSet<Int>()
        var last = 0L
        for (frame in TraceFiles.read(file)) {
            frame.stackFrame?.let {
                assertEquals(defined.size + 1, it.id, "frame ids start at 1 and are dense")
                defined.add(it.id)
            }
            val event = frame.event ?: continue
            assertTrue(seen.add(event.seq), "event ${event.seq} is in the file twice")
            assertTrue(defined.containsAll(event.stack), "a definition precedes the first event that refers to it")
            last = maxOf(last, event.seq)
        }
        assertEquals(threads * perThread, seen.size)
        assertEquals((threads * perThread).toLong(), last, "dense: that many events, and the largest number is their count")
        assertEquals(5, defined.size)
    }

    @Test
    fun aBatchIsWrittenInSequenceOrderWithSettingsBehindTheEventTheyWereMadeAfter() {
        val file = File(dir, "batch.ctrace")
        val writer = writer(file)
        // All of it is queued before the writer starts: one batch, whatever the machine does.
        writer.enqueue(pace(0, 5)) // the configured setting, made before the first event
        writer.enqueue(event(2))
        writer.enqueue(pace(2, 22))
        writer.enqueue(event(1)) // a thread that took its number first and got here second
        writer.enqueue(pace(2, 23)) // two changes after the same event keep the order they were made in
        writer.enqueue(DiagnosticDef(Wire.INFO, "something about the capture"))
        writer.enqueue(event(4))
        writer.enqueue(event(3))
        writer.enqueue(pace(3, 33))
        writer.start()
        writer.shutdown()

        val frames = TraceFiles.read(file)
        val order = frames.mapNotNull { frame ->
            frame.event?.let { "event ${it.seq}" } ?: frame.pace?.let { "pace ${it.intervalNanos} after ${it.afterSeq}" }
        }
        assertEquals(
            listOf("pace 5 after 0", "event 1", "event 2", "pace 22 after 2", "pace 23 after 2", "event 3", "pace 33 after 3", "event 4"),
            order
        )
        assertEquals(1, frames.count { it.diagnostic != null })
    }

    @Test
    fun whatWasHandedOverIsInTheFileAMomentLaterWithoutAnybodyAsking() {
        // A JVM that dies abruptly leaves a trace that is at most a moment behind: the writer flushes when it runs dry.
        val file = File(dir, "flush.ctrace")
        val writer = writer(file)
        writer.start()
        for (round in 1..3) {
            writer.enqueue(event(round.toLong()))
            val deadline = System.nanoTime() + 10_000_000_000L
            while (TraceFiles.read(file).size < 1 + round && System.nanoTime() < deadline) Thread.sleep(2)
            assertEquals(1 + round, TraceFiles.read(file).size, "the header and $round events")
        }
        assertFalse(writer.isClosed())
    }

    @Test
    fun shutdownWritesWhatIsQueuedClosesTheFileAndTakesNothingMore() {
        val file = File(dir, "shutdown.ctrace")
        val writer = writer(file)
        writer.start()
        for (i in 1..20_000) writer.enqueue(event(i.toLong())) // more than one batch of the writer

        writer.shutdown()

        assertFalse(writer.isAlive)
        assertTrue(writer.isClosed(), "a live client that sees this reads to the end of the file and is done")
        val length = file.length()
        assertEquals(20_001, TraceFiles.read(file).size)

        writer.enqueue(event(20_001)) // a hook of a thread that is still running while the JVM goes down
        writer.shutdown() // and a second request to stop: neither throws, neither changes the file
        assertEquals(length, file.length())
    }

    @Test
    fun whenTheWriterFailsTracingGoesOffInsteadOfTheProgram() {
        // The switch the agent has for this test: the writer fails as it would on a full disk.
        System.setProperty("coroutree.debug.writerFailsAfterMillis", "0")
        val writer = try {
            writer(File(dir, "failing.ctrace"))
        } finally {
            System.clearProperty("coroutree.debug.writerFailsAfterMillis")
        }
        Tracer.active = true
        try {
            writer.enqueue(event(1))
            writer.start()
            writer.join(10_000)
            assertFalse(writer.isAlive)
            assertFalse(Tracer.active, "hooks go quiet, and whoever is held at the gate goes, when the trace cannot be written")
            assertTrue(writer.isClosed(), "live clients are not left waiting for a file that will not grow")
            writer.enqueue(event(2)) // hooks that were already past the check: nothing piles up, nothing throws
        } finally {
            Tracer.active = false
        }
    }

    companion object {
        private val CONFIG: AgentConfig = AgentConfig.parse("pace=false,build.id=writer-test")

        private fun event(seq: Long, vararg stack: StackFrameRef): TraceEvent {
            val event = TraceEvent(seq % 7 + 1, Wire.RESUMED, 1)
            event.seq = seq
            event.stack = arrayOf(*stack)
            return event
        }

        private fun pace(afterSeq: Long, interval: Long): PaceDef {
            val def = PaceDef()
            def.afterSeq = afterSeq
            def.intervalNanos = interval
            return def
        }
    }
}
