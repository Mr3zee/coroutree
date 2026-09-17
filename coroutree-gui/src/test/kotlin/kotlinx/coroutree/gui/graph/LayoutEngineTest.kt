package kotlinx.coroutree.gui.graph

import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.GraphLayout
import kotlinx.coroutree.gui.view.graph.GraphLink
import kotlinx.coroutree.gui.view.graph.GraphMetrics
import kotlinx.coroutree.gui.view.graph.GraphModel
import kotlinx.coroutree.gui.view.graph.GraphNode
import kotlinx.coroutree.gui.view.graph.LayoutEngine
import kotlinx.coroutree.gui.view.graph.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the drawing looks like (DESIGN §6.1), as opposed to what it must never do (§6.2, GraphInvariantTest). */
class LayoutEngineTest {
    private fun GraphLayout.rect(id: Long): Rect = box(id)!!.rect

    /** Layers by depth, children under their parent in creation order, roots side by side in creation order. */
    private fun assertTopDownForest(graph: GraphModel, layout: GraphLayout, what: String) {
        val byId = graph.nodes.associateBy { it.id }
        val ids = byId.keys
        fun depth(node: GraphNode): Int = generateSequence(node) { byId[it.parentId] }.count() - 1
        val tops = HashMap<Int, Int>()
        for (node in graph.nodes) {
            val top = layout.rect(node.id).top
            assertEquals(tops.getOrPut(depth(node)) { top }, top, "$what: nodes of one depth stand on one line")
            assertEquals(GraphMetrics.NODE_HEIGHT, layout.rect(node.id).height)
            assertTrue(layout.rect(node.id).width >= node.width, "$what: a box is at least as wide as its content")
        }
        assertEquals(tops.keys.sorted(), tops.entries.sortedBy { it.value }.map { it.key }, "$what: deeper is lower")

        val families = graph.nodes.groupBy { it.parentId.takeIf { parent -> parent in ids } ?: 0L }
        for ((parentId, children) in families) {
            val centres = children.map { layout.rect(it.id).centerX }
            assertEquals(centres.sorted(), centres, "$what: children of $parentId left to right in creation order")
            if (parentId == 0L) continue
            val parent = layout.rect(parentId)
            assertTrue(abs(parent.centerX - (centres.first() + centres.last()) / 2) <= GraphMetrics.COLUMN, "$what: $parentId is centred over its children")
        }
        // A subtree occupies a stretch of its own in every layer: nothing of a sibling's subtree stands inside it.
        for (row in layout.boxes.groupBy { it.depth }.values) {
            val ordered = row.sortedBy { it.rect.left }
            for ((a, b) in ordered.zipWithNext()) assertTrue(a.rect.right + GraphMetrics.BOX_GAP <= b.rect.left, "$what: ${a.id} and ${b.id} overlap")
        }
    }

    @Test
    fun drawsATopDownForest() {
        val graph = GraphModel(
            listOf(
                GraphNode(1, 0), GraphNode(2, 1), GraphNode(3, 1, width = 200), GraphNode(4, 2), GraphNode(5, 2), GraphNode(6, 2),
                GraphNode(7, 0), GraphNode(8, 7), GraphNode(9, 3), GraphNode(10, 0),
            ),
            emptyList(),
        )
        val layout = LayoutEngine.layout(graph)
        assertTopDownForest(graph, layout, "two trees and a lone root")
        assertEquals(listOf(1L, 7L, 10L), layout.boxes.filter { it.depth == 0 }.sortedBy { it.rect.left }.map { it.id })
        assertEquals(listOf(0, 1, 1, 2, 2, 2, 0, 1, 2, 0), layout.boxes.map { it.depth })
        assertEquals(GraphMetrics.MARGIN, layout.boxes.minOf { it.rect.top })
        assertTrue(layout.boxes.minOf { it.rect.left } in GraphMetrics.MARGIN until GraphMetrics.MARGIN + GraphMetrics.COLUMN)
        assertTrue(layout.bounds.right >= layout.boxes.maxOf { it.rect.right } + GraphMetrics.MARGIN)
    }

    @Test
    fun randomForestsAreTopDownForestsToo() {
        for (shape in RandomGraphs.Shape.entries) for (seed in 1..15) {
            val graph = RandomGraphs.forest(seed, 60, shape, links = 25)
            assertTopDownForest(graph, LayoutEngine.layout(graph), "forest(seed=$seed, $shape)")
        }
    }

