package spike;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Threads fighting over one monitor, with the truth about every acquisition written down by the program itself:
 * when the thread asked for the monitor and when it got it, on the clock the agent's global order is built on
 * ({@link System#nanoTime}) and with a global sequence number taken right before and right after, the way
 * {@code Hooks} would. A probe's view of the same contentions is then compared with this.
 *
 * Three phases: long holds (every waiter parks), short holds (contention that lasts microseconds) and wait/notify.
 */
public final class Workload {
    /** One attempt to enter the monitor, as the program saw it. */
    public record Acquisition(long threadId, long askedNanos, long gotNanos, long seqBefore, long seqAfter) {
        public long waitedNanos() {
            return gotNanos - askedNanos;
        }
    }

    public static final AtomicLong SEQ = new AtomicLong();
    public static final Object LOCK = new Object();

    public final List<Acquisition> acquisitions = new ArrayList<>();
    public long elapsedNanos;

    public static Workload run(int threads, int longHolds, int shortHolds, int waits) throws InterruptedException {
        Workload workload = new Workload();
        long start = System.nanoTime();
        workload.contend(threads, longHolds, 2_000_000);   // 2 ms under the lock: everybody else parks
        workload.contend(threads, shortHolds, 20_000);     // 20 µs: spinning often wins, parking is brief
        workload.waitAndNotify(waits);
        workload.elapsedNanos = System.nanoTime() - start;
        return workload;
    }

    private void contend(int threads, int iterations, long holdNanos) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        List<List<Acquisition>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            List<Acquisition> mine = new ArrayList<>(iterations);
            results.add(mine);
            Thread worker = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                long tid = Thread.currentThread().threadId();
                for (int n = 0; n < iterations; n++) {
                    long seqBefore = SEQ.incrementAndGet();
                    long asked = System.nanoTime();
                    synchronized (LOCK) {
                        long got = System.nanoTime();
                        long seqAfter = SEQ.incrementAndGet();
                        mine.add(new Acquisition(tid, asked, got, seqBefore, seqAfter));
                        spin(holdNanos);
                    }
                    spin(holdNanos / 4); // stay away for a moment, or the same thread wins the monitor every time
                }
            }, "contender-" + i);
            workers.add(worker);
            worker.start();
        }
        go.countDown();
        for (Thread worker : workers) worker.join();
        for (List<Acquisition> mine : results) acquisitions.addAll(mine);
    }

    private void waitAndNotify(int rounds) throws InterruptedException {
        Object condition = new Object();
        boolean[] ready = {false};
        for (int round = 0; round < rounds; round++) {
            ready[0] = false;
            Thread waiter = new Thread(() -> {
                synchronized (condition) {
                    while (!ready[0]) {
                        try {
                            condition.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "waiter-" + round);
            waiter.start();
            Thread.sleep(2);
            synchronized (condition) {
                ready[0] = true;
                condition.notifyAll();
            }
            waiter.join();
        }
    }

    private static void spin(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) Thread.onSpinWait();
    }
}
