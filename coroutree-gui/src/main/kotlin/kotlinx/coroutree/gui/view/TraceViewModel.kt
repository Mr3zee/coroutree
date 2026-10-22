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
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.PaceSetting
import kotlinx.coroutree.model.tree.TraceSnapshot

/** [ticket] distinguishes repeated requests for the same target. */
data class Reveal(val target: Long, val ticket: Int)

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

    val graphView: GraphViewState = GraphViewState()

    /**
     * Structural equality avoids layout work when new events leave the visible graph unchanged.
     * Without an explicit policy, derived state reports equal models as changes.
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

    /** Set by the feed owner while connected to a running JVM. Null for recorded or ended sessions. */
    var commands: ((PaceCommand) -> Unit)? by mutableStateOf(null)

    val paceable: Boolean by derivedStateOf { commands != null && snapshot.header?.paceable == true && !snapshot.complete }

    val globalPace: PaceSetting? by derivedStateOf { snapshot.pace.global }

    val pausedAtStart: Boolean by derivedStateOf {
        snapshot.pace.global?.paused == true && snapshot.paceChanges.lastOrNull { it.scopeNodeId == 0L }?.reason == PaceDef.Reason.CONFIG
    }

    val eventLog: EventLogItems by derivedStateOf { EventLogItems(snapshot.events, snapshot.paceChanges) }

    fun ownPace(nodeId: Long): PaceSetting? = snapshot.pace.nodes[nodeId]

    fun governingPace(nodeId: Long): PaceSetting? = snapshot.pace.governing(nodeId) { snapshot.node(it)?.info?.parentId }

    fun send(command: PaceCommand) {
        if (paceable) commands?.invoke(command)
    }

    fun togglePause(node: Long = 0) {
        val paused = (if (node == 0L) globalPace else governingPace(node))?.paused == true
        send(if (paused) PaceCommand.Resume(node) else PaceCommand.Pause(node))
    }

    fun step(node: Long = 0, count: Int = 1) = send(PaceCommand.Step(count, node))

    fun setPace(intervalNanos: Long, node: Long = 0) = send(PaceCommand.SetPace(intervalNanos, node))

    fun inherit(node: Long) = send(PaceCommand.Inherit(node))

    fun handleKey(key: ControlKey): Boolean {
        if (!paceable) return false
        when (key) {
            ControlKey.PAUSE_RESUME -> togglePause()
            ControlKey.STEP -> step()
        }
        return true
    }

    enum class ControlKey { PAUSE_RESUME, STEP }

    fun toggleLinks(kind: EdgeKind) {
        options = options.copy(linkKinds = if (kind in options.linkKinds) options.linkKinds - kind else options.linkKinds + kind)
    }

    fun showLibrary(show: Boolean) {
        options = options.copy(showLibrary = show)
    }

    fun showLabels(show: Boolean) {
        options = options.copy(labels = show)
    }

    fun selectNode(id: Long) {
        selectedNodeId = id
        selectedEventSeq = null
        snapshot.node(id)?.events?.lastOrNull()?.let { logReveal = Reveal(it.seq, ++tickets) }
    }

    fun selectEvent(event: Event) {
        selectedEventSeq = event.seq
        selectedNodeId = event.nodeId
        revealInGraph(event.nodeId)
        logReveal = Reveal(event.seq, ++tickets)
    }

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

    /** Log position including execution-control changes, or -1 if the event is absent. */
    fun logIndex(seq: Long): Int = eventLog.positionOfEvent(eventIndex(seq))

    /** Index in [TraceSnapshot.events], or -1 if absent. Sequence numbers can have gaps. */
    fun eventIndex(seq: Long): Int = snapshot.events.binarySearch { it.seq.compareTo(seq) }.coerceAtLeast(-1)

    private fun revealInGraph(id: Long) {
        if (!options.showLibrary && SubtreeFacts(snapshot).isHidden(id)) showLibrary(true)
        graphReveal = Reveal(id, ++tickets)
    }
}
