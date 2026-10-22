package kotlinx.coroutree.runtime;

import java.lang.ref.WeakReference;

/**
 * Attached to the job through {@link Tagged}, or weakly keyed by the root frame for a coroutine without a job.
 * Must not extend the lifetime of application objects beyond the job.
 */
final class JobNode extends PaceNode {
    static final int NEW = 0;
    static final int RUNNING = 1;
    static final int SUSPENDED = 2;

    /** Runs in the thread that created it, without dispatch, until its first suspension: coroutineScope and the like. */
    final boolean startsUndispatched;
    /** An exception it ends with stays inside until somebody awaits it: async. */
    final boolean deferred;

    /**
     * The caller that receives a scope's or runBlocking's rethrown failure. Id 0 means failure goes to the parent job.
     * {@code rethrowsTo} is null when the caller is a thread.
     */
    long rethrowsToId;
    JobNode rethrowsTo;

    /**
     * The caller shares this node's execution-control flow while it waits for the node.
     * Cleared when the node ends. Null without a gate.
     */
    volatile PaceNode caller;

    /** Described elements used to compare child contexts against this context. */
    ContextEntry[] context;

    // Guarded by this because debug probes for one coroutine can race across threads.
    int run = NEW;
    long runThread;
    /** Suspensions already recorded on behalf of a thread that has yet to report them itself. */
    int lateSuspends;

    volatile boolean cancelling;
    volatile boolean finished;
    /**
     * A job attached to a cancelled parent can emit events from its constructor before the constructor hook defines it.
     */
    volatile boolean defined;

    // The library reports child failure twice: on cancellation and on completion.
    private WeakReference<Throwable> propagated;
    private WeakReference<Throwable> stopped;
    /** Failure of a scope this node called into, on its way to be rethrown in this node's code. */
    private volatile WeakReference<Throwable> received;

    JobNode(long id, boolean startsUndispatched, boolean deferred) {
        super(id);
        this.startsUndispatched = startsUndispatched;
        this.deferred = deferred;
    }

    @Override
    PaceNode flow() {
        PaceNode node = this;
        while (node instanceof JobNode job) {
            PaceNode next = job.caller;
            if (next == null) break;
            node = next;
        }
        return node;
    }

    @Override
    boolean isFinished() {
        return finished;
    }

    synchronized boolean hasPropagated(Throwable exception) {
        return propagated != null && propagated.get() == exception;
    }

    synchronized boolean hasStopped(Throwable exception) {
        return stopped != null && stopped.get() == exception;
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
     * With stack trace recovery, kotlinx.coroutines can rethrow a copy whose cause is the original exception.
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
