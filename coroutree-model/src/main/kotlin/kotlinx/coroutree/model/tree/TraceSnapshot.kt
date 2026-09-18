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
    /** Node id of the thread this node runs on right now, 0 when it is not running (or is a thread itself). */
    public val runsOn: Long,
    /** The definition was never seen; the node exists only because something referred to its id. */
    public val placeholder: Boolean,
) {
    public val id: Long get() = info.id
}

/** One setting of the gate: how far apart the steps of a sequence are kept, or that they are not let through at all. */
public data class PaceSetting(
    /** 0 = no limit. */
    val intervalNanos: Long = 0,
    val paused: Boolean = false,
) {
    /** Holds nobody. */
    public val isOpen: Boolean get() = !paused && intervalNanos == 0L
}

/**
 * The gate as the trace says it is: the settings as read back from the stream, not as somebody last asked for.
 * It says what is *set*; which threads are being held at this moment nobody outside the JVM knows.
 */
public data class PaceState(
    /** `null`: the trace has said nothing about a gate (the JVM has none, or has not got to say). */
    val global: PaceSetting? = null,
    /** Settings that nodes carry for their subtrees, by node id. */
    val nodes: Map<Long, PaceSetting> = emptyMap(),
) {
    /** What governs [nodeId]: the innermost setting on its way to the root, else the global one. */
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
        /** `from` is the execution unit whose code created `to`, and it is not `to`'s structural parent. */
        LAUNCHED_FROM,
        CANCELS,
        INTERRUPTS,
    }
}
