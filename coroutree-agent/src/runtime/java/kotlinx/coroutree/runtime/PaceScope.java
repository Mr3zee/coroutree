package kotlinx.coroutree.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes require the gate's command lock. Held threads read fields independently without locks.
 * Partial changes are safe because held threads recheck at the next tick.
 */
final class PaceScope {
    /** The node whose subtree this governs, {@code null} for the global setting. */
    final PaceNode node;

    /** Nanoseconds between steps, or 0 for no limit. Ignored while {@link #paused}. */
    volatile long intervalNanos;

    volatile boolean paused;

    /**
     * A new counter on each pause or resume prevents late refunds from releasing steps under a later pause.
     */
    volatile AtomicLong permits = new AtomicLong();

    PaceScope(PaceNode node) {
        this.node = node;
    }

    /** A fixed permit order prevents steps from each holding a permit the other needs. */
    long order() {
        return node == null ? 0 : node.id;
    }
}
