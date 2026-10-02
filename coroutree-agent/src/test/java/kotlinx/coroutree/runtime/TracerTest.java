package kotlinx.coroutree.runtime;

import kotlinx.coroutree.model.Diagnostic;
import kotlinx.coroutree.model.Event;
import kotlinx.coroutree.model.EventKind;
import kotlinx.coroutree.model.Frame;
import kotlinx.coroutree.model.PaceDef;
import kotlinx.coroutree.model.TraceHeader;
import kotlinx.coroutree.runtime.TracedJvm.Recording;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The path every event takes out of a hook, in a JVM that traces for real: sequence numbers, what the gate's step
 * leaves on an event, settings of the gate among the events, and the agent's talk about itself.
 */
@Timeout(120)
class TracerTest {
    private static final AtomicLong IDS = new AtomicLong(5_000_000);

    @BeforeAll
    static void traceThisJvm() {
        TracedJvm.start();
    }

    /** A node the gate keeps time for, of nobody's structure. */
    private static final class Node extends PaceNode {
        Node() {
            super(IDS.incrementAndGet());
        }

        @Override
        boolean isFinished() {
            return false;
        }
    }

    /** What a hook does around its events: pass the gate, emit, settle. */
    private static TraceEvent[] step(PaceNode node, int events) {
        ThreadState ts = ThreadState.current();
        Pace.await(ts, node, node, null);
        TraceEvent[] emitted = new TraceEvent[events];
        for (int i = 0; i < events; i++) {
            emitted[i] = new TraceEvent(node.id, Wire.RESUMED, 0);
            Tracer.emit(ts, emitted[i]);
        }
        Pace.settle(ts);
        return emitted;
    }

    @Test
    void theTraceOfThisJvmStartsWithItsHeaderAndTheSettingItStartedWith() {
        List<Frame> frames = TraceFiles.read(TracedJvm.TRACE);
        TraceHeader header = frames.get(0).getHeader();
        assertNotNull(header, "the first frame holds the header");
        assertEquals("unit", header.getBuildId());
        assertEquals(":agent:test", header.getTaskPath());
        assertEquals(List.of("com.acme"), header.getIncludePackages());
        assertEquals(List.of("com.acme.generated"), header.getExcludePackages());
        assertEquals(ProcessHandle.current().pid(), header.getJvm().getPid());
        assertTrue(header.getPaceable(), "no live socket, but a configured pace: the JVM has a gate");

        PaceDef first = null;
        for (Frame frame : frames) {
            if (frame.getHeader() != null && frame != frames.get(0)) throw new AssertionError("a second header");
            if (first == null && frame.getPace() != null) first = frame.getPace();
        }
        assertNotNull(first);
        assertEquals(new PaceDef(first.getTimeNanos(), 0, 0, TracedJvm.INTERVAL_NANOS, false, 0, PaceDef.Reason.CONFIG, false), first,
            "the first setting is what the run was configured to start with, made before the first event");
        assertThrows(IllegalStateException.class, () -> Tracer.start(TracedJvm.CONFIG), "one capture per JVM");
    }

    @Test
    void sequenceNumbersAreDenseAndFollowTheOrderOfEmitOnEveryThread() throws Exception {
        int threads = 8, perThread = 2_000;
        Recording recording = TracedJvm.record(() -> {
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> emitters = new ArrayList<>();
            for (int t = 1; t <= threads; t++) {
                long thread = t;
                Thread emitter = new Thread(() -> {
                    ThreadState ts = ThreadState.current();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 1; i <= perThread; i++) {
                        ts.awaited = true; // each its own step, through a gate that is not asked here
                        TraceEvent event = new TraceEvent(i, Wire.RESUMED, thread);
                        Tracer.emit(ts, event);
                        Pace.settle(ts);
                    }
                });
                emitter.start();
                emitters.add(emitter);
            }
            go.countDown();
            for (Thread emitter : emitters) emitter.join();
        });

