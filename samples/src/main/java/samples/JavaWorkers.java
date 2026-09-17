package samples;

import java.util.ArrayList;
import java.util.List;

/** The Java half of {@code MixedJavaKotlin}: plain threads that call back into Kotlin. */
public final class JavaWorkers {
    private JavaWorkers() {}

    /** Starts {@code count} threads, each of which runs {@code task}, and waits for all of them. */
    public static void runAll(int count, Runnable task) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread thread = new Thread(() -> {
                try {
                    task.run();
                } catch (IllegalStateException e) {
                    System.out.println("java caught: " + e.getMessage());
                }
            }, "java-worker-" + i);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
    }
}