    @Test
    fun subtreesTuckUnderEachOther() {
        // 1 has a deep first child and a shallow second one; the second child's neighbour at depth 1 is what decides
        // how far right it goes, not the width of the first child's grandchildren.
        val graph = GraphModel(
            listOf(GraphNode(1, 0), GraphNode(2, 1), GraphNode(3, 1), GraphNode(4, 2), GraphNode(5, 4), GraphNode(6, 4), GraphNode(7, 4), GraphNode(8, 4)),
            emptyList(),
        )
        val layout = LayoutEngine.layout(graph)
        val gap = layout.rect(3).left - layout.rect(2).right
        assertTrue(gap < GraphMetrics.BOX_GAP + GraphMetrics.COLUMN, "siblings stand next to each other, $gap apart, over the fan below")
        assertTrue(layout.rect(8).right > layout.rect(3).right, "the fan below is wider than both")
    }

    @Test
    fun structuralEdgesShareATrunkAndASingleChildHangsStraightBelow() {
        val graph = GraphModel(listOf(GraphNode(1, 0), GraphNode(2, 1), GraphNode(3, 1), GraphNode(4, 1), GraphNode(5, 4)), emptyList())
        val layout = LayoutEngine.layout(graph)
        val fan = layout.trunks.single { it.parentId == 1L }
        assertEquals(listOf(2L, 3L, 4L), fan.childIds)
        assertEquals(1 + 1 + 3, fan.segments.size, "a trunk, a bus and three drops")
        assertEquals(layout.rect(1).let { it.centerX to it.bottom }, fan.top.x to fan.top.y)
        assertEquals(listOf(2L, 3L, 4L).map { layout.rect(it).centerX to layout.rect(it).top }, fan.drops.map { it.x to it.y })
        val toThird = fan.pathTo(4)!!
        assertEquals(fan.top, toThird.first())
        assertEquals(fan.drops[2], toThird.last())
        assertTrue(toThird.zipWithNext().all { (a, b) -> a.x == b.x || a.y == b.y }, "the way to a child follows the shape")
        assertNull(fan.pathTo(5))

        val chain = layout.trunks.single { it.parentId == 4L }
        assertTrue(chain.straight)
        assertEquals(1, chain.segments.size)
        assertEquals(layout.rect(4).centerX, layout.rect(5).centerX)
    }

    @Test
    fun crossLinksAreOrthogonalRoutesFromBoxToBoxInTheirDirection() {
        val label = GraphMetrics.labelWidth("cancels".length)
        val graph = GraphModel(
            listOf(GraphNode(1, 0), GraphNode(2, 1), GraphNode(3, 1), GraphNode(4, 2), GraphNode(5, 3), GraphNode(6, 0)),
            listOf(
                GraphLink(EdgeKind.CANCELS, 1, 5, label), // down two layers, past node 3's layer
                GraphLink(EdgeKind.LAUNCHED_FROM, 4, 6, label), // up to another tree
                GraphLink(EdgeKind.INTERRUPTS, 2, 3, label), // between siblings
                GraphLink(EdgeKind.CANCELS, 9, 1), // from a node that is not in the graph
            ),
        )
        val layout = LayoutEngine.layout(graph)
        assertEquals(graph.links.take(3), layout.routes.map { it.link })
        for (route in layout.routes) {
            val from = layout.rect(route.link.from)
            val to = layout.rect(route.link.to)
            assertTrue(from.onBorder(route.points.first().x, route.points.first().y), "${route.link} starts on its source")
            assertTrue(to.onBorder(route.points.last().x, route.points.last().y), "${route.link} ends on its target")
            assertTrue(route.points.zipWithNext().all { (a, b) -> (a.x == b.x) != (a.y == b.y) }, "${route.link} is orthogonal")
            assertTrue(route.points.zipWithNext().zipWithNext().all { (s, t) -> (s.first.x == s.second.x) != (t.first.x == t.second.x) }, "${route.link} turns at every point")
            val labelBox = assertNotNull(route.label, "${route.link} has room for its label")
            assertEquals(label, labelBox.width)
            assertEquals(GraphMetrics.LABEL_HEIGHT, labelBox.height)
        }
        val siblings = layout.routes.single { it.link.kind == EdgeKind.INTERRUPTS }
        assertTrue(siblings.points.all { it.y >= layout.rect(2).bottom }, "a link between two boxes of one layer hangs below them")
    }

