package kotlinx.coroutree.runtime;

import kotlinx.coroutree.model.Event;
import kotlinx.coroutree.model.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thread that writes the trace file: what hooks hand over from any number of threads is in the file, whole,
 * a moment later, and to its last byte when the JVM goes down.
 */
@Timeout(60)
class TraceWriterThreadTest {
    private static final AgentConfig CONFIG = AgentConfig.parse("pace=false,build.id=writer-test");

    @TempDir
    File dir;

    private final List<TraceWriterThread> writers = new ArrayList<>();

    private TraceWriterThread writer(File file) throws IOException {
        TraceWriterThread writer = new TraceWriterThread(file, CONFIG, 42);
        writers.add(writer);
        return writer;
    }

    @AfterEach
    void stopWriters() {
        for (TraceWriterThread writer : writers) if (writer.isAlive()) writer.shutdown();
    }

    private static TraceEvent event(long seq, StackFrameRef... stack) {
        TraceEvent event = new TraceEvent(seq % 7 + 1, Wire.RESUMED, 1);
        event.seq = seq;
        event.stack = stack;
        return event;
    }

    private static TraceEvent.PaceDef pace(long afterSeq, long interval) {
        TraceEvent.PaceDef def = new TraceEvent.PaceDef();
        def.afterSeq = afterSeq;
        def.intervalNanos = interval;
        return def;
    }

    @Test
    void theFileStartsWithTheHeaderBeforeTheWriterEvenRuns() throws IOException {
        // Live clients and a GUI that opens the file find a trace from the first moment, in directories nobody made.
        File file = new File(dir, "traces/b42/run.ctrace");
        writer(file);
        List<Frame> frames = TraceFiles.read(file);
        assertEquals(1, frames.size());
        assertNotNull(frames.get(0).getHeader());
        assertEquals("writer-test", frames.get(0).getHeader().getBuildId());
        assertEquals(42, frames.get(0).getHeader().getStartedAtEpochMillis());
    }

