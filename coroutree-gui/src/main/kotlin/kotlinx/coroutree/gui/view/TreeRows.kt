package kotlinx.coroutree.gui.view

import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.TraceSnapshot

/** One visible line of the tree pane. */
data class TreeRow(
    val node: NodeSnapshot,
    val depth: Int,
    val expanded: Boolean,
    /** Library machinery with no project code anywhere below it. */
    val dimmed: Boolean,
) {
    val hasChildren: Boolean get() = node.children.isNotEmpty()
}

/**
 * Which nodes are open. Only the user's explicit choices are stored; everything else follows [expandedByDefault],
 * so nodes that appear later in a live trace get the right state without anybody visiting them.
 */
data class TreeExpansion(private val overrides: Map<Long, Boolean> = emptyMap()) {
    fun toggled(id: Long, currentlyExpanded: Boolean): TreeExpansion = TreeExpansion(overrides + (id to !currentlyExpanded))

    fun expanded(ids: Iterable<Long>): TreeExpansion = TreeExpansion(overrides + ids.associateWith { true })

    fun isExpanded(node: NodeSnapshot, facts: SubtreeFacts): Boolean = overrides[node.id] ?: expandedByDefault(node, facts)

    companion object {
        /** Pools and library-internal subtrees start closed: they are there when wanted and out of the way otherwise. */
        fun expandedByDefault(node: NodeSnapshot, facts: SubtreeFacts): Boolean =
            node.info.kind != NodeKind.POOL && !facts.isLibraryInternal(node)
    }
}

/** Per-snapshot, memoized answers to "is there project code under this node". */
class SubtreeFacts(private val snapshot: TraceSnapshot) {
    private val containsProject = HashMap<Long, Boolean>()

    fun isLibraryInternal(node: NodeSnapshot): Boolean = node.info.origin == Origin.LIBRARY && !containsProject(node.id)

    private fun containsProject(root: Long): Boolean {
        containsProject[root]?.let { return it }
        // Iterative depth-first search: coroutine trees can be deep enough to overflow the stack. Each entry remembers
        // how far it got through its children, so a node with 10^5 of them costs 10^5 steps, not 10^10.
        val path = ArrayDeque<Visit>().apply { add(Visit(root)) }
        while (path.isNotEmpty()) {
            val visit = path.last()
            val node = snapshot.node(visit.id)
            val known = when {
                node == null -> false
                node.info.origin == Origin.PROJECT || visit.found -> true
                visit.nextChild == node.children.size -> false
                else -> null
            }
            if (known != null) {
                containsProject[visit.id] = known
                path.removeLast()
                if (known) path.lastOrNull()?.found = true // one is enough: the parent need not look further
                continue
            }
            val child = node!!.children[visit.nextChild++]
            when (containsProject[child]) {
                true -> visit.found = true
                false -> {}
                null -> path.add(Visit(child))
            }
        }
        return containsProject.getValue(root)
    }

    private class Visit(val id: Long) {
        var nextChild = 0
        var found = false
    }
}

object TreeRows {
    fun flatten(snapshot: TraceSnapshot, expansion: TreeExpansion): List<TreeRow> {
        val facts = SubtreeFacts(snapshot)
        val rows = ArrayList<TreeRow>()
        // Explicit stack of (id, depth), children pushed in reverse to come out in creation order.
        val stack = ArrayDeque<Pair<Long, Int>>()
        for (root in snapshot.roots.asReversed()) stack.add(root to 0)
        while (stack.isNotEmpty()) {
            val (id, depth) = stack.removeLast()
            val node = snapshot.node(id) ?: continue
            val expanded = node.children.isNotEmpty() && expansion.isExpanded(node, facts)
            rows += TreeRow(node, depth, expanded, facts.isLibraryInternal(node))
            if (expanded) for (child in node.children.asReversed()) stack.add(child to depth + 1)
        }
        return rows
    }

    /** Structural ancestors of [id], nearest first. */
    fun ancestors(snapshot: TraceSnapshot, id: Long): List<Long> {
        val result = LinkedHashSet<Long>()
        var current = snapshot.node(id)?.info?.parentId ?: 0L
        while (current != 0L && result.add(current)) { // the guard is against a corrupt trace with a parent cycle
            current = snapshot.node(current)?.info?.parentId ?: 0L
        }
        return result.toList()
    }
}
