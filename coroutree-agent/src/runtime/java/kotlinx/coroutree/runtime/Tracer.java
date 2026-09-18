package kotlinx.coroutree.runtime;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Lifecycle of the capture and the path every event takes out of a hook. */
public final class Tracer {
    private Tracer() {}

    /** Checked first by every hook. False until {@link #activate}, after the writer has failed, and during shutdown. */
    static volatile boolean active;

    /** {@code -Dcoroutree.debug=true}: self-checks that cost something, and stack traces of internal errors. Tests run with it. */
    static final boolean DEBUG = Boolean.getBoolean("coroutree.debug");

    static AgentConfig config;
    private static TraceWriterThread writer;
    private static volatile LiveServer live;
    private static long originNanos;

    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicLong NODE_IDS = new AtomicLong();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Opens the trace and starts the agent's threads. Hooks stay inert until {@link #activate}, which the agent
     * calls once the classes that were already loaded have been retransformed.
     */
    public static synchronized void start(AgentConfig agentConfig) throws IOException {
        if (writer != null) throw new IllegalStateException("coroutree is already started");
        config = agentConfig;
        originNanos = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        File traceFile = agentConfig.resolveTraceFile(ProcessHandle.current().pid());
        writer = new TraceWriterThread(traceFile, agentConfig, startedAt);
        writer.start();
        // From here on the gate exists or does not (Pace.GATE is made from the configuration the first time Pace is touched).
        final Pace gate = Pace.GATE;
        if (gate != null) gate.announce();
        if (agentConfig.live) {
            try {
                live = new LiveServer(agentConfig, traceFile, startedAt, writer);
                live.start();
                live.warmUp();
            } catch (IOException e) {
                diagnostic(Wire.WARNING, "Live streaming is off, the trace file is still written: " + e);
                if (gate != null && gate.liveUnavailable()) {
                    diagnostic(Wire.WARNING, "The program was to start paused, but without the live socket nothing could ever resume it: it runs.");
                }
            }
        }
        Pace.warmUp();
        for (String problem : agentConfig.problems) diagnostic(Wire.WARNING, problem);
        if (gate != null && gate.root.paused) {
            System.err.println("coroutree: the program starts PAUSED and stays so until a coroutree GUI resumes or steps it (pace.paused)");
        }

        Thread shutdown = new AgentThread("coroutree-shutdown") {
            @Override
            public void run() {
                active = false; // first: whoever is held at the gate goes, and a paused program exits on Ctrl-C with its trace complete
                if (gate != null) gate.shutdown();
                writer.shutdown();
                if (live != null) live.shutdown(); // after the writer: live clients get the trace to its last byte
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
            // Every hook passes the gate before it emits (and names the point, see Pace.await). One that does not would
            // be a hook the user cannot stop the program at; said loudly, so that it cannot go unnoticed in a test.
            if (!ts.awaited) reportInternalError("gate", new IllegalStateException("event of kind " + event.kind + " did not pass the gate"));
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

    /** A setting of the gate has changed. No sequence number: a setting is not something the program did. */
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
     * A bug in the agent must never become a bug in the application: hooks catch everything and report here.
     * Each distinct problem is reported once.
     */
    static void reportInternalError(String what, Throwable error) {
        try {
            String key = what + ": " + describe(error);
            if (REPORTED.size() > 100 || !REPORTED.add(key)) return;
            diagnostic(Wire.ERROR, "internal error, " + key);
            if (DEBUG) error.printStackTrace();
        } catch (Throwable ignored) {
            // This runs in the catch block of every hook: whatever happens here must not reach the application either.
        }
    }

    /** The error may come out of application code (a toString, a hashCode) and have a toString of the same quality. */
    private static String describe(Throwable error) {
        try {
            return error.toString();
        } catch (Throwable e) {
            return error.getClass().getName();
        }
    }

    /** The trace cannot be written any more. Hooks go quiet instead of doing work that is thrown away. */
    static void writerFailed(Throwable error) {
        active = false;
        LiveServer server = live;
        if (server != null) server.shutdown();
        reportInternalError("trace writer stopped, tracing is off", error);
    }
}
