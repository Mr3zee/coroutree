package kotlinx.coroutree.runtime;

import java.util.Arrays;

/** What the runtime knows about the current thread. Thread-confined, reached through a thread local. */
final class ThreadState {
    private static final ThreadLocal<ThreadState> LOCAL = new ThreadLocal<ThreadState>() {
        @Override
        protected ThreadState initialValue() {
            return new ThreadState(Thread.currentThread());
        }
    };

    static ThreadState current() {
        return LOCAL.get();
    }

    final Thread thread;

    /**
     * Set while a hook runs. Hooks use the JDK and the JDK is instrumented, so without this a hook could observe,
     * and recurse into, itself. Permanently set for threads that are not part of the picture, see the constructor.
     */
    boolean inHook;

    /** Node of this thread; created on first use. */
    ThreadNode node;

    // --- blocking calls in progress in the innermost unit ---

    /** Nesting depth of instrumented blocking methods: {@code Thread.join} waits with {@code Object.wait}, and so on. */
    int blockDepth;
    /** Depth at which THREAD_BLOCKED was emitted, 0 if it was not. Only the outermost blocking call is reported. */
    int blockEmittedAt;
    /** Coroutine named in the pending THREAD_BLOCKED, repeated in the matching THREAD_UNBLOCKED. */
    long blockOtherNodeId;

    // --- execution units running on this thread, innermost last, each with the blocking state of the unit below it ---

    private static final int MAX_UNITS = 1024;

    private JobNode[] units = new JobNode[8];
    private int[] savedBlockDepth = new int[8];
    private int[] savedBlockEmittedAt = new int[8];
    private long[] savedBlockOtherNodeId = new long[8];
    private int unitCount;

    private ThreadState(Thread thread) {
        this.thread = thread;
        // Besides the agent's own threads: the JVM's pseudo-thread that waits for the program to end and then runs the
        // shutdown sequence. What it does is the JVM going down (and waiting for this agent's shutdown hook), not the program.
        this.inHook = thread instanceof AgentThread || "DestroyJavaVM".equals(thread.getName());
    }

    JobNode currentUnit() {
        return unitCount == 0 ? null : units[unitCount - 1];
    }

    /**
     * A coroutine starts or continues on this thread. It gets its own blocking-call bookkeeping: a coroutine that
     * sleeps inside {@code runBlocking}'s event loop is a blocking call of its own, not a detail of {@code runBlocking}.
     */
    void pushUnit(JobNode unit) {
        if (unitCount > 0 && units[unitCount - 1] == unit) return;
        if (unitCount == units.length) grow();
        units[unitCount] = unit;
        savedBlockDepth[unitCount] = blockDepth;
        savedBlockEmittedAt[unitCount] = blockEmittedAt;
        savedBlockOtherNodeId[unitCount] = blockOtherNodeId;
        unitCount++;
        blockDepth = 0;
        blockEmittedAt = 0;
        blockOtherNodeId = 0;
    }

    /** The unit suspended or finished its code: it, and everything started undispatched on top of it, is off this thread. */
    void popUnit(JobNode unit) {
        int index = unitCount - 1;
        while (index >= 0 && units[index] != unit) index--;
        if (index < 0) return;
        blockDepth = savedBlockDepth[index];
        blockEmittedAt = savedBlockEmittedAt[index];
        blockOtherNodeId = savedBlockOtherNodeId[index];
        Arrays.fill(units, index, unitCount, null);
        unitCount = index;
    }

    private void grow() {
        if (units.length >= MAX_UNITS) {
            // Nesting this deep is not a program, it is entries that were never popped. Forget the oldest half.
            int keep = unitCount / 2;
            int from = unitCount - keep;
            System.arraycopy(units, from, units, 0, keep);
            System.arraycopy(savedBlockDepth, from, savedBlockDepth, 0, keep);
            System.arraycopy(savedBlockEmittedAt, from, savedBlockEmittedAt, 0, keep);
            System.arraycopy(savedBlockOtherNodeId, from, savedBlockOtherNodeId, 0, keep);
            Arrays.fill(units, keep, unitCount, null);
            unitCount = keep;
            return;
        }
        int capacity = units.length * 2;
        units = Arrays.copyOf(units, capacity);
        savedBlockDepth = Arrays.copyOf(savedBlockDepth, capacity);
        savedBlockEmittedAt = Arrays.copyOf(savedBlockEmittedAt, capacity);
        savedBlockOtherNodeId = Arrays.copyOf(savedBlockOtherNodeId, capacity);
    }
}
