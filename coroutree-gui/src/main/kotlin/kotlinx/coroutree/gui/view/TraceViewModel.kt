package kotlinx.coroutree.gui.view

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.GraphLayout
import kotlinx.coroutree.gui.view.graph.GraphLink
import kotlinx.coroutree.gui.view.graph.LayoutEngine
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.TraceSnapshot

/** A request to bring something into view. [ticket] makes two requests for the same target distinct. */
data class Reveal(val target: Long, val ticket: Int)

/**
 * What stands out in the graph besides the selected node: the two nodes of a selected event that names another node
 * (*propagated to*, *cancelled by*), and the edge between them — the structural one if one is the other's parent, and
 * any cross-link that joins them.
 */
data class GraphHighlight(
    val nodes: Set<Long> = emptySet(),
    /** Parent and child of the highlighted structural edge. */
    val structural: Pair<Long, Long>? = null,
    val links: Set<GraphLink> = emptySet(),
) {
    companion object {
        val NONE = GraphHighlight()
    }
}

/**
 * State of one open trace: what the graph holds, what is selected, and how the graph, the details pane and the event
 * log follow each other. Holds no Compose UI, only snapshot state, so it is driven directly from tests.
 */
class TraceViewModel {
    var snapshot: TraceSnapshot by mutableStateOf(TraceSnapshot.EMPTY)
    var options: GraphOptions by mutableStateOf(GraphOptions())
        private set
    var selectedNodeId: Long? by mutableStateOf(null)
        private set
    var selectedEventSeq: Long? by mutableStateOf(null)
        private set
    var graphReveal: Reveal? by mutableStateOf(null)
        private set
    var logReveal: Reveal? by mutableStateOf(null)
        private set
    private var tickets = 0

    /** Viewport and glide of the graph pane. Here rather than in the pane so that it outlives recomposition and tests can see it. */
    val graphView: GraphViewState = GraphViewState()

    /**
     * The graph at the shown moment. The equality policy is what keeps a live trace cheap: most snapshots bring
     * events, not nodes, the model comes out equal to the previous one, and nothing downstream runs again (a derived
     * state has no policy unless given one, and would hand every equal model on as a change).
     */
    val graph: VisibleGraph by derivedStateOf(structuralEqualityPolicy()) { GraphBuilder.build(snapshot, options, selectedNodeId) }

    val layout: GraphLayout by derivedStateOf { LayoutEngine.layout(graph.model) }

    val selectedNode: NodeSnapshot? by derivedStateOf { selectedNodeId?.let(snapshot::node) }

    val selectedEvent: Event? by derivedStateOf {
        selectedEventSeq?.let { seq -> eventIndex(seq).takeIf { it >= 0 }?.let(snapshot.events::get) }
    }

    val highlight: GraphHighlight by derivedStateOf {
        val event = selectedEvent ?: return@derivedStateOf GraphHighlight.NONE
        val a = event.nodeId
        val b = event.otherNodeId
        if (b == 0L || b == a) return@derivedStateOf GraphHighlight.NONE
        val structural = when {
            snapshot.node(a)?.info?.parentId == b -> b to a
            snapshot.node(b)?.info?.parentId == a -> a to b
            else -> null
        }
        val links = graph.model.links.filterTo(HashSet()) { (it.from == a && it.to == b) || (it.from == b && it.to == a) }
        GraphHighlight(setOf(a, b), structural, links)
    }

    fun toggleLinks(kind: EdgeKind) {
        options = options.copy(linkKinds = if (kind in options.linkKinds) options.linkKinds - kind else options.linkKinds + kind)
    }

    fun showLibrary(show: Boolean) {
        options = options.copy(showLibrary = show)
    }

    fun showLabels(show: Boolean) {
        options = options.copy(labels = show)
    }

    /** Selection made in the graph: the log highlights the node's events and shows the latest of them. */
    fun selectNode(id: Long) {
        selectedNodeId = id
        selectedEventSeq = null
        snapshot.node(id)?.events?.lastOrNull()?.let { logReveal = Reveal(it.seq, ++tickets) }
    }

    /**
     * Selection of an event, in the log or in a node's own list: the owning node gets selected and brought into view
     * in the graph, and the log shows the event (a no-op when it was clicked there).
     */
    fun selectEvent(event: Event) {
        selectedEventSeq = event.seq
        selectedNodeId = event.nodeId
        revealInGraph(event.nodeId)
        logReveal = Reveal(event.seq, ++tickets)
    }

    /** Following a reference (parent, creator, cross-link) from the details pane. */
    fun navigateTo(id: Long) {
        selectNode(id)
        revealInGraph(id)
    }

    fun clearSelection() {
        selectedNodeId = null
        selectedEventSeq = null
    }

    fun isHighlighted(event: Event): Boolean {
        val id = selectedNodeId ?: return false
        return event.nodeId == id || event.otherNodeId == id
    }

    /** Position of the event with [seq] in the log, or -1. Sequence numbers ascend but may have gaps. */
    fun eventIndex(seq: Long): Int = snapshot.events.binarySearch { it.seq.compareTo(seq) }.coerceAtLeast(-1)

    /** A node that is left out as library noise can only be shown by showing the library. */
    private fun revealInGraph(id: Long) {
        if (!options.showLibrary && SubtreeFacts(snapshot).isHidden(id)) showLibrary(true)
        graphReveal = Reveal(id, ++tickets)
    }
}
