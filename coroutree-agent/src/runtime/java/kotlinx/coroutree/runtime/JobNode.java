package kotlinx.coroutree.runtime;

import java.lang.ref.WeakReference;

/**
 * Runtime state of a coroutine, scope, context change or plain Job. Hangs off the job itself, see {@link Tagged}
 * (a coroutine without a Job: off its root frame, through a weak map), and must not keep application objects alive
 * longer than the job does.
 */
final class JobNode {
    static final int NEW = 0;
    static final int RUNNING = 1;
    static final int SUSPENDED = 2;

    final long id;
    /** Runs in the thread that created it, without dispatch, until its first suspension: coroutineScope and the like. */
    final boolean startsUndispatched;
    /** An exception it ends with stays inside until somebody awaits it: async. */
    final boolean deferred;

    /**
     * Who gets this node's failure thrown at them: the caller of a scope or of runBlocking. 0 for coroutines that
     * report to their parent job instead. The node is {@code null} when the caller is a thread.
     */
    long rethrowsToId;
    JobNode rethrowsTo;

    /** Described context elements, for diffing a child's context against this one. */
    ContextEntry[] context;

    // Guarded by this. Transitions are driven by the debug probes, which may race across threads for one coroutine.
    int run = NEW;
    long runThread;
    /** Suspensions already recorded on behalf of a thread that has yet to report them itself. */
    int lateSuspends;

    volatile boolean cancelling;
    volatile boolean finished;
    /**
     * Whether the node's definition (LAUNCHED or DISCOVERED) is in the trace. A job can make itself heard from inside
     * its own constructor — attached to a parent that is already cancelled, it is cancelled on the spot — and then
     * the node exists, and has events, before the constructor hook gets to define it.
     */
    volatile boolean defined;

    // The library tells a parent about a failing child twice: when the child starts cancelling and when it is final.
    private WeakReference<Throwable> propagated;
    private WeakReference<Throwable> stopped;
    /** Failure of a scope this node called into, on its way to be rethrown in this node's code. */
    private volatile WeakReference<Throwable> received;

    JobNode(long id, boolean startsUndispatched, boolean deferred) {
        this.id = id;
        this.startsUndispatched = startsUndispatched;
        this.deferred = deferred;
    }

    /** False if {@code exception} was already reported as propagated from this node to its parent. */
    synchronized boolean markPropagated(Throwable exception) {
        if (propagated != null && propagated.get() == exception) return false;
        propagated = new WeakReference<>(exception);
        return true;
    }

    /** False if {@code exception} was already reported as stopped at this node's supervisor. */
    synchronized boolean markStopped(Throwable exception) {
        if (stopped != null && stopped.get() == exception) return false;
        stopped = new WeakReference<>(exception);
        return true;
    }

    void markReceived(Throwable exception) {
        received = new WeakReference<>(exception);
    }

    /**
     * Whether {@code failure} is the exception last received from a scope, thrown on. kotlinx.coroutines rethrows
     * either the same instance or, with stack trace recovery, a copy whose cause is the original.
     */
    boolean isRethrowOfReceived(Throwable failure) {
        WeakReference<Throwable> reference = received;
        Throwable original = reference == null ? null : reference.get();
        if (original == null) return false;
        Throwable candidate = failure;
        for (int depth = 0; candidate != null && depth < 4; depth++) {
            if (candidate == original) return true;
            candidate = candidate.getCause();
        }
        return false;
    }
}
