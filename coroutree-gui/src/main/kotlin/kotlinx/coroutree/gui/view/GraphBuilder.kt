package kotlinx.coroutree.gui.view

import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.GraphLink
import kotlinx.coroutree.gui.view.graph.GraphMetrics
import kotlinx.coroutree.gui.view.graph.GraphModel
import kotlinx.coroutree.gui.view.graph.GraphNode
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.tree.CrossLink
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.RUNS_ON_VERB
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.title
import kotlinx.coroutree.model.tree.verb

/** What the toolbar of the graph decides. */
data class GraphOptions(
    /** Pools and library-internal subtrees are in the graph (dimmed) rather than left out. */
    val showLibrary: Boolean = false,
    val linkKinds: Set<EdgeKind> = EdgeKind.entries.toSet(),
    /** Cross-links carry their type as a label where there is room for it. */
    val labels: Boolean = true,
)

/** The graph to draw, and how much of the trace was left out of it as library noise. */
data class VisibleGraph(val model: GraphModel, val hiddenNodes: Int) {
    companion object {
        val EMPTY = VisibleGraph(GraphModel.EMPTY, 0)
    }
}

val EdgeKind.label: String
    get() = when (this) {
        EdgeKind.LAUNCHED_FROM -> CrossLink.Kind.LAUNCHED_FROM.verb
        EdgeKind.CANCELS -> CrossLink.Kind.CANCELS.verb
        EdgeKind.INTERRUPTS -> CrossLink.Kind.INTERRUPTS.verb
        EdgeKind.RUNS_ON -> RUNS_ON_VERB
    }

private val CrossLink.Kind.edgeKind: EdgeKind
    get() = when (this) {
        CrossLink.Kind.LAUNCHED_FROM -> EdgeKind.LAUNCHED_FROM
        CrossLink.Kind.CANCELS -> EdgeKind.CANCELS
        CrossLink.Kind.INTERRUPTS -> EdgeKind.INTERRUPTS
    }

val NodeSnapshot.graphTitle: String get() = if (placeholder) "node #$id" else title

/** Per-snapshot, memoized answers to "is there project code under this node". */
class SubtreeFacts(private val snapshot: TraceSnapshot) {
    private val containsProject = HashMap<Long, Boolean>()

    /**
     * Library noise (DESIGN §6.1): a pool, or a node of library origin, with no project code anywhere below it.
     * What hangs below such a node is noise as well, whatever it says about itself.
     */
    fun isLibraryNoise(node: NodeSnapshot): Boolean =
        (node.info.origin == Origin.LIBRARY || node.info.kind == NodeKind.POOL) && !containsProject(node.id)

    /** Whether [id] is left out of the graph while "show library / pools" is off: it, or an ancestor of it, is noise. */
    fun isHidden(id: Long): Boolean {
        val seen = HashSet<Long>()
        var current = snapshot.node(id)
        while (current != null && seen.add(current.id)) { // the guard is against a corrupt trace with a parent cycle
            if (isLibraryNoise(current)) return true
            current = snapshot.node(current.info.parentId)
        }
        return false
    }

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

/** Turns a snapshot into the graph to draw: which nodes are in it, in which order, and which cross-links. */
object GraphBuilder {
    fun build(snapshot: TraceSnapshot, options: GraphOptions = GraphOptions(), selectedNodeId: Long? = null): VisibleGraph {
        val facts = SubtreeFacts(snapshot)
        val nodes = ArrayList<GraphNode>()
        val visible = HashSet<Long>()
        // Depth first, children pushed in reverse to come out in creation order; `dimmed` is inherited down a noisy subtree.
        val stack = ArrayDeque<Pair<Long, Boolean>>()
        for (root in snapshot.roots.asReversed()) stack.add(root to false)
        while (stack.isNotEmpty()) {
            val (id, inNoise) = stack.removeLast()
            val node = snapshot.node(id) ?: continue
            val noise = inNoise || facts.isLibraryNoise(node)
            if (noise && !options.showLibrary) continue
            if (!visible.add(id)) continue // a corrupt trace that lists a node under two parents
            val title = node.graphTitle
            val parent = node.info.parentId.takeIf { it in visible } ?: 0L
            nodes += GraphNode(id, parent, title, GraphMetrics.nodeWidth(title.length), dimmed = noise)
            for (child in node.children.asReversed()) stack.add(child to noise)
        }

        val links = LinkedHashSet<GraphLink>()
        fun add(kind: EdgeKind, from: Long, to: Long) {
            if (kind !in options.linkKinds || from == to || from !in visible || to !in visible) return
            links += GraphLink(kind, from, to, if (options.labels) GraphMetrics.labelWidth(kind.label.length) else 0)
        }
        for (graphNode in nodes) {
            // A link is stored on both of its ends; the one it starts at speaks for it.
            for (link in snapshot.node(graphNode.id)!!.links) if (link.from == graphNode.id) add(link.kind.edgeKind, link.from, link.to)
        }
        // Every running coroutine runs on some thread; drawing that for all of them would bury the graph, so it is
        // drawn for the selection: the thread under a selected coroutine, the coroutines on a selected thread.
        val selected = selectedNodeId?.takeIf { it in visible }?.let(snapshot::node)
        if (selected != null && EdgeKind.RUNS_ON in options.linkKinds) {
            if (selected.runsOn != 0L) add(EdgeKind.RUNS_ON, selected.id, selected.runsOn)
            for (graphNode in nodes) if (snapshot.node(graphNode.id)!!.runsOn == selected.id) add(EdgeKind.RUNS_ON, graphNode.id, selected.id)
        }
        return VisibleGraph(GraphModel(nodes, links.toList()), hiddenNodes = snapshot.nodes.size - nodes.size)
    }
}
