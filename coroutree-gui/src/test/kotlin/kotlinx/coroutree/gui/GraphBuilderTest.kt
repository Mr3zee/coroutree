package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.demo.DemoTrace
import kotlinx.coroutree.gui.graph.assertInvariant
import kotlinx.coroutree.gui.view.GraphBuilder
import kotlinx.coroutree.gui.view.GraphOptions
import kotlinx.coroutree.gui.view.SubtreeFacts
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.GraphLink
import kotlinx.coroutree.gui.view.graph.GraphMetrics
import kotlinx.coroutree.gui.view.graph.LayoutEngine
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphBuilderTest {
    private val everything = GraphOptions(showLibrary = true)

    @Test
    fun nodesComeDepthFirstInCreationOrderWithTheirParents() {
        val snapshot = snapshotOf(trace { node(1, 0); node(2, 1); node(3, 0); node(4, 1); node(5, 2) })
        val graph = GraphBuilder.build(snapshot).model
        assertEquals(listOf(1L to 0L, 2L to 1L, 5L to 2L, 4L to 1L, 3L to 0L), graph.nodes.map { it.id to it.parentId })
        assertTrue(graph.links.isEmpty())
    }

    @Test
    fun poolsAndLibraryInternalsAreLeftOutUnlessAskedForAndDimmedWhenShown() {
        val snapshot = snapshotOf(
            trace {
                node(1, 0, Origin.LIBRARY, NodeKind.POOL); node(2, 1, Origin.LIBRARY, NodeKind.THREAD) // a pool and its worker
                node(3, 0, Origin.LIBRARY); node(4, 3, Origin.LIBRARY); node(5, 4, Origin.UNSPECIFIED) // a library's own subtree
                node(6, 0, Origin.LIBRARY); node(7, 6, Origin.LIBRARY); node(8, 7, Origin.PROJECT) // library code that leads to project code
                node(9, 0, Origin.UNSPECIFIED); node(10, 9) // not tagged at all
                node(11, 0, Origin.UNSPECIFIED, NodeKind.POOL); node(12, 11, Origin.PROJECT) // a pool with project code under it
                node(13, 8, Origin.LIBRARY) // library code launched by project code
            },
        )
        val byDefault = GraphBuilder.build(snapshot)
        assertEquals(listOf(6L, 7L, 8L, 9L, 10L, 11L, 12L), byDefault.model.nodes.map { it.id })
        assertEquals(6, byDefault.hiddenNodes)
        assertTrue(byDefault.model.nodes.none { it.dimmed })

        val shown = GraphBuilder.build(snapshot, everything)
        assertEquals((1L..13L).toSet(), shown.model.nodes.map { it.id }.toSet())
        assertEquals(0, shown.hiddenNodes)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 13L), shown.model.nodes.filter { it.dimmed }.map { it.id }.toSet(), "what hangs under noise is noise")

        val facts = SubtreeFacts(snapshot)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 13L), (1L..13L).filter(facts::isHidden).toSet())
        assertFalse(facts.isHidden(99))
    }

    @Test
    fun crossLinksAreDrawnOnceBetweenVisibleNodesOfSwitchedOnKinds() {
        val snapshot = snapshotOf(
            trace {
                node(1, 0); node(2, 1); node(3, 0, creator = 2); node(4, 0, Origin.LIBRARY, creator = 1)
                event(3, EventKind.CANCELLATION_REQUESTED, other = 1)
                event(3, EventKind.CANCELLATION_REQUESTED, other = 1) // asked twice: still one edge
                event(2, EventKind.CANCELLATION_REQUESTED, other = 2) // cancels itself: no edge to draw
                event(1, EventKind.THREAD_INTERRUPTED, other = 4)
            },
        )
        val label = GraphMetrics.labelWidth("launches".length)
        assertEquals(
            listOf(GraphLink(EdgeKind.CANCELS, 1, 3, GraphMetrics.labelWidth("cancels".length)), GraphLink(EdgeKind.LAUNCHED_FROM, 2, 3, label)),
            GraphBuilder.build(snapshot).model.links,
            "links of the hidden node 4 are left out with it",
        )
        assertEquals(
            setOf(EdgeKind.CANCELS to 3L, EdgeKind.LAUNCHED_FROM to 3L, EdgeKind.LAUNCHED_FROM to 4L, EdgeKind.INTERRUPTS to 1L),
            GraphBuilder.build(snapshot, everything).model.links.map { it.kind to it.to }.toSet(),
        )
        val onlyCancels = GraphBuilder.build(snapshot, GraphOptions(showLibrary = true, linkKinds = setOf(EdgeKind.CANCELS), labels = false)).model.links
        assertEquals(listOf(GraphLink(EdgeKind.CANCELS, 1, 3, labelWidth = 0)), onlyCancels)
    }

    @Test
    fun runsOnIsForTheSelectionFromEitherEnd() {
        val snapshot = snapshotOf(
            trace {
                node(1, 0, kind = NodeKind.THREAD); node(2, 1); node(3, 1); node(4, 0, kind = NodeKind.THREAD)
                event(2, EventKind.RESUMED, thread = 1)
                event(3, EventKind.RESUMED, thread = 4)
            },
        )
        fun runsOn(selected: Long?, options: GraphOptions = GraphOptions()) =
            GraphBuilder.build(snapshot, options, selected).model.links.filter { it.kind == EdgeKind.RUNS_ON }.map { it.from to it.to }
        assertEquals(emptyList(), runsOn(null))
        assertEquals(listOf(2L to 1L), runsOn(2))
        assertEquals(listOf(3L to 4L), runsOn(3))
        assertEquals(listOf(2L to 1L), runsOn(1), "selecting the thread shows who runs on it")
        assertEquals(emptyList(), runsOn(2, GraphOptions(linkKinds = EdgeKind.entries.toSet() - EdgeKind.RUNS_ON)))
        assertEquals(emptyList(), runsOn(77))
    }

    @Test
    fun titlesDecideTheWidthAndPlaceholdersAreNamedByTheirId() {
        val snapshot = snapshotOf(trace { node(1, 0, name = "a-rather-long-coroutine-name-for-a-box"); node(2, 1); event(9, EventKind.SUSPENDED) })
        val nodes = GraphBuilder.build(snapshot).model.nodes.associateBy { it.id }
        assertEquals("launch \"a-rather-long-coroutine-name-for-a-box\"", nodes.getValue(1).title)
        assertEquals(GraphMetrics.MAX_NODE_WIDTH, nodes.getValue(1).width)
        assertEquals(GraphMetrics.MIN_NODE_WIDTH, nodes.getValue(2).width)
        assertEquals("node #9", nodes.getValue(9).title)
        assertTrue(nodes.values.all { it.width % 2 == 0 })
    }

    @Test
    fun survivesVeryDeepTrees() {
        val depth = 50_000
        val snapshot = snapshotOf(trace { for (id in 1L..depth) node(id, id - 1, Origin.LIBRARY) })
        assertEquals(0, GraphBuilder.build(snapshot).model.nodes.size)
        assertEquals(depth, GraphBuilder.build(snapshot, everything).model.nodes.size)
        assertTrue(SubtreeFacts(snapshot).isHidden(depth.toLong()))
    }

    /**
     * The demo trace at every moment of its life, under every combination of toolbar switches and with every node
     * selected in turn (each selection adds its runs-on edges): the drawing invariant holds for all of them.
     */
    @Test
    fun theDemoTraceKeepsTheDrawingInvariantAtEveryMomentUnderEveryOption() {
        val frames = DemoTrace.frames()
        var layouts = 0
        for (upTo in (1..frames.size step 7) + frames.size) {
            val snapshot = snapshotOf(frames.take(upTo))
            for (library in listOf(false, true)) for (labels in listOf(false, true)) {
                val options = GraphOptions(showLibrary = library, labels = labels)
                val selections = if (upTo == frames.size) listOf(null) + snapshot.nodes.keys else listOf(null)
                for (selected in selections) {
                    val graph = GraphBuilder.build(snapshot, options, selected).model
                    assertInvariant(LayoutEngine.layout(graph), "demo[0..$upTo] library=$library labels=$labels selected=$selected")
                    layouts++
                }
            }
        }
        assertTrue(layouts > 150)
        val kinds = EdgeKind.entries.filter { kind -> snapshotOf(frames).nodes.keys.any { id -> GraphBuilder.build(snapshotOf(frames), everything, id).model.links.any { it.kind == kind } } }
        assertEquals(EdgeKind.entries, kinds, "the demo draws every kind of cross-link")
    }
}
