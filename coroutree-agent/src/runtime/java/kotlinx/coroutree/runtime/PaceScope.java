package kotlinx.coroutree.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A setting of the gate: the global one, or the one a node carries for its subtree. Written by the threads that read
 * commands (under the gate's command lock), read by held threads without any lock: every field stands for itself and
 * a thread that catches a change half-way looks again a tick later.
 */
final class PaceScope {
    /** The node whose subtree this governs, {@code null} for the global setting. */
    final PaceNode node;

    /** Minimum distance between two steps of one sequence, 0 for no limit. Not looked at while {@link #paused}. */
    volatile long intervalNanos;

    volatile boolean paused;

    /**
     * Steps that may pass while paused. A new counter with every pause and resume, so that a permit handed back late
     * (by a hook that took one and then had nothing to report) cannot open a gate that was closed in the meantime.
     */
    volatile AtomicLong permits = new AtomicLong();

    PaceScope(PaceNode node) {
        this.node = node;
    }

    /** The order in which a step that needs several scopes asks them for permits: two steps never hold one each and wait. */
    long order() {
        return node == null ? 0 : node.id;
    }
}