    @Test
    void everythingHandedOverByRacingThreadsIsInTheFileWithNoNumberMissing() throws Exception {
        File file = new File(dir, "race.ctrace");
        TraceWriterThread writer = writer(file);
        writer.start();

        int threads = 8, perThread = 5_000;
        AtomicLong seq = new AtomicLong();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> emitters = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            Thread emitter = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    // What Tracer.emit does: take a number, then enqueue. Another thread may get in between; some help it to.
                    long number = seq.incrementAndGet();
                    if ((number & 15) == thread) Thread.yield();
                    // A few distinct frames shared by all: interning must not be confused by the interleaving.
                    writer.enqueue(event(number, new StackFrameRef("com.acme.Worker", "step" + (number % 5), "Worker.kt", (int) (number % 5))));
                }
            });
            emitter.start();
            emitters.add(emitter);
        }
        go.countDown();
        for (Thread emitter : emitters) emitter.join();
        writer.shutdown();

        assertTrue(writer.isClosed());
        Set<Long> seen = new HashSet<>();
        Set<Integer> defined = new HashSet<>();
        long last = 0;
        for (Frame frame : TraceFiles.read(file)) {
            if (frame.getStackFrame() != null) {
                assertEquals(defined.size() + 1, frame.getStackFrame().getId(), "frame ids start at 1 and are dense");
                defined.add(frame.getStackFrame().getId());
            }
            Event event = frame.getEvent();
            if (event == null) continue;
            assertTrue(seen.add(event.getSeq()), "event " + event.getSeq() + " is in the file twice");
            assertTrue(defined.containsAll(event.getStack()), "a definition precedes the first event that refers to it");
            last = Math.max(last, event.getSeq());
        }
        assertEquals(threads * perThread, seen.size());
        assertEquals(threads * perThread, last, "dense: that many events, and the largest number is their count");
        assertEquals(5, defined.size());
    }

    @Test
    void aBatchIsWrittenInSequenceOrderWithSettingsBehindTheEventTheyWereMadeAfter() throws Exception {
        File file = new File(dir, "batch.ctrace");
        TraceWriterThread writer = writer(file);
        // All of it is queued before the writer starts: one batch, whatever the machine does.
        writer.enqueue(pace(0, 5));  // the configured setting, made before the first event
        writer.enqueue(event(2));
        writer.enqueue(pace(2, 22));
        writer.enqueue(event(1));    // a thread that took its number first and got here second
        writer.enqueue(pace(2, 23)); // two changes after the same event keep the order they were made in
        writer.enqueue(new TraceEvent.DiagnosticDef(Wire.INFO, "something about the capture"));
        writer.enqueue(event(4));
        writer.enqueue(event(3));
        writer.enqueue(pace(3, 33));
        writer.start();
        writer.shutdown();

        List<String> order = new ArrayList<>();
        int diagnostics = 0;
        for (Frame frame : TraceFiles.read(file)) {
            if (frame.getEvent() != null) order.add("event " + frame.getEvent().getSeq());
            if (frame.getPace() != null) order.add("pace " + frame.getPace().getIntervalNanos() + " after " + frame.getPace().getAfterSeq());
            if (frame.getDiagnostic() != null) diagnostics++;
        }
        assertEquals(List.of("pace 5 after 0", "event 1", "event 2", "pace 22 after 2", "pace 23 after 2", "event 3", "pace 33 after 3", "event 4"), order);
        assertEquals(1, diagnostics);
    }

    @Test
    void whatWasHandedOverIsInTheFileAMomentLaterWithoutAnybodyAsking() throws Exception {
        // A JVM that dies abruptly leaves a trace that is at most a moment behind: the writer flushes when it runs dry.
        File file = new File(dir, "flush.ctrace");
        TraceWriterThread writer = writer(file);
        writer.start();
        for (int round = 1; round <= 3; round++) {
            writer.enqueue(event(round));
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (TraceFiles.read(file).size() < 1 + round && System.nanoTime() < deadline) Thread.sleep(2);
            assertEquals(1 + round, TraceFiles.read(file).size(), "the header and " + round + " events");
        }
        assertFalse(writer.isClosed());
    }

    @Test
    void shutdownWritesWhatIsQueuedClosesTheFileAndTakesNothingMore() throws Exception {
        File file = new File(dir, "shutdown.ctrace");
        TraceWriterThread writer = writer(file);
        writer.start();
        for (int i = 1; i <= 20_000; i++) writer.enqueue(event(i)); // more than one batch of the writer
        writer.shutdown();

        assertFalse(writer.isAlive());
        assertTrue(writer.isClosed(), "a live client that sees this reads to the end of the file and is done");
        long length = file.length();
        assertEquals(20_001, TraceFiles.read(file).size());

        writer.enqueue(event(20_001)); // a hook of a thread that is still running while the JVM goes down
        writer.shutdown();             // and a second request to stop: neither throws, neither changes the file
        assertEquals(length, file.length());
    }

    @Test
    void whenTheWriterFailsTracingGoesOffInsteadOfTheProgram() throws Exception {
        // The switch the agent has for this test: the writer fails as it would on a full disk.
        System.setProperty("coroutree.debug.writerFailsAfterMillis", "0");
        TraceWriterThread writer;
        try {
            writer = writer(new File(dir, "failing.ctrace"));
        } finally {
            System.clearProperty("coroutree.debug.writerFailsAfterMillis");
        }
        Tracer.active = true;
        try {
            writer.enqueue(event(1));
            writer.start();
            writer.join(10_000);
            assertFalse(writer.isAlive());
            assertFalse(Tracer.active, "hooks go quiet, and whoever is held at the gate goes, when the trace cannot be written");
            assertTrue(writer.isClosed(), "live clients are not left waiting for a file that will not grow");
            writer.enqueue(event(2)); // hooks that were already past the check: nothing piles up, nothing throws
        } finally {
            Tracer.active = false;
        }
    }
}
