package kotlinx.coroutree.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * What the gate ({@link Pace}) needs of a runtime node: where it hangs in the structure, so that a setting on a node
 * holds for its subtree, and when the last step happened to it, so that the next one keeps its distance.
 * Without a gate none of it exists: no link is set, no time is kept, the node is not registered anywhere.
 */
abstract class PaceNode {
    final long id;

    /**
     * Runtime node of the structural parent: ours, never the application's object. {@code null} for a root, for a
     * node whose definition has not been seen yet, and always without a gate.
     */
    volatile PaceNode paceParent;

    /** The setting this node carries for its subtree, {@code null} while it goes by its parent's. Written under the gate's command lock. */
    volatile PaceScope scope;

    /** When the last step happened to this node (and, for a flow, was made by it), {@link Pace#NEVER} before the first. */
    final AtomicLong lastStep;

    PaceNode(long id) {
        this.id = id;
        Pace gate = Pace.GATE;
        lastStep = gate == null ? null : new AtomicLong(Pace.NEVER);
        if (gate != null) gate.register(this);
    }

    /**
     * The line of execution this node's code is part of: itself, unless it runs in place of a caller that waits for it
     * (a scope, a context change, runBlocking), in which case it is the caller's.
     */
    PaceNode flow() {
        return this;
    }

    /** A node that has ended takes no setting and keeps none. */
    abstract boolean isFinished();
}
