package kotlinx.coroutree.model.tree

import kotlinx.coroutree.model.ContextChange
import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.NodeInfo
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.StackFrameDef
import kotlinx.coroutree.model.TraceHeader
import kotlinx.coroutree.model.TraceReader
import java.io.InputStream
import java.util.TreeMap

/**
 * Folds trace frames into the concurrency tree.
 *
 * Not thread-safe. One thread feeds frames and takes snapshots. Other threads can read the immutable snapshots.
 * A snapshot costs O(changed nodes) plus a shallow copy of the node map when nodes change.
 */
public class TraceStore {
    private class MutableNode(var info: NodeInfo, var placeholder: Boolean) {
        val children = AppendLog<Long>()
        val events = AppendLog<Event>()
        var links: List<CrossLink> = emptyList()
        var suspended = false
        var blockedDepth = 0
        var cancelling = false
        var finalState = NodeState.UNSPECIFIED
        var runsOn = 0L

        val state: NodeState
            get() = when {
                finalState != NodeState.UNSPECIFIED -> finalState
                blockedDepth > 0 -> NodeState.BLOCKED
                cancelling -> NodeState.CANCELLING
                suspended -> NodeState.SUSPENDED
                else -> NodeState.ACTIVE
            }

        var contextDiff: List<ContextChange> = emptyList()

        fun snapshot() = NodeSnapshot(info, state, children.view(), events.view(), links, contextDiff, runsOn, placeholder)
    }

    private var header: TraceHeader? = null
    private var sources = SourceResolver.EMPTY
    private val nodes = HashMap<Long, MutableNode>()
    private val roots = AppendLog<Long>()

    // A placeholder starts as a root and moves under its parent when it is defined. The root log can't remove, so
    // snapshot() filters these ids out.
    private val formerRoots = HashSet<Long>()
    private val events = AppendLog<Event>()
    private val frames = AppendLog<StackFrameDef?>().apply { add(null) } // frame ids start at 1
    private var frameCount = 1
    private val diagnostics = AppendLog<Diagnostic>()
    private var complete = false
    private val paceChanges = AppendLog<PaceDef>()
    private var pace = PaceState.NONE

    // Frames of concurrent threads reach the file slightly out of order. Sequence numbers are dense, so an event
    // waits here until its predecessors have arrived.
    private val pending = TreeMap<Long, Event>()
    private var nextSeq = 1L

    private val dirty = LinkedHashSet<MutableNode>()
    private var publishedNodes: Map<Long, NodeSnapshot> = emptyMap()
    private var published: TraceSnapshot? = null

    public fun accept(frame: Frame) {
        published = null
        frame.header?.let {
            header = it
            sources = SourceResolver(it.sourceIndex)
        }
        frame.stackFrame?.let(::defineFrame)
        frame.diagnostic?.let(diagnostics::add)
        frame.event?.let(::enqueue)
        frame.pace?.let(::applyPace)
    }

    /** Marks the stream complete and releases pending events despite sequence gaps. */
    public fun endOfStream() {
        published = null
        drainPending()
        complete = true
    }

    public fun snapshot(): TraceSnapshot {
        published?.let { return it }
        if (dirty.isNotEmpty()) {
            val updated = HashMap(publishedNodes)
            for (node in dirty) updated[node.info.id] = node.snapshot()
            dirty.clear()
            publishedNodes = updated
        }
        val rootIds = if (formerRoots.isEmpty()) roots.view() else roots.view().filter { it !in formerRoots }
        return TraceSnapshot(header, publishedNodes, rootIds, events.view(), diagnostics.view(), frames.view(), sources, complete, paceChanges.view(), pace)
            .also { published = it }
    }

    private fun applyPace(def: PaceDef) {
        paceChanges.add(def)
        val setting = PaceSetting(def.intervalNanos, def.paused)
        pace = when {
            def.scopeNodeId == 0L -> pace.copy(global = setting)
            def.dropped -> pace.copy(nodes = pace.nodes - def.scopeNodeId)
            else -> pace.copy(nodes = pace.nodes + (def.scopeNodeId to setting))
        }
    }

    private fun defineFrame(def: StackFrameDef) {
        if (def.id < frameCount) return
        while (frameCount < def.id) {
            frames.add(null)
            frameCount++
        }
        frames.add(def)
        frameCount++
    }

