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
        if (agentConfig.live) {
            try {
                live = new LiveServer(agentConfig, traceFile, startedAt, writer);
                live.start();
            } catch (IOException e) {
                diagnostic(Wire.WARNING, "Live streaming is off, the trace file is still written: " + e);
            }
        }
        for (String problem : agentConfig.problems) diagnostic(Wire.WARNING, problem);

        Thread shutdown = new AgentThread("coroutree-shutdown") {
            @Override
            public void run() {
                active = false;
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

    static void emit(TraceEvent event) {
        event.timeNanos = System.nanoTime() - originNanos;
        event.seq = SEQ.incrementAndGet();
        writer.enqueue(event);
    }

    /**
     * A bug in the agent must never become a bug in the application: hooks catch everything and report here.
     * Each distinct problem is reported once.
     */
    static void reportInternalError(String what, Throwable error) {
        try {
            String key = what + ": " + describe(error);
            if (REPORTED.size() > 100 || !REPORTED.add(key)) return;
            diagnostic(Wire.ERROR, "internal error, " + key);
            if (Boolean.getBoolean("coroutree.debug")) error.printStackTrace();
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
