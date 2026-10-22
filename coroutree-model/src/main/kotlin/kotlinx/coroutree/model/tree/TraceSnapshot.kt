package kotlinx.coroutree.model.tree

import kotlinx.coroutree.model.ContextChange
import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.NodeInfo
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.StackFrameDef
import kotlinx.coroutree.model.TraceHeader

/** Immutable view of a trace at one moment. Safe to hand to another thread. */
public class TraceSnapshot internal constructor(
    public val header: TraceHeader?,
    public val nodes: Map<Long, NodeSnapshot>,
    /** Ids of nodes without a structural parent, in order of appearance. */
    public val roots: List<Long>,
    /** All events in historical order. */
    public val events: List<Event>,
    public val diagnostics: List<Diagnostic>,
    private val frames: List<StackFrameDef?>,
    public val sources: SourceResolver,
    /** The producer is gone: the file ended or the live connection closed. */
    public val complete: Boolean,
    /** Every change of the agent's gate (execution control), in the order of the stream. */
    public val paceChanges: List<PaceDef> = emptyList(),
    /** The gate's settings after the last of [paceChanges]. */
    public val pace: PaceState = PaceState.NONE,
) {
    public fun frame(id: Int): StackFrameDef? = frames.getOrNull(id)

    public fun node(id: Long): NodeSnapshot? = nodes[id]

    public companion object {
        public val EMPTY: TraceSnapshot = TraceSnapshot(
            header = null,
            nodes = emptyMap(),
            roots = emptyList(),
            events = emptyList(),
            diagnostics = emptyList(),
            frames = emptyList(),
            sources = SourceResolver.EMPTY,
            complete = false,
        )
    }
}

public class NodeSnapshot internal constructor(
    public val info: NodeInfo,
    public val state: NodeState,
    /** Structural children in order of creation. */
    public val children: List<Long>,
    /** The node's own events plus events of other nodes that name it as [Event.otherNodeId], in historical order. */
    public val events: List<Event>,
    public val links: List<CrossLink>,
    /** How the node's context differs from its structural parent's: its CONTEXT_CHANGED and DISPATCHER_CHANGED events, folded. */
    public val contextDiff: List<ContextChange>,
    /** Current thread's node id, or 0 if this node isn't running or is itself a thread. */
    public val runsOn: Long,
    /** Whether the node exists only through an id reference, without a definition. */
    public val placeholder: Boolean,
) {
    public val id: Long get() = info.id
}

/** The minimum step interval and pause state for one sequence. */
public data class PaceSetting(
    /** Minimum interval in nanoseconds. Default is 0 for no limit. */
    val intervalNanos: Long = 0,
    val paused: Boolean = false,
) {
    /** Whether this setting imposes no pause or pace limit. */
    public val isOpen: Boolean get() = !paused && intervalNanos == 0L
}

/**
 * Execution-control settings confirmed by the trace stream. Pending commands aren't included.
 * These settings don't identify which threads are held.
 */
public data class PaceState(
    /** The global setting, or `null` if the trace has no execution-control settings yet. Default is `null`. */
    val global: PaceSetting? = null,
    /** Settings that nodes carry for their subtrees, by node id. */
    val nodes: Map<Long, PaceSetting> = emptyMap(),
) {
    /** Returns the nearest setting on the path from [nodeId] to the root, or the global setting. */
    public fun governing(nodeId: Long, parentOf: (Long) -> Long?): PaceSetting? {
        var id: Long? = nodeId
        var hops = 0
        while (id != null && id != 0L && hops++ < MAX_DEPTH) {
            nodes[id]?.let { return it }
            id = parentOf(id)
        }
        return global
    }

    public companion object {
        public val NONE: PaceState = PaceState()
        private const val MAX_DEPTH = 1_000_000
    }
}

/** A non-tree edge. Stored on both of its ends. */
public data class CrossLink(
    val kind: Kind,
    val from: Long,
    val to: Long,
    /** Sequence number of the event that established the link. */
    val seq: Long,
) {
    public enum class Kind {
        /** `from` is the execution unit whose code created `to`, and it isn't `to`'s structural parent. */
        LAUNCHED_FROM,
        CANCELS,
        INTERRUPTS,
    }
}
