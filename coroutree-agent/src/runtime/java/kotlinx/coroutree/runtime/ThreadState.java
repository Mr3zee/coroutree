package kotlinx.coroutree.runtime;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-confined state. */
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
     * Prevents instrumented JDK calls inside hooks from recursively entering hooks.
     * Permanently set for agent and DestroyJavaVM threads.
     */
    boolean inHook;

    /**
     * JVM system threads are traced but never held. Holding the Signal Dispatcher in Thread.start would block
     * SIGTERM handling and thread dumps while the program is paused.
     */
    final boolean neverHeld;

    /** Created on first use. */
    ThreadNode node;

    /** Nesting depth of instrumented blocking methods: {@code Thread.join} waits with {@code Object.wait}, and so on. */
    int blockDepth;
    /** Depth at which THREAD_BLOCKED was emitted, or 0 without an event. Only the outermost blocking call is reported. */
    int blockEmittedAt;
    /** Coroutine named in the pending THREAD_BLOCKED, repeated in the matching THREAD_UNBLOCKED. */
    long blockOtherNodeId;

    /** An unsettled step passed the gate. {@link Tracer#emit} requires this before it records an event. */
    boolean awaited;
    /** The step has an event already: the next one of this hook call is part of the same step. */
    boolean stepEmitted;
    /** First event time, or {@link Pace#NEVER} if the step passed an open gate. */
    long releaseNanos = Pace.NEVER;
    /** How long this thread was held since its last event. Reported with its next event, whichever hook call that is in. */
    long heldNanos;

    // A step with no events must restore its sequence slots and permits.
    int claimedSlots;
    int claimedPermits;
    long claimTime;
    final PaceNode[] claimSlot = new PaceNode[3];
    final long[] claimPrevious = new long[3];
    final AtomicLong[] claimPermit = new AtomicLong[3];

    void claim(PaceNode slot, long previous) {
        claimSlot[claimedSlots] = slot;
        claimPrevious[claimedSlots++] = previous;
    }

    void claim(AtomicLong permits) {
        claimPermit[claimedPermits++] = permits;
    }

    void exitHook() {
        if (awaited) Pace.settle(this);
        inHook = false;
    }

    // Units are innermost last. Each entry saves the enclosing unit's blocking state.

    private static final int MAX_UNITS = 1024;

    private JobNode[] units = new JobNode[8];
    private int[] savedBlockDepth = new int[8];
    private int[] savedBlockEmittedAt = new int[8];
    private long[] savedBlockOtherNodeId = new long[8];
    private int unitCount;

    private ThreadState(Thread thread) {
        this.thread = thread;
        // DestroyJavaVM waits for shutdown hooks, so tracing its waits would record the agent's own shutdown work.
        this.inHook = thread instanceof AgentThread || "DestroyJavaVM".equals(thread.getName());
        ThreadGroup group = thread.getThreadGroup(); // null once the thread has terminated
        this.neverHeld = group != null && group.getParent() == null; // JVM threads belong to the root "system" group
    }

    JobNode currentUnit() {
        return unitCount == 0 ? null : units[unitCount - 1];
    }

    /**
     * Each coroutine needs separate blocking state so its sleep inside {@code runBlocking} is reported independently.
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

    /** Removes the unit and any undispatched units nested inside it, then restores the enclosing unit's blocking state. */
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
            // Bound memory use if unmatched entries accumulate.
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