    private fun enqueue(event: Event) {
        if (event.seq < nextSeq) return // a replayed duplicate
        pending[event.seq] = event
        while (true) {
            val first = pending.firstEntry() ?: break
            if (first.key != nextSeq) break
            pending.pollFirstEntry()
            nextSeq++
            apply(first.value)
        }
        if (pending.size > MAX_PENDING) drainPending()
    }

    private fun drainPending() {
        while (true) {
            val entry = pending.pollFirstEntry() ?: break
            nextSeq = entry.key + 1
            apply(entry.value)
        }
    }

    private fun apply(event: Event) {
        events.add(event)
        val definition = event.node?.takeIf { event.kind == EventKind.LAUNCHED || event.kind == EventKind.DISCOVERED }
        val node = if (definition != null) define(definition) else node(event.nodeId)
        node.events.add(event)
        dirty.add(node)
        val other = event.otherNodeId.takeIf { it != 0L && it != node.info.id }?.let(::node)
        if (other != null) {
            other.events.add(event)
            dirty.add(other)
        }

        when (event.kind) {
            EventKind.LAUNCHED -> {
                val creator = node.info.creatorId
                if (creator != 0L && creator != node.info.parentId) {
                    link(CrossLink(CrossLink.Kind.LAUNCHED_FROM, creator, node.info.id, event.seq))
                }
            }
            EventKind.CONTEXT_CHANGED, EventKind.DISPATCHER_CHANGED -> node.contextDiff = node.contextDiff + event.contextDiff
            EventKind.SUSPENDED -> {
                node.suspended = true
                node.runsOn = 0
            }
            EventKind.RESUMED -> {
                node.suspended = false
                node.runsOn = event.threadId
            }
            EventKind.CANCELLING -> node.cancelling = true
            EventKind.CANCELLATION_REQUESTED ->
                if (other != null) link(CrossLink(CrossLink.Kind.CANCELS, other.info.id, node.info.id, event.seq))
            EventKind.THREAD_INTERRUPTED ->
                if (other != null) link(CrossLink(CrossLink.Kind.INTERRUPTS, other.info.id, node.info.id, event.seq))
            // Blocking nests: a thread blocked in runBlocking runs a coroutine that sleeps.
            EventKind.THREAD_BLOCKED -> {
                node.blockedDepth++
                other?.let { it.blockedDepth++ }
            }
            EventKind.THREAD_UNBLOCKED -> {
                node.blockedDepth = maxOf(0, node.blockedDepth - 1)
                other?.let { it.blockedDepth = maxOf(0, it.blockedDepth - 1) }
            }
            EventKind.FINISHED -> {
                node.finalState = event.finalState.takeIf { it.isFinal } ?: NodeState.COMPLETED
                node.runsOn = 0
            }
            else -> {}
        }
    }

    private fun define(info: NodeInfo): MutableNode {
        val existing = nodes[info.id]
        if (existing != null) {
            if (existing.placeholder) {
                existing.info = info
                existing.placeholder = false
                if (info.parentId != 0L) attach(existing, info.parentId, wasRoot = true)
            }
            return existing
        }
        val node = MutableNode(info, placeholder = false)
        nodes[info.id] = node
        if (info.parentId == 0L) roots.add(info.id) else attach(node, info.parentId, wasRoot = false)
        return node
    }

    private fun attach(node: MutableNode, parentId: Long, wasRoot: Boolean) {
        val parent = node(parentId)
        parent.children.add(node.info.id)
        dirty.add(parent)
        if (wasRoot) formerRoots.add(node.info.id)
    }

    private fun node(id: Long): MutableNode = nodes.getOrPut(id) {
        roots.add(id)
        MutableNode(NodeInfo(id = id), placeholder = true).also(dirty::add)
    }

    private fun link(link: CrossLink) {
        for (end in setOf(link.from, link.to)) {
            val node = node(end)
            node.links = node.links + link
            dirty.add(node)
        }
    }

    public companion object {
        private const val MAX_PENDING = 4096

        /** Reads a whole recorded trace. */
        public fun read(input: InputStream): TraceSnapshot {
            val store = TraceStore()
            TraceReader(input).use { reader -> reader.frames().forEach(store::accept) }
            store.endOfStream()
            return store.snapshot()
        }
    }
}
