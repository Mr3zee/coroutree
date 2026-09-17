package kotlinx.coroutree.gui.view

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.tree.CrossLink
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.TraceSnapshot

/** A request to scroll something into view. [ticket] makes two requests for the same target distinct. */
data class Reveal(val target: Long, val ticket: Int)

/** How a row relates to the selected node through a cross-link. `●` stands for the selection. */
data class LinkMark(val kind: CrossLink.Kind, val fromSelection: Boolean) {
    val label: String
        get() = when (kind) {
            CrossLink.Kind.LAUNCHED_FROM -> if (fromSelection) "launched by ●" else "launched ●"
            CrossLink.Kind.CANCELS -> if (fromSelection) "cancelled by ●" else "cancels ●"
            CrossLink.Kind.INTERRUPTS -> if (fromSelection) "interrupted by ●" else "interrupts ●"
        }
}

/**
 * State of one open trace: what is expanded, what is selected, and how the tree and the event log follow each other.
 * Holds no Compose UI, only snapshot state, so it is driven directly from tests.
 */
class TraceViewModel {
    var snapshot: TraceSnapshot by mutableStateOf(TraceSnapshot.EMPTY)
    var expansion: TreeExpansion by mutableStateOf(TreeExpansion())
        private set
    var selectedNodeId: Long? by mutableStateOf(null)
        private set
    var selectedEventSeq: Long? by mutableStateOf(null)
        private set
    var treeReveal: Reveal? by mutableStateOf(null)
        private set
    var logReveal: Reveal? by mutableStateOf(null)
        private set
    private var tickets = 0

    val rows: List<TreeRow> by derivedStateOf { TreeRows.flatten(snapshot, expansion) }

    val selectedNode: NodeSnapshot? by derivedStateOf { selectedNodeId?.let(snapshot::node) }

    val selectedEvent: Event? by derivedStateOf {
        selectedEventSeq?.let { seq -> eventIndex(seq).takeIf { it >= 0 }?.let(snapshot.events::get) }
    }

    /** Marks for the rows linked to the selected node, keyed by row node id. */
    val linkMarks: Map<Long, List<LinkMark>> by derivedStateOf {
        val selected = selectedNode ?: return@derivedStateOf emptyMap()
        selected.links.groupBy(
            keySelector = { if (it.from == selected.id) it.to else it.from },
            valueTransform = { LinkMark(it.kind, fromSelection = it.from == selected.id) },
        ).mapValues { it.value.distinct() }
    }

    fun toggle(row: TreeRow) {
        expansion = expansion.toggled(row.node.id, row.expanded)
    }

    /** Selection made in the tree: the log highlights the node's events and shows the latest of them. */
    fun selectNode(id: Long) {
        selectedNodeId = id
        selectedEventSeq = null
        snapshot.node(id)?.events?.lastOrNull()?.let { logReveal = Reveal(it.seq, ++tickets) }
    }

    /**
     * Selection of an event, in the log or in a node's own list: the owning node gets selected and brought into view
     * in the tree, and the log shows the event (a no-op when it was clicked there).
     */
    fun selectEvent(event: Event) {
        selectedEventSeq = event.seq
        selectedNodeId = event.nodeId
        revealInTree(event.nodeId)
        logReveal = Reveal(event.seq, ++tickets)
    }

    /** Following a reference (parent, creator, cross-link) from the details pane. */
    fun navigateTo(id: Long) {
        selectNode(id)
        revealInTree(id)
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

    fun rowIndex(nodeId: Long): Int = rows.indexOfFirst { it.node.id == nodeId }

    private fun revealInTree(id: Long) {
        expansion = expansion.expanded(TreeRows.ancestors(snapshot, id))
        treeReveal = Reveal(id, ++tickets)
    }
}
