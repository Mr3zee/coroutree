package kotlinx.coroutree.runtime;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * A thread of the JVM itself (Signal Dispatcher, Reference Handler, Finalizer, the "SIGTERM handler" the dispatcher
     * starts): it is in the picture like any thread, but the gate never holds it. It is not the program's, and the JVM
     * needs it: held at a paused gate inside the Thread.start of the SIGTERM handler, the dispatcher would never
     * deliver the signal, and a paused program could not be ended with Ctrl-C — nor asked for a thread dump.
     */
    final boolean neverHeld;

    /** Node of this thread; created on first use. */
    ThreadNode node;

    // --- blocking calls in progress in the innermost unit ---

    /** Nesting depth of instrumented blocking methods: {@code Thread.join} waits with {@code Object.wait}, and so on. */
    int blockDepth;
    /** Depth at which THREAD_BLOCKED was emitted, 0 if it was not. Only the outermost blocking call is reported. */
    int blockEmittedAt;
    /** Coroutine named in the pending THREAD_BLOCKED, repeated in the matching THREAD_UNBLOCKED. */
    long blockOtherNodeId;

    // --- the step this thread is making through the gate (Pace); all of it stays untouched without a gate ---

    /** A hook call has passed the gate and not settled yet. {@link Tracer#emit} insists on it: no event without its await. */
    boolean awaited;
    /** The step has an event already: the next one of this hook call is part of the same step. */
    boolean stepEmitted;
    /** When the gate let the step go, which becomes the time of its first event; {@link Pace#NEVER} if it went through an open gate. */
    long releaseNanos = Pace.NEVER;
    /** How long this thread was held since its last event. Reported with its next event, whichever hook call that is in. */
    long heldNanos;

    // What the step took, to be given back if it turns out to have nothing to report: the slots of its sequences with
    // the times they held before, and the counters it took permits from.
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

    /** The end of every hook: the step, if there was one, is settled, and the thread is the program's again. */
    void exitHook() {
        if (awaited) Pace.settle(this);
        inHook = false;
    }

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
        ThreadGroup group = thread.getThreadGroup(); // null once the thread has terminated
        this.neverHeld = group != null && group.getParent() == null; // the root group, "system": where the JVM keeps its own
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
