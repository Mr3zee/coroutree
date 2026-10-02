package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.graph.assertInvariant
import kotlinx.coroutree.gui.view.GraphBuilder
import kotlinx.coroutree.gui.view.GraphOptions
import kotlinx.coroutree.gui.view.SubtreeFacts
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.LayoutEngine
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.TraceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What is structure and what is not (CLAUDE.md: "the graph is rebuilt only when GraphModel changes structurally, so
 * keep anything that changes per event out of GraphNode"), and what the builder makes of traces that are not tidy:
 * nodes referred to before they are defined, parents that never come, parents in a circle.
 */
class GraphBuilderRulesTest {
    /** A stream that is still running: no end of stream, so events held back behind a gap stay held back. */
    private fun running(frames: List<Frame>): TraceSnapshot = TraceStore().apply { frames.forEach(::accept) }.snapshot()

    /** Five nodes and a link between two of them, then everything a program does to nodes that are already there. */
    private fun TraceScript.story() {
        node(1, 0, kind = NodeKind.THREAD, construct = "Thread", name = "main")
        node(2, 0, kind = NodeKind.THREAD, construct = "Thread", name = "worker")
        node(3, 1, name = "a")
        node(4, 3, name = "b")
        node(5, 0, creator = 3, name = "c")
        event(5, EventKind.CANCELLATION_REQUESTED, other = 3)
        // --- from here on nothing is created
        event(3, EventKind.RESUMED, thread = 1)
        event(4, EventKind.RESUMED, thread = 2)
        event(3, EventKind.SUSPENDED)
        event(1, EventKind.THREAD_BLOCKED)
        event(3, EventKind.RESUMED, thread = 2)
        event(4, EventKind.CANCELLING)
        event(4, EventKind.EXCEPTION_THROWN)
        event(3, EventKind.EXCEPTION_PROPAGATED, other = 4)
        event(1, EventKind.THREAD_UNBLOCKED)
        event(5, EventKind.CANCELLATION_REQUESTED, other = 3) // a link that is there already
        event(4, EventKind.FINISHED, state = NodeState.FAILED)
        event(3, EventKind.SUSPENDED)
        event(5, EventKind.FINISHED, state = NodeState.CANCELLED)
    }

    private val life = trace { story() }
    private val structure = life.take(6)

    @Test
    fun whatHappensToNodesChangesNoBoxAndNoEdge() {
        val before = GraphBuilder.build(running(structure))
        for (upTo in structure.size..life.size) {
            val snapshot = running(life.take(upTo))
            assertEquals(before, GraphBuilder.build(snapshot), "after ${upTo - structure.size} more events")
            // Somebody else being selected does not bring in the runs-on of the nodes these events are about.
            assertEquals(GraphBuilder.build(running(structure), selectedNodeId = 5), GraphBuilder.build(snapshot, selectedNodeId = 5), "after ${upTo - structure.size} more events, with 5 selected")
        }
        assertEquals(listOf(NodeState.FAILED, NodeState.CANCELLED), running(life).let { listOf(it.node(4)!!.state, it.node(5)!!.state) }, "the events did change the nodes")
    }

    @Test
    fun aLiveTraceLaysOutAgainOnlyWhenABoxOrAnEdgeChanges() {
        val viewModel = TraceViewModel()
        viewModel.snapshot = running(structure)
        val layout = viewModel.layout
        for (upTo in structure.size + 1..life.size) {
            viewModel.snapshot = running(life.take(upTo))
            assertSame(layout, viewModel.layout, "event ${upTo - structure.size} made the engine run")
        }
        viewModel.snapshot = running(trace { story(); node(6, 3) })
        assertNotSame(layout, viewModel.layout, "a new node is a new box")
        assertEquals(6, viewModel.layout.boxes.size)
    }

    @Test
    fun theRunsOnEdgeOfTheSelectionFollowsItFromThreadToThread() {
        fun runsOn(upTo: Int) = GraphBuilder.build(running(life.take(upTo)), selectedNodeId = 3).model.links.filter { it.kind == EdgeKind.RUNS_ON }.map { it.from to it.to }
        val n = structure.size
        assertEquals(emptyList(), runsOn(n))
        assertEquals(listOf(3L to 1L), runsOn(n + 1), "resumed on main")
        assertEquals(emptyList(), runsOn(n + 3), "suspended: on no thread")
        assertEquals(listOf(3L to 2L), runsOn(n + 5), "resumed on the worker")
        assertEquals(emptyList(), runsOn(life.size))
        // Seen from the thread: both coroutines were on the worker for a while.
        val onWorker = GraphBuilder.build(running(life.take(n + 5)), selectedNodeId = 2).model.links.filter { it.kind == EdgeKind.RUNS_ON }.map { it.from }
        assertEquals(setOf(3L, 4L), onWorker.toSet())
    }

    /** DESIGN §12: runs-on "needs library / pools on to be seen when the thread is a pool worker". */
    @Test
    fun runsOnToAThreadThatIsLeftOutAsLibraryNoiseIsLeftOutWithIt() {
        val snapshot = snapshotOf(
            trace {
                node(1, 0, Origin.LIBRARY, NodeKind.POOL)
                node(2, 1, Origin.LIBRARY, NodeKind.THREAD)
                node(3, 0, name = "job")
                event(3, EventKind.RESUMED, thread = 2)
            },
        )
        val hidden = GraphBuilder.build(snapshot, selectedNodeId = 3)
        assertEquals(listOf(3L), hidden.model.nodes.map { it.id })
        assertTrue(hidden.model.links.isEmpty(), "an edge needs both of its boxes")
        assertEquals(2, hidden.hiddenNodes)
        val shown = GraphBuilder.build(snapshot, GraphOptions(showLibrary = true), selectedNodeId = 3)
        assertEquals(listOf(EdgeKind.RUNS_ON), shown.model.links.map { it.kind })
        // The worker selected while it is hidden: nothing to draw from, and nothing breaks.
        assertEquals(hidden, GraphBuilder.build(snapshot, selectedNodeId = 2))
    }

    /** CLAUDE.md: "A node may be referenced before it is defined (a job is cancelled inside its own constructor)". */
    @Test
    fun aNodeReferredToBeforeItIsDefinedIsOneBoxThatMovesUnderItsParentOnceKnown() {
        val early = trace {
            node(1, 0)
            event(9, EventKind.CANCELLATION_REQUESTED, other = 1)
        }
        val placeholder = GraphBuilder.build(snapshotOf(early)).model
        assertEquals(listOf(1L to 0L, 9L to 0L), placeholder.nodes.map { it.id to it.parentId })
        assertEquals("node #9", placeholder.nodes.last().title)
        assertEquals(listOf(Triple(EdgeKind.CANCELS, 1L, 9L)), placeholder.links.map { Triple(it.kind, it.from, it.to) })

        val defined = TraceScript().apply {
            node(1, 0)
            event(9, EventKind.CANCELLATION_REQUESTED, other = 1)
            node(9, 1, name = "late")
        }.frames
        val graph = GraphBuilder.build(snapshotOf(defined)).model
        assertEquals(listOf(1L to 0L, 9L to 1L), graph.nodes.map { it.id to it.parentId }, "once, and under its parent")
        assertEquals("launch \"late\"", graph.nodes.last().title)
        assertEquals(1, graph.links.size, "the link it had as a placeholder is still its link")
        assertNotEquals(placeholder, graph, "this one is a structural change")
        assertInvariant(LayoutEngine.layout(graph), "a parent that cancels its child")
    }

    @Test
    fun aParentThatIsNeverDefinedStandsAsAPlaceholderRootAboveItsChildren() {
        val graph = GraphBuilder.build(snapshotOf(trace { node(5, 7, name = "orphan"); node(6, 7) })).model
        assertEquals(listOf(7L to 0L, 5L to 7L, 6L to 7L), graph.nodes.map { it.id to it.parentId })
        assertEquals("node #7", graph.nodes.first().title)
        assertFalse(graph.nodes.first().dimmed, "what is not known to be library is not treated as noise")
    }

    @Test
    fun aCorruptTraceWithParentsInACircleNeitherHangsNorBreaksTheLayout() {
        // 1 is under 2 and 2 under 1: neither is a root, so neither is reachable from one.
        val circle = snapshotOf(trace { node(1, 2); node(2, 1); node(3, 0); node(4, 3, Origin.LIBRARY) })
        assertEquals(setOf(1L, 2L, 3L, 4L), circle.nodes.keys)
        for (options in listOf(GraphOptions(), GraphOptions(showLibrary = true))) {
            val graph = GraphBuilder.build(circle, options, selectedNodeId = 1).model
            assertTrue(graph.nodes.map { it.id }.containsAll(listOf(3L)), "what is sound is drawn")
            assertEquals(graph.nodes.size, graph.nodes.map { it.id }.toSet().size, "no node twice")
            assertInvariant(LayoutEngine.layout(graph), "a trace with a parent cycle")
        }
        val facts = SubtreeFacts(circle)
        assertFalse(facts.isHidden(1))
        assertTrue(facts.isHidden(4))
    }

    @Test
    fun hiddenNodesAreCountedWhateverIsSelectedAndWhicheverLinksAreOn() {
        val snapshot = demoSnapshot()
        val hidden = GraphBuilder.build(snapshot).hiddenNodes
        assertTrue(hidden > 0, "the demo has library machinery")
        for (id in snapshot.nodes.keys) {
            val graph = GraphBuilder.build(snapshot, GraphOptions(linkKinds = emptySet(), labels = false), selectedNodeId = id)
            assertEquals(hidden, graph.hiddenNodes)
            assertEquals(snapshot.nodes.size - hidden, graph.model.nodes.size)
            assertTrue(graph.model.links.isEmpty(), "every kind is switched off, runs-on included")
        }
    }
}
