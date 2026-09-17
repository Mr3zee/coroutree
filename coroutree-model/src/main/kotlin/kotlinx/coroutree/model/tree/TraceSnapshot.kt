package kotlinx.coroutree.model.tree

import kotlinx.coroutree.model.ContextChange
import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.NodeInfo
import kotlinx.coroutree.model.NodeState
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
