package kotlinx.coroutree.model.tree

import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind

/**
 * Renders a snapshot as indented text: the form golden-tree tests compare and a readable dump for humans.
 *
 * Everything that differs between two runs of the same deterministic program is left out: ids, timestamps,
 * sequence numbers, thread names in `resumed on …`. What remains is the tree, node states, context diffs and,
 * per node, its events in order.
 */
public class TreeRenderer(
    /** Nodes for which this returns `false` are omitted together with their subtrees. */
    private val includeNode: (NodeSnapshot) -> Boolean = { true },
    /** Events for which this returns `false` are omitted. */
    private val includeEvent: (Event) -> Boolean = { true },
) {
    public fun render(snapshot: TraceSnapshot): String = buildString {
        for (root in snapshot.roots) appendNode(snapshot, root, depth = 0)
    }

    private fun StringBuilder.appendNode(snapshot: TraceSnapshot, id: Long, depth: Int) {
        val node = snapshot.node(id) ?: return
        if (!includeNode(node)) return
        val indent = "  ".repeat(depth)
        append(indent).append(node.title)
        snapshot.frame(node.info.siteFrame)?.shortLocation?.takeIf { it.isNotEmpty() }?.let { append(" @ ").append(it) }
        append(" [").append(node.state.name.lowercase()).append(']')
        val diff = node.contextDiff
        if (diff.isNotEmpty()) append(" {").append(diff.joinToString { it.describe() }).append('}')
        append('\n')
        for (event in node.events) {
            if (event.kind in STRUCTURAL || !includeEvent(event)) continue
            append(indent).append("  - ").append(describeStable(snapshot, event, id)).append('\n')
        }
        for (link in node.links) {
            if (link.from != id) continue
            val target = snapshot.node(link.to) ?: continue
            if (!includeNode(target)) continue
            val verb = when (link.kind) {
                CrossLink.Kind.LAUNCHED_FROM -> "launches"
                CrossLink.Kind.CANCELS -> "cancels"
                CrossLink.Kind.INTERRUPTS -> "interrupts"
            }
            append(indent).append("  ~ ").append(verb).append(' ').append(target.title).append('\n')
        }
        for (child in node.children) appendNode(snapshot, child, depth + 1)
    }

    /** [describe] minus the names of threads a coroutine happened to run on, which are the dispatcher's business. */
    private fun describeStable(snapshot: TraceSnapshot, event: Event, viewpoint: Long): String {
        val ofItsThread = viewpoint == event.otherNodeId
        return when (event.kind) {
            EventKind.RESUMED -> "resumed"
            EventKind.THREAD_BLOCKED if ofItsThread -> "blocked its thread (${event.blockReason.label})"
            EventKind.THREAD_UNBLOCKED if ofItsThread -> "unblocked its thread"
            else -> snapshot.describe(event, viewpoint)
        }
    }

    private companion object {
        // Already visible in the header line of a node.
        val STRUCTURAL = setOf(EventKind.LAUNCHED, EventKind.DISCOVERED, EventKind.CONTEXT_CHANGED, EventKind.DISPATCHER_CHANGED)
    }
}
