package kotlinx.coroutree.runtime;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class Tracer {
    private Tracer() {}

    /** Checked first by every hook. False until {@link #activate}, after the writer has failed, and during shutdown. */
    static volatile boolean active;

    /** Enables additional runtime checks and internal-error stack traces. Tests set -Dcoroutree.debug=true. */
    static final boolean DEBUG = Boolean.getBoolean("coroutree.debug");

    static AgentConfig config;
    private static TraceWriterThread writer;
    private static volatile LiveServer live;
    private static long originNanos;

    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicLong NODE_IDS = new AtomicLong();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Hooks stay inactive until {@link #activate}, after loaded classes have been retransformed.
     */
    public static synchronized void start(AgentConfig agentConfig) throws IOException {
        if (writer != null) throw new IllegalStateException("coroutree is already started");
        config = agentConfig;
        originNanos = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        File traceFile = agentConfig.resolveTraceFile(ProcessHandle.current().pid());
        writer = new TraceWriterThread(traceFile, agentConfig, startedAt);
        writer.start();
        final Pace gate = Pace.GATE;
        if (gate != null) gate.announce();
        if (agentConfig.live) {
            try {
                live = new LiveServer(writer);
                live.start();
                live.warmUp();
            } catch (IOException e) {
                diagnostic(Wire.WARNING, "The live socket failed to open, so no GUI can attach: " + e + ". The trace file is still written.");
                if (gate != null && gate.liveUnavailable()) {
                    diagnostic(Wire.WARNING, "Ignored pace.paused: without the live socket no GUI can resume the program, so it runs.");
                }
            }
        }
        final SessionDescriptor descriptor = new SessionDescriptor(agentConfig, traceFile, startedAt, live);
        descriptor.write(false);
        Pace.warmUp();
        for (String problem : agentConfig.problems) diagnostic(Wire.WARNING, problem);
        if (gate != null && gate.root.paused) {
            System.err.println("coroutree: the program starts paused (pace.paused). Resume or step it from a coroutree GUI.");
        }

        Thread shutdown = new AgentThread("coroutree-shutdown") {
            @Override
            public void run() {
                active = false; // release held threads before draining the trace
                if (gate != null) gate.shutdown();
                writer.shutdown();
                if (live != null) live.shutdown(); // after the writer: live clients get the trace to its last byte
                descriptor.write(true);
            }
        };
        shutdown.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(shutdown);
        System.err.println("coroutree: tracing to " + traceFile);
    }

    public static void activate() {
        active = true;
    }

    /** A message about the capture itself, recorded in the trace and echoed to stderr. */
    public static void diagnostic(int severity, String message) {
        System.err.println("coroutree: " + message);
        TraceWriterThread w = writer;
        if (w != null) w.enqueue(new TraceEvent.DiagnosticDef(severity, message));
    }

    static long newNodeId() {
        return NODE_IDS.incrementAndGet();
    }

    /** Time as the trace counts it: nanoseconds since it started. */
    static long now() {
        return System.nanoTime() - originNanos;
    }

    static void emit(ThreadState ts, TraceEvent event) {
        long time = now();
        if (Pace.GATE != null) {
            // A hook that skips await can't be paused at its event. Report it as an internal error so tests fail.
            if (!ts.awaited) reportInternalError("gate", new IllegalStateException("event of kind " + event.kind + " didn't pass the gate"));
            if (ts.stepEmitted) {
                event.sameStep = true;
            } else if (ts.releaseNanos != Pace.NEVER) {
                time = ts.releaseNanos; // taken at release: steps of one sequence are an interval apart exactly
            }
            ts.stepEmitted = true;
            event.heldNanos = ts.heldNanos;
            ts.heldNanos = 0;
        }
        event.timeNanos = time;
        event.seq = SEQ.incrementAndGet();
        writer.enqueue(event);
    }

    /** Settings have no sequence number because they aren't program events. */
    static void pace(TraceEvent.PaceDef def) {
        def.timeNanos = now();
        def.afterSeq = SEQ.get();
        java.util.List<TraceEvent.PaceDef> sink = paceSink;
        if (sink != null) sink.add(def);
        TraceWriterThread w = writer;
        if (w != null) w.enqueue(def);
    }

    /** For tests of the gate, which run without a writer. */
    static volatile java.util.List<TraceEvent.PaceDef> paceSink;

    /**
     * Must not throw into application code, even if error formatting fails. Reports each distinct problem only once.
     */
    static void reportInternalError(String what, Throwable error) {
        try {
            String key = what + ": " + describe(error);
            if (REPORTED.size() > 100 || !REPORTED.add(key)) return;
            diagnostic(Wire.ERROR, "internal error: " + key);
            if (DEBUG) error.printStackTrace();
        } catch (Throwable ignored) {
        }
    }

    /** Application exceptions can have a toString that also throws. */
    private static String describe(Throwable error) {
        try {
            return error.toString();
        } catch (Throwable e) {
            return error.getClass().getName();
        }
    }

    static void writerFailed(Throwable error) {
        active = false;
        LiveServer server = live;
        if (server != null) server.shutdown();
        reportInternalError("trace writer stopped, tracing is off", error);
    }
}