    @Test
    fun aLabelThatDoesNotFitIsTruncatedOrDropped() {
        val nodes = listOf(GraphNode(1, 0), GraphNode(2, 1))
        // Parent to only child: the link runs a few units sideways from one port to the other; no label fits there.
        val tight = LayoutEngine.layout(GraphModel(nodes, listOf(GraphLink(EdgeKind.CANCELS, 1, 2, labelWidth = 60))))
        assertNull(tight.routes.single().label)
        assertInvariant(tight, "parent cancels only child")

        // Between two distant boxes a very long label gets what room there is.
        val far = listOf(GraphNode(1, 0), GraphNode(2, 1), GraphNode(3, 1), GraphNode(4, 1), GraphNode(5, 2), GraphNode(6, 4))
        val wide = LayoutEngine.layout(GraphModel(far, listOf(GraphLink(EdgeKind.CANCELS, 5, 6, labelWidth = 5000))))
        val label = assertNotNull(wide.routes.single().label)
        assertTrue(label.width in GraphMetrics.MIN_LABEL_WIDTH until 5000, "truncated to ${label.width}")
        assertInvariant(wide, "a label longer than its line")
    }

    @Test
    fun sameModelSameDrawing() {
        for (seed in 1..10) {
            val graph = RandomGraphs.forest(seed, 80, RandomGraphs.Shape.BUSHY, links = 60)
            val first = LayoutEngine.layout(graph)
            val second = LayoutEngine.layout(graph.copy(nodes = graph.nodes.toList(), links = graph.links.toList()))
            assertEquals(first.boxes.map { it.rect }, second.boxes.map { it.rect })
            assertEquals(first.routes.map { it.points to it.label }, second.routes.map { it.points to it.label })
            assertEquals(first.trunks.map { it.segments }, second.trunks.map { it.segments })
            assertEquals(first.bounds, second.bounds)
        }
    }

    @Test
    fun findsBoxesByAreaAndByPoint() {
        val graph = RandomGraphs.forest(3, 300, RandomGraphs.Shape.BUSHY, links = 40)
        val layout = LayoutEngine.layout(graph)
        val areas = listOf(Rect(0, 0, 400, 200), Rect(900, 100, 1500, 400), layout.bounds, Rect(-50, -50, -1, -1), Rect(310, 70, 310, 70))
        for (area in areas) {
            assertEquals(layout.boxes.filter { it.rect.intersects(area) }.map { it.id }.toSet(), layout.boxesIn(area).map { it.id }.toSet(), "$area")
        }
        val some = layout.boxes[137]
        assertEquals(some.id, layout.boxAt(some.rect.centerX, some.rect.centerY)?.id)
        assertEquals(some.id, layout.boxAt(some.rect.left, some.rect.bottom)?.id)
        assertNull(layout.boxAt(some.rect.left - 1, some.rect.top - 1))
    }

    @Test
    fun survivesVeryDeepAndVeryWideForests() {
        val deep = LayoutEngine.layout(GraphModel((1L..50_000L).map { GraphNode(it, it - 1) }, listOf(GraphLink(EdgeKind.CANCELS, 1, 50_000))))
        assertEquals(50_000, deep.boxes.size)
        assertEquals(deep.boxes.first().rect.centerX, deep.boxes.last().rect.centerX, "a chain is a straight line down")
        val wide = LayoutEngine.layout(GraphModel(listOf(GraphNode(1, 0)) + (2L..20_001L).map { GraphNode(it, 1) }, emptyList()))
        assertEquals(20_000, wide.trunks.single().drops.size)
    }

    @Test
    fun rejectsWhatIsNotAForest() {
        assertFailsWith<IllegalArgumentException> { LayoutEngine.layout(GraphModel(listOf(GraphNode(1, 2), GraphNode(2, 1)), emptyList())) }
        assertFailsWith<IllegalArgumentException> { LayoutEngine.layout(GraphModel(listOf(GraphNode(1, 0), GraphNode(1, 0)), emptyList())) }
    }

    /**
     * DESIGN §11.9: layout and routing run again whenever the node set of a live trace changes, so they have to stay
     * fast at 10^4 nodes. The bound is generous on purpose (CI machines, a cold JVM); the time is printed for whoever
     * wants to watch it.
     */
    @Test
    fun laysOutTenThousandNodesQuickly() {
        val graph = RandomGraphs.forest(42, 10_000, RandomGraphs.Shape.BUSHY, links = 3_000)
        LayoutEngine.layout(RandomGraphs.forest(1, 2_000, RandomGraphs.Shape.BUSHY, links = 500)) // warm up
        val started = System.nanoTime()
        val layout = LayoutEngine.layout(graph)
        val millis = (System.nanoTime() - started) / 1_000_000
        println("LayoutEngine: 10 000 nodes, ${layout.routes.size} cross-links in $millis ms")
        assertTrue(millis < 3_000, "took $millis ms")
        assertInvariant(layout, "10 000 nodes")
    }
}
