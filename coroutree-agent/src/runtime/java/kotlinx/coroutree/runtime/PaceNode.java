package kotlinx.coroutree.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Without a gate, nodes have no pacing ancestry, timestamp storage, or registry entry.
 */
abstract class PaceNode {
    final long id;

    /**
     * Null for roots, undefined nodes, and nodes without a gate. Must reference only runtime nodes, never application objects.
     */
    volatile PaceNode paceParent;

    /** Null means inherited. Writes require the gate's command lock. */
    volatile PaceScope scope;

    /** Last step on this node or from this flow. {@link Pace#NEVER} before the first step, null without a gate. */
    final AtomicLong lastStep;

    PaceNode(long id) {
        this.id = id;
        Pace gate = Pace.GATE;
        lastStep = gate == null ? null : new AtomicLong(Pace.NEVER);
        if (gate != null) gate.register(this);
    }

    /**
     * Scopes, context changes, and runBlocking share the flow of the caller that waits for them.
     */
    PaceNode flow() {
        return this;
    }

    /** Finished nodes can't have local settings. */
    abstract boolean isFinished();
}
