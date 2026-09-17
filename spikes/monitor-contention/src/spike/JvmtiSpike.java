package spike;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Option (b): a JVMTI agent (jvmti_probe.c) whose monitor callbacks call straight into Java, on the contending thread,
 * at the moment of contention — which is where the real agent would call {@code Hooks.blockEnter(MONITOR)}.
 * Run with {@code -agentpath:libjvmtiprobe.dylib}.
 */
public final class JvmtiSpike {
    /**
     * What the native callbacks report. It runs inside monitor events of every monitor in the JVM, the JDK's own
     * included, so it must not block on a monitor, and must not link anything lazily: the first version used a lambda
     * here, whose bootstrap contended on a JDK-internal monitor, re-entered this method and failed. Hence the
     * anonymous class and the warm-up call — the same discipline the agent's Hooks already follow.
     */
    public static final class Sink {
        public record Seen(int kind, boolean workloadMonitor, long threadId, long seq, long nanos, String topFrame) {}

        public static final int CONTENDED_ENTER = 1, CONTENDED_ENTERED = 2, WAIT = 3, WAITED = 4;
        static final ConcurrentLinkedQueue<Seen> SEEN = new ConcurrentLinkedQueue<>();
        static volatile boolean captureStacks;
        private static final StackWalker WALKER = StackWalker.getInstance();
        private static final ThreadLocal<Boolean> INSIDE = new ThreadLocal<>();

        private static final Function<Stream<StackWalker.StackFrame>, String> TOP_FRAME = new Function<>() {
            @Override
            public String apply(Stream<StackWalker.StackFrame> frames) {
                var iterator = frames.iterator();
                while (iterator.hasNext()) {
                    StackWalker.StackFrame frame = iterator.next();
                    if (!frame.getClassName().startsWith("spike.JvmtiSpike")) {
                        return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                    }
                }
                return null;
            }
        };

        /** Called from C. */
        public static void event(int kind, Object monitor) {
            if (INSIDE.get() != null) return; // the same re-entrancy guard Hooks has
            INSIDE.set(Boolean.TRUE);
            try {
                long seq = Workload.SEQ.incrementAndGet();
                long nanos = System.nanoTime();
                String top = captureStacks && kind == CONTENDED_ENTER ? WALKER.walk(TOP_FRAME) : null;
                SEEN.add(new Seen(kind, monitor == Workload.LOCK, Thread.currentThread().threadId(), seq, nanos, top));
            } finally {
                INSIDE.remove();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int threads = Integer.parseInt(args[0]), longHolds = Integer.parseInt(args[1]), shortHolds = Integer.parseInt(args[2]), waits = Integer.parseInt(args[3]);
        Sink.captureStacks = true;
        Sink.event(Sink.CONTENDED_ENTER, null); // warm-up: load and link everything the sink touches
        String library = System.getProperty("spike.load"); // instead of -agentpath: load the probe into the running JVM
        if (library != null) System.load(library);
        Sink.SEEN.clear();
        Sink.captureStacks = args.length > 4 && args[4].equals("stacks");

        Workload workload = Workload.run(threads, longHolds, shortHolds, waits);
        List<Sink.Seen> seen = new ArrayList<>(Sink.SEEN);

        System.out.printf("mode=jvmti%s%s elapsed=%.1f ms acquisitions=%d%n", library != null ? "(System.load)" : "(-agentpath)",
            Sink.captureStacks ? "+stacks" : "", workload.elapsedNanos / 1e6, workload.acquisitions.size());
        List<Sink.Seen> onLock = seen.stream().filter(Sink.Seen::workloadMonitor).toList();
        long enters = onLock.stream().filter(s -> s.kind() == Sink.CONTENDED_ENTER).count();
        long entered = onLock.stream().filter(s -> s.kind() == Sink.CONTENDED_ENTERED).count();
        System.out.printf("on the workload's monitor: MonitorContendedEnter=%d MonitorContendedEntered=%d; on other monitors (JDK internals): %d events%n",
            enters, entered, seen.stream().filter(s -> !s.workloadMonitor() && s.kind() <= Sink.CONTENDED_ENTERED).count());
        System.out.printf("MonitorWait=%d MonitorWaited=%d (wait rounds=%d)%n",
            seen.stream().filter(s -> s.kind() == Sink.WAIT).count(), seen.stream().filter(s -> s.kind() == Sink.WAITED).count(), waits);
        if (enters == 0) {
            System.out.println("no events: was the JVM started with -agentpath:...libjvmtiprobe?");
            return;
        }
        onLock.stream().filter(s -> s.topFrame() != null).findFirst().ifPresent(s -> System.out.println("sample top frame: " + s.topFrame()));

        // Ordering: the callback took its sequence number on the contending thread, so it must fall between the
        // numbers the program took right before asking for the monitor and right after getting it.
        Map<Long, List<Workload.Acquisition>> byThread = new HashMap<>();
        for (Workload.Acquisition a : workload.acquisitions) byThread.computeIfAbsent(a.threadId(), k -> new ArrayList<>()).add(a);
        int ordered = 0;
        for (Sink.Seen event : onLock) {
            for (Workload.Acquisition a : byThread.getOrDefault(event.threadId(), List.of())) {
                if (a.seqBefore() < event.seq() && event.seq() < a.seqAfter()) {
                    ordered++;
                    break;
                }
            }
        }
        System.out.printf("callbacks whose sequence number lies inside the program's own (before, after) bracket: %d of %d%n", ordered, onLock.size());

        for (long threshold : new long[] {10_000, 100_000, 1_000_000}) {
            long count = workload.acquisitions.stream().filter(a -> a.waitedNanos() >= threshold).count();
            System.out.printf("acquisitions that waited >= %d µs: %d%n", threshold / 1000, count);
        }
    }
}
