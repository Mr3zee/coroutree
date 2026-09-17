package kotlinx.coroutree.runtime;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * The only thread that writes the trace file, and the file is the only thing it writes: live clients are served by
 * threads of their own that read the file back ({@link LiveServer}), so a client that is slow or stuck cannot hold
 * up the capture.
 *
 * Hooks hand events over through a lock-free queue and never wait. This thread drains the queue in batches, puts each
 * batch back into sequence order (threads race between taking a number and enqueueing), encodes and writes it, and
 * flushes whenever it runs dry, so a JVM that dies abruptly leaves a trace that is at most a moment behind.
 */
final class TraceWriterThread extends AgentThread {
    private static final int MAX_BATCH = 8192;
    private static final long MIN_PARK_NANOS = 200_000;       // 0.2 ms
    private static final long MAX_PARK_NANOS = 20_000_000;    // 20 ms

    private static final Comparator<Object> BY_SEQ = new Comparator<Object>() {
        @Override
        public int compare(Object a, Object b) {
            return Long.compare(seq(a), seq(b));
        }

        // Diagnostics carry no number; sorting them first keeps them near where they were enqueued.
        private long seq(Object item) {
            return item instanceof TraceEvent event ? event.seq : 0;
        }
    };

    private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
    final File file;
    private final OutputStream out;
    private final TraceEncoder encoder;
    private final ArrayList<Object> batch = new ArrayList<>();
    private volatile boolean stopping;
    private volatile boolean closed;

    TraceWriterThread(File file, AgentConfig config, long startedAtEpochMillis) throws IOException {
        super("coroutree-writer");
        this.file = file;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        this.out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
        this.encoder = new TraceEncoder(out);
        encoder.writeMagic();
        encoder.writeHeader(config, startedAtEpochMillis);
        out.flush();
    }

    void enqueue(Object item) {
        if (!closed) queue.add(item);
    }

    /** The file has its last byte: nothing more will be written. */
    boolean isClosed() {
        return closed;
    }

    /** Called from the shutdown hook: writes out whatever is queued and closes the file. */
    void shutdown() {
        stopping = true;
        LockSupport.unpark(this);
        try {
            join(5000);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void run() {
        long park = MIN_PARK_NANOS;
        Throwable failure = null;
        try {
            while (true) {
                boolean stop = stopping; // read before draining: what was enqueued before the stop request gets written
                if (drain()) {
                    park = MIN_PARK_NANOS;
                    continue;
                }
                out.flush();
                if (stop) break;
                LockSupport.parkNanos(park);
                park = Math.min(park * 2, MAX_PARK_NANOS);
            }
        } catch (Throwable e) {
            failure = e;
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            closed = true; // only now: a client that sees this flag reads to the end of the file and is done
            queue.clear();
        }
        if (failure != null) Tracer.writerFailed(failure);
    }

    private boolean drain() throws IOException {
        batch.clear();
        for (Object item = queue.poll(); item != null; item = queue.poll()) {
            batch.add(item);
            if (batch.size() == MAX_BATCH) break;
        }
        if (batch.isEmpty()) return false;
        batch.sort(BY_SEQ);
        for (Object item : batch) {
            if (item instanceof TraceEvent event) encoder.writeEvent(event);
            else encoder.writeDiagnostic((TraceEvent.DiagnosticDef) item);
        }
        return true;
    }
}