        // Recording has checked that no number between the first and the last is missing; these are all of them.
        assertEquals(threads * perThread, recording.events.size());
        Map<Long, Long> lastOfThread = new HashMap<>();
        long previous = 0;
        for (Event event : recording.events) {
            if (previous != 0) assertEquals(previous + 1, event.getSeq());
            previous = event.getSeq();
            long last = lastOfThread.getOrDefault(event.getThreadId(), 0L);
            assertEquals(last + 1, event.getNodeId(), "thread " + event.getThreadId() + " emitted its events in this order");
            lastOfThread.put(event.getThreadId(), event.getNodeId());
        }
        assertEquals(threads, lastOfThread.size());
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void theEventsOfOneHookCallAreOneStepAndTheHoldIsAccountedOnItsFirstEvent() throws Exception {
        Node node = new Node();
        long[] released = new long[1], heldBefore = new long[1];
        Recording recording = TracedJvm.record("stepper", () -> {
            ThreadState ts = ThreadState.current();
            step(node, 2); // the first step of a sequence is not held

            Pace.await(ts, node, node, null); // the second waits out the interval,
            released[0] = ts.releaseNanos;
            Thread.sleep(3);                  // and its hook takes its time to put three events together
            for (int i = 0; i < 3; i++) Tracer.emit(ts, new TraceEvent(node.id, Wire.RESUMED, 0));
            Pace.settle(ts);

            // A hook call that waited at the gate and then had nothing to report: its time is not lost. (Held right
            // after a step of its sequence it is; tried again should the machine have stalled for the whole interval.)
            for (int attempt = 0; attempt < 50 && ts.heldNanos == 0; attempt++) {
                step(node, 1);
                Pace.await(ts, node, node, null);
                Pace.settle(ts);
            }
            heldBefore[0] = ts.heldNanos;
            step(node, 1);
        });

        List<Event> events = recording.of(node.id);
        List<Boolean> sameStep = new ArrayList<>();
        for (Event event : events) sameStep.add(event.getSameStep());
        assertEquals(List.of(false, true, false, true, true), sameStep.subList(0, 5));
        assertFalse(sameStep.subList(5, sameStep.size()).contains(true), "every step after that had one event");

        Event first = events.get(0), second = events.get(2), third = events.get(5), last = events.get(events.size() - 1);
        assertTrue(second.getTimeNanos() - first.getTimeNanos() >= TracedJvm.INTERVAL_NANOS,
            "two steps of one sequence are at least the interval apart by their times in the trace: " + (second.getTimeNanos() - first.getTimeNanos()));
        assertTrue(third.getTimeNanos() - second.getTimeNanos() >= TracedJvm.INTERVAL_NANOS);
        assertEquals(0, first.getHeldNanos());
        assertTrue(second.getHeldNanos() > 0, "held for the rest of the interval");
        assertEquals(0, events.get(1).getHeldNanos());
        assertEquals(0, events.get(3).getHeldNanos(), "what was held is said once");
        assertEquals(0, events.get(4).getHeldNanos());
        assertTrue(heldBefore[0] > 0, "the step with nothing to report was held like any other");
        assertTrue(last.getHeldNanos() >= heldBefore[0], "sums per thread are exact: time held for nothing is carried to the thread's next event");
        // The time of a step is the moment the gate let it go, not the later moment its events were put together.
        assertEquals(released[0], second.getTimeNanos());
        assertTrue(events.get(3).getTimeNanos() >= second.getTimeNanos() + 3_000_000, "the others have the time they were emitted at");
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void anEventThatDidNotPassTheGateIsWrittenAndSaidLoudlyOncePerKind() throws Exception {
        Recording recording = TracedJvm.record("forgetful", () -> {
            ThreadState ts = ThreadState.current();
            // A hook that emits without its await: one the user could not stop the program at.
            Tracer.emit(ts, new TraceEvent(77, Wire.CANCELLATION_PROPAGATED, 0));
            Pace.settle(ts);
            Tracer.emit(ts, new TraceEvent(77, Wire.CANCELLATION_PROPAGATED, 0));
            Pace.settle(ts);
        });
        assertEquals(2, recording.events.size(), "the event itself is not lost");
        assertEquals(EventKind.CANCELLATION_PROPAGATED, recording.events.get(0).getKind());
        assertEquals(1, recording.errors().size(), "each distinct problem is reported once: " + recording.errors());
        assertTrue(recording.errors().get(0).contains("did not pass the gate"), recording.errors().get(0));
    }

    @Test
    void aSettingOfTheGateTakesNoSequenceNumberAndSaysAfterWhichEventItWasMade() throws Exception {
        Node node = new Node();
        long[] seqs = new long[2];
        Recording recording = TracedJvm.record("setter", () -> {
            seqs[0] = step(node, 1)[0].seq;
            Pace.GATE.command("pace 2000000");
            Pace.GATE.command("pace " + TracedJvm.INTERVAL_NANOS);
            seqs[1] = step(node, 1)[0].seq;
        });
        assertEquals(seqs[0] + 1, seqs[1], "a setting is not something the program did");
        List<String> settings = new ArrayList<>();
        for (PaceDef pace : recording.paces) settings.add(pace.getReason() + " " + pace.getIntervalNanos() + " after " + (pace.getAfterSeq() - seqs[0]));
        assertEquals(List.of("CONTROLLER 2000000 after 0", "CONTROLLER 1000000 after 0"), settings);
        for (PaceDef pace : recording.paces) assertTrue(pace.getTimeNanos() > 0);
        assertEquals(List.of(), recording.errors());
    }

    @Test
    void warmingTheGateUpLeavesNoMarkOnTheRealOneOrInTheTrace() throws Exception {
        long[] held = {-1};
        Recording recording = TracedJvm.record("warming-up", () -> {
            // Everything a held thread and a control reader will run, run once: commands, a hold, a step, a controller that leaves.
            Pace.warmUp();
            ThreadState ts = ThreadState.current();
            held[0] = ts.heldNanos;
            assertFalse(ts.inHook || ts.awaited, "the thread is the program's again");
        });
        assertEquals(List.of(), recording.events);
        assertEquals(List.of(), recording.paces, "the settings it plays with are those of a gate of its own");
        assertEquals(List.of(), recording.errors());
        assertEquals(0, held[0], "its hold is nobody's time");
        assertFalse(Pace.GATE.root.paused);
        assertEquals(TracedJvm.INTERVAL_NANOS, Pace.GATE.root.intervalNanos);
        assertNull(Pace.GATE.node(Long.MAX_VALUE), "its nodes are not nodes of the trace");
    }

    @Test
    void whatTheAgentSaysAboutItselfIsInTheTraceEachProblemOnceAndNeverThrown() throws Exception {
        Throwable unprintable = new RuntimeException() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString of an application exception");
            }
        };
        Recording recording = TracedJvm.record(() -> {
            Tracer.diagnostic(Wire.WARNING, "said once by a test");
            Tracer.diagnostic(Wire.INFO, "said once by a test");
            for (int i = 0; i < 3; i++) Tracer.reportInternalError("tracerTest", new IllegalStateException("the same every time"));
            Tracer.reportInternalError("tracerTest", new IllegalStateException("another"));
            Tracer.reportInternalError("tracerTest, elsewhere", new IllegalStateException("the same every time"));
            // This runs in the catch block of every hook: whatever it is given, nothing comes out of it.
            Tracer.reportInternalError("tracerTest", unprintable);
            Tracer.reportInternalError("tracerTest", null);
            Tracer.reportInternalError(null, null);
        });
        List<String> said = new ArrayList<>();
        for (Diagnostic diagnostic : recording.diagnostics) said.add(diagnostic.getSeverity() + " " + diagnostic.getMessage());
        assertEquals(List.of(
            "WARNING said once by a test",
            "INFO said once by a test",
            "ERROR internal error, tracerTest: java.lang.IllegalStateException: the same every time",
            "ERROR internal error, tracerTest: java.lang.IllegalStateException: another",
            "ERROR internal error, tracerTest, elsewhere: java.lang.IllegalStateException: the same every time",
            "ERROR internal error, tracerTest: " + unprintable.getClass().getName()), said);
    }
}
