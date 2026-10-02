package kotlinx.coroutree.runtime;

import kotlinx.coroutree.model.Diagnostic;
import kotlinx.coroutree.model.Event;
import kotlinx.coroutree.model.Frame;
import kotlinx.coroutree.model.NodeInfo;
import kotlinx.coroutree.model.PaceDef;
import kotlinx.coroutree.model.StackFrameDef;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The test JVM as a traced one: the tracer is started once, for good (it cannot be stopped), with a trace file that the
 * tests read back with coroutree-model, the other implementation of the format. Nothing is instrumented here; tests
 * call the hooks the way instrumented code would.
 *
 * The JVM has a gate, the one {@link PaceTest} insists on and with the same pace, whichever class is loaded first:
 * {@link Pace#GATE} is made once per JVM from the configuration of that moment.
 */
final class TracedJvm {
    private TracedJvm() {}

    static final String GATE_OPTIONS = "live=false,pace.events.per.second=1000";
    static final long INTERVAL_NANOS = 1_000_000;
    static final int STACK_DEPTH = 12;

    static final File DIR;
    static final File TRACE;
    static final AgentConfig CONFIG;

    /** Node id of the events this class emits to find its place in the file; no node of a trace gets there. */
    private static final long SENTINEL = Long.MAX_VALUE - 7;

    static {
        try {
            if (Tracer.config == null) Tracer.config = AgentConfig.parse(GATE_OPTIONS);
            if (Pace.GATE == null) throw new IllegalStateException("the test JVM was to have a gate");
            DIR = Files.createTempDirectory("coroutree-agent-test").toFile();
            TRACE = new File(DIR, "unit.ctrace");
            DIR.deleteOnExit();
            TRACE.deleteOnExit(); // after the agent's own shutdown hook has closed it
            CONFIG = AgentConfig.parse("trace.file=" + TRACE + "," + GATE_OPTIONS
                + ",include=com.acme,exclude=com.acme.generated,stack.depth=" + STACK_DEPTH + ",build.id=unit,task.path=:agent:test,project.dir=/work");
            Tracer.start(CONFIG);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads this class, which is what starts the tracer. */
    static void start() {
    }

    interface Scenario {
        void run() throws Throwable;
    }

    /**
     * Runs the scenario on a thread of its own (a thread the agent has never seen, with nothing on its books) while
     * tracing is on, and returns what went into the trace file meanwhile.
     */
    static Recording record(String threadName, Scenario scenario) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                scenario.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, threadName);
        thread.setDaemon(true);
        Recording recording = record(() -> {
            thread.start();
            thread.join(60_000);
            if (thread.isAlive()) throw new AssertionError("the scenario is stuck: " + List.of(thread.getStackTrace()));
        });
        if (failure.get() != null) throw new AssertionError("the scenario failed", failure.get());
        return recording;
    }

    /** The same on the calling thread, for scenarios that bring their own threads. */
    static Recording record(Scenario scenario) throws Exception {
        Tracer.config = CONFIG; // PaceTest sets its own when it is loaded
        long begin = sentinel();
        int first = awaitFrames(begin).size();
        Tracer.active = true;
        try {
            scenario.run();
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError(e);
        } finally {
            Tracer.active = false;
        }
        long end = sentinel();
        List<Frame> frames = awaitFrames(end);
        return new Recording(frames, first, begin, end);
    }

    /** An event of no kind on no node: it takes a sequence number, and when it is in the file so is everything before it. */
    private static long sentinel() {
        ThreadState ts = ThreadState.current();
        ts.awaited = true; // what a hook's await leaves; emit insists on it
        TraceEvent event = new TraceEvent(SENTINEL, 0, 0);
        Tracer.emit(ts, event);
        Pace.settle(ts);
        ts.heldNanos = 0;
        return event.seq;
    }

    /** The frames of the file once the sentinel and every event before it are there; the list ends with the sentinel. */
    private static List<Frame> awaitFrames(long sentinelSeq) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (true) {
            List<Frame> frames = TraceFiles.read(TRACE);
            int at = -1;
            long events = 0;
            for (int i = 0; i < frames.size(); i++) {
                Event event = frames.get(i).getEvent();
                if (event == null) continue;
                if (event.getSeq() <= sentinelSeq) events++;
                if (event.getSeq() == sentinelSeq) at = i;
            }
            // Sequence numbers are dense: that many events up to the sentinel means all of them.
            if (at >= 0 && events == sentinelSeq) return frames.subList(0, at + 1);
            if (System.nanoTime() > deadline) throw new AssertionError("the trace file never got to event " + sentinelSeq + ": " + events + " events before it");
            Thread.sleep(5);
        }
    }

    /** What one scenario left in the trace. */
    static final class Recording {
        /** In the order they happened: by sequence number. */
        final List<Event> events = new ArrayList<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final List<PaceDef> paces = new ArrayList<>();
        /** Every stack frame the file has defined so far. */
        final Map<Integer, StackFrameDef> stackFrames = new HashMap<>();

        private Recording(List<Frame> frames, int first, long begin, long end) {
            for (int i = 0; i < frames.size(); i++) {
                Frame frame = frames.get(i);
                if (frame.getStackFrame() != null) stackFrames.put(frame.getStackFrame().getId(), frame.getStackFrame());
                if (i < first) continue;
                if (frame.getDiagnostic() != null) diagnostics.add(frame.getDiagnostic());
                if (frame.getPace() != null) paces.add(frame.getPace());
                Event event = frame.getEvent();
                if (event != null && event.getSeq() > begin && event.getSeq() < end) events.add(event);
            }
            events.sort(Comparator.comparingLong(Event::getSeq));
        }

        /** The definition of the one node of that name. */
        NodeInfo node(String name) {
            NodeInfo found = null;
            for (Event event : events) {
                NodeInfo node = event.getNode();
                if (node == null || !node.getName().equals(name)) continue;
                if (found != null) throw new AssertionError("two definitions of " + name + ": " + events);
                found = node;
            }
            if (found == null) throw new AssertionError("no node named " + name + " in " + events);
            return found;
        }

        List<Event> of(long nodeId) {
            List<Event> result = new ArrayList<>();
            for (Event event : events) if (event.getNodeId() == nodeId) result.add(event);
            return result;
        }

        /** The kinds of the node's events in order, with what tells two of a kind apart: {@code THREAD_BLOCKED(SLEEP)}. */
        List<String> story(long nodeId) {
            List<String> result = new ArrayList<>();
            for (Event event : of(nodeId)) result.add(describe(event));
            return result;
        }

        List<String> errors() {
            List<String> result = new ArrayList<>();
            for (Diagnostic diagnostic : diagnostics) {
                if (diagnostic.getSeverity() == Diagnostic.Severity.ERROR) result.add(diagnostic.getMessage());
            }
            return result;
        }

        /** The stack as {@code Class.method}, innermost first. */
        List<String> stack(List<Integer> ids) {
            List<String> result = new ArrayList<>();
            for (int id : ids) {
                StackFrameDef frame = stackFrames.get(id);
                if (frame == null) throw new AssertionError("frame " + id + " is referred to and not defined");
                result.add(frame.getClassName() + "." + frame.getMethodName());
            }
            return result;
        }

        static String describe(Event event) {
            String detail = switch (event.getKind()) {
                case THREAD_BLOCKED -> event.getBlockReason().name();
                case EXCEPTION_HANDLED -> event.getHandledBy().name();
                case FINISHED -> event.getFinalState().name();
                default -> "";
            };
            return detail.isEmpty() ? event.getKind().name() : event.getKind().name() + "(" + detail + ")";
        }
    }
}
