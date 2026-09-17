package spike;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Option (a): in-process JFR event streaming. Runs the workload with jdk.JavaMonitorEnter / jdk.JavaMonitorWait
 * streamed to a consumer in the same JVM and answers the questions of DESIGN.md §11.7 with numbers.
 */
public final class JfrSpike {
    private record Seen(String type, long threadId, long startNanos, long endNanos, long arrivedNanos, boolean hasStack, String topFrame) {}

    public static void main(String[] args) throws Exception {
        int threads = Integer.parseInt(args[0]), longHolds = Integer.parseInt(args[1]), shortHolds = Integer.parseInt(args[2]), waits = Integer.parseInt(args[3]);
        boolean probe = !(args.length > 4 && args[4].equals("baseline"));

        // JFR stamps events with wall-clock Instants taken from its own tick counter; the agent orders events with
        // System.nanoTime. To merge the two, one needs the offset between the clocks — sampled here before and after
        // the run, which also shows how much it drifts.
        long offsetBefore = clockOffset();

        List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
        RecordingStream stream = null;
        if (probe) {
            stream = new RecordingStream();
            for (String type : List.of("jdk.JavaMonitorEnter", "jdk.JavaMonitorWait")) {
                stream.enable(type).withThreshold(Duration.ZERO).withStackTrace();
                stream.onEvent(type, event -> seen.add(toSeen(event, offsetBefore)));
            }
            stream.startAsync();
            Thread.sleep(500); // let the recording come up; not part of what is measured
        }

        Workload workload = Workload.run(threads, longHolds, shortHolds, waits);
        long workloadEnd = System.nanoTime();

        if (stream != null) {
            stream.stop(); // flushes what is still buffered
            stream.close();
        }
        long offsetAfter = clockOffset();

        System.out.printf("mode=%s elapsed=%.1f ms acquisitions=%d%n", probe ? "jfr" : "baseline", workload.elapsedNanos / 1e6, workload.acquisitions.size());
        if (!probe) return;

        System.out.printf("clock offset drift over the run: %d µs%n", (offsetAfter - offsetBefore) / 1000);
        // Only the workload's threads: JFR reports contention on every monitor in the JVM, its own included.
        Map<Long, List<Workload.Acquisition>> byThread = new HashMap<>();
        for (Workload.Acquisition a : workload.acquisitions) byThread.computeIfAbsent(a.threadId(), k -> new ArrayList<>()).add(a);
        List<Seen> enters = seen.stream().filter(s -> s.type.equals("jdk.JavaMonitorEnter") && byThread.containsKey(s.threadId)).toList();
        List<Seen> waitEvents = seen.stream().filter(s -> s.type.equals("jdk.JavaMonitorWait")).toList();
        System.out.printf("JavaMonitorEnter events=%d, JavaMonitorWait events=%d (wait rounds=%d)%n", enters.size(), waitEvents.size(), waits);
        System.out.printf("with stack trace: %d of %d; sample top frame: %s%n",
            enters.stream().filter(Seen::hasStack).count(), enters.size(), enters.isEmpty() ? "-" : enters.get(0).topFrame);

        // Delivery latency: how long after the contention ended did the consumer hear of it.
        long[] latency = enters.stream().mapToLong(s -> s.arrivedNanos - s.endNanos).sorted().toArray();
        if (latency.length > 0) {
            System.out.printf("delivery latency ms: min=%.1f median=%.1f p99=%.1f max=%.1f; last event arrived %.1f ms after the workload ended%n",
                latency[0] / 1e6, latency[latency.length / 2] / 1e6, latency[(int) (latency.length * 0.99)] / 1e6, latency[latency.length - 1] / 1e6,
                (enters.stream().mapToLong(Seen::arrivedNanos).max().getAsLong() - workloadEnd) / 1e6);
        }

        // Ordering. The program bracketed every acquisition with [asked, got] on the nanoTime clock. An event merged
        // into the agent's order by timestamp is only placed correctly if its own interval falls inside the bracket of
        // the acquisition it describes.
        int inside = 0, unmatched = 0;
        List<Long> startError = new ArrayList<>();
        for (Seen event : enters) {
            Workload.Acquisition best = null;
            long bestDistance = Long.MAX_VALUE;
            for (Workload.Acquisition a : byThread.getOrDefault(event.threadId, List.of())) {
                long distance = Math.abs(a.gotNanos() - event.endNanos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = a;
                }
            }
            if (best == null) {
                unmatched++;
                continue;
            }
            long overshoot = Math.max(Math.max(best.askedNanos() - event.startNanos, event.endNanos - best.gotNanos()), 0);
            if (overshoot == 0) inside++;
            startError.add(overshoot);
        }
        long[] errors = startError.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf("events whose interval lies inside the program's own [asked, got] bracket: %d of %d (unmatched %d)%n", inside, enters.size(), unmatched);
        if (errors.length > 0) {
            System.out.printf("overshoot outside the bracket, ns: median=%d p90=%d p99=%d max=%d%n",
                errors[errors.length / 2], errors[(int) (errors.length * 0.9)], errors[(int) (errors.length * 0.99)], errors[errors.length - 1]);
        }

        // Completeness: which of the program's acquisitions that visibly waited have an event.
        long[] thresholds = {10_000, 100_000, 1_000_000};
        for (long threshold : thresholds) {
            long waited = workload.acquisitions.stream().filter(a -> a.waitedNanos() >= threshold).count();
            System.out.printf("acquisitions that waited >= %d µs: %d%n", threshold / 1000, waited);
        }
        System.out.println("shortest contention reported, µs: " + enters.stream().mapToLong(s -> s.endNanos - s.startNanos).min().orElse(-1) / 1000);
    }

    private static Seen toSeen(RecordedEvent event, long offset) {
        long arrived = System.nanoTime();
        var thread = event.getThread("eventThread");
        var stack = event.getStackTrace();
        String top = stack == null || stack.getFrames().isEmpty() ? null
            : stack.getFrames().get(0).getMethod().getType().getName() + "." + stack.getFrames().get(0).getMethod().getName();
        return new Seen(event.getEventType().getName(), thread == null ? -1 : thread.getJavaThreadId(),
            toNanoTime(event.getStartTime(), offset), toNanoTime(event.getEndTime(), offset), arrived, stack != null, top);
    }

    /** nanoTime minus wall clock, in nanoseconds: the best of a few samples taken back to back. */
    private static long clockOffset() {
        long best = 0, bestWidth = Long.MAX_VALUE;
        for (int i = 0; i < 1000; i++) {
            long before = System.nanoTime();
            Instant now = Instant.now();
            long after = System.nanoTime();
            if (after - before < bestWidth) {
                bestWidth = after - before;
                best = (before + after) / 2 - (now.getEpochSecond() * 1_000_000_000L + now.getNano());
            }
        }
        return best;
    }

    private static long toNanoTime(Instant instant, long offset) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano() + offset;
    }
}
