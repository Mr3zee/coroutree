package kotlinx.coroutree.gui.graph

import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.GraphLayout
import kotlinx.coroutree.gui.view.graph.GraphLink
import kotlinx.coroutree.gui.view.graph.GraphMetrics
import kotlinx.coroutree.gui.view.graph.GraphModel
import kotlinx.coroutree.gui.view.graph.GraphNode
import kotlinx.coroutree.gui.view.graph.LayoutEngine
import kotlinx.coroutree.gui.view.graph.Rect
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the engine keeps the drawing invariant (DESIGN §12, "M1.1 as built"; CLAUDE.md): the construction rules that the
 * checker deliberately knows nothing about. The checker says whether a drawing is legal; these say that it is legal
 * for the reason the design gives, so that a change which only happens to pass the corpus is still caught.
 */
class LayoutEngineRulesTest {
    private val column = GraphMetrics.COLUMN

    private companion object {
        /** Wider than any drawing these tests make. */
        const val HUGE_LABEL = 1_000_000
    }

    private fun forests(seeds: IntRange, nodes: Int, links: Int): Sequence<Pair<String, GraphModel>> = sequence {
        for (shape in RandomGraphs.Shape.entries) for (seed in seeds) {
            yield("forest(seed=$seed, nodes=$nodes, $shape, links=$links)" to RandomGraphs.forest(seed, nodes, shape, links))
        }
    }

    private fun depths(graph: GraphModel): Map<Long, Int> {
        val byId = graph.nodes.associateBy { it.id }
        val depth = HashMap<Long, Int>()
        fun of(id: Long): Int = depth.getOrPut(id) {
            val parent = byId.getValue(id).parentId
            if (parent == id || parent !in byId) 0 else of(parent) + 1
        }
        graph.nodes.forEach { of(it.id) }
        return depth
    }

    // ------------------------------------------------------------------ the residue classes of x

    /**
     * "Box centres, where structural edges attach, are multiples of 18; cross-link ports and gap tracks are at +6 on
     * even layers and +12 on odd ones": every vertical piece of a line has an x of the class of the layer it belongs to.
     */
    @Test
    fun everyVerticalHasAnXOfTheClassOfItsLayer() {
        for ((what, graph) in forests(1..25, nodes = 70, links = 60)) {
            val layout = LayoutEngine.layout(graph)
            val depth = depths(graph)
            for (box in layout.boxes) {
                assertEquals(0, (box.rect.left + box.rect.right) % 2, "$what: box ${box.id} has a centre")
                assertEquals(0, Math.floorMod(box.rect.centerX, column), "$what: the centre of box ${box.id} is on the column grid")
                assertEquals(depth.getValue(box.id), box.depth, "$what: box ${box.id} stands in the layer of its depth")
            }
            for (trunk in layout.trunks) {
                assertEquals(layout.box(trunk.parentId)!!.rect.centerX, trunk.top.x, "$what: a trunk leaves the centre of its parent")
                assertEquals(trunk.childIds.map { layout.box(it)!!.rect.centerX }, trunk.drops.map { it.x }, "$what: drops enter the centres of the children")
            }
            for (route in layout.routes) {
                val fromDepth = depth.getValue(route.link.from)
                val toDepth = depth.getValue(route.link.to)
                // Top down: the port on the upper box, one gap track per layer passed, the port on the lower box.
                val points = if (fromDepth <= toDepth) route.points else route.points.asReversed()
                val verticals = points.filterIndexed { i, _ -> i % 2 == 0 }.map { it.x }
                val upper = minOf(fromDepth, toDepth)
                val lower = maxOf(fromDepth, toDepth)
                if (upper == lower) {
                    assertEquals(2, verticals.size, "$what: ${route.link} hangs below its two boxes")
                    for (x in verticals) assertEquals(GraphMetrics.linkClass(upper), Math.floorMod(x, column), "$what: ${route.link}, port at $x")
                } else {
                    assertEquals(lower - upper + 1, verticals.size, "$what: ${route.link} has one vertical per layer from its upper box to its lower one")
                    verticals.forEachIndexed { k, x ->
                        assertEquals(GraphMetrics.linkClass(upper + k), Math.floorMod(x, column), "$what: ${route.link}, vertical $k at $x belongs to layer ${upper + k}")
                    }
                }
            }
        }
    }

    /** "A box that has more links than its width has ports grows: every line gets a port of its own." */
    @Test
    fun everyLinkEndHasAPortOfItsOwnInsideItsBox() {
        for ((what, graph) in forests(1..15, nodes = 40, links = 120)) {
            val layout = LayoutEngine.layout(graph)
            val ports = HashMap<Pair<Long, Int>, MutableList<Int>>() // box and side (y) → x of every line end there
            for (route in layout.routes) {
                for ((id, point) in listOf(route.link.from to route.points.first(), route.link.to to route.points.last())) {
                    val rect = layout.box(id)!!.rect
                    assertTrue(point.y == rect.top || point.y == rect.bottom, "$what: ${route.link} ends on the top or the bottom of box $id")
                    assertTrue(point.x - rect.left >= GraphMetrics.PORT_INSET && rect.right - point.x >= GraphMetrics.PORT_INSET, "$what: ${route.link} ends at ${point.x}, too near a corner of $rect")
                    ports.getOrPut(id to point.y) { ArrayList() } += point.x
                }
            }
            for ((side, xs) in ports) {
                val sorted = xs.sorted()
                assertTrue(sorted.zipWithNext().all { (l, r) -> r - l >= column }, "$what: ports of box ${side.first} are a column apart: $sorted")
            }
        }
    }

    @Test
    fun aBoxIsAsWideAsItsContentAsksUnlessItsPortsNeedMore() {
        for ((what, graph) in forests(1..10, nodes = 60, links = 0)) {
            val layout = LayoutEngine.layout(graph)
            for (box in layout.boxes) assertEquals(box.node.width, box.rect.width, "$what: box ${box.id} has no ports to grow for")
        }
        // An odd width is rounded up so that the box has a centre.
        val odd = LayoutEngine.layout(GraphModel(listOf(GraphNode(1, 0, width = 101), GraphNode(2, 1, width = 133)), emptyList()))
        assertEquals(listOf(102, 134), odd.boxes.map { it.rect.width })
        assertInvariant(odd, "odd widths")
    }

    // ------------------------------------------------------------------ what the input order decides, and what not

    /**
     * "A node whose parent is not in the list is a root; sibling order is list order" (GraphModel, Forest): the node
     * list says nothing else. Listing the same forest breadth first, or children before their parents, draws the same.
     */
    @Test
    fun theNodeListDecidesSiblingOrderAndNothingElse() {
        for ((what, graph) in forests(1..12, nodes = 60, links = 40)) {
            val depth = depths(graph)
            val expected = LayoutEngine.layout(graph)
            val relisted = listOf(
                "breadth first" to graph.nodes.sortedBy { depth.getValue(it.id) },
                "children first" to graph.nodes.sortedByDescending { depth.getValue(it.id) },
            )
            for ((order, nodes) in relisted) {
                val actual = LayoutEngine.layout(GraphModel(nodes, graph.links))
                assertEquals(expected.boxes.associate { it.id to it.rect }, actual.boxes.associate { it.id to it.rect }, "$what listed $order: boxes")
                assertEquals(expected.routes.map { Triple(it.link, it.points, it.label) }, actual.routes.map { Triple(it.link, it.points, it.label) }, "$what listed $order: routes")
                assertEquals(expected.trunks.associate { it.parentId to it.segments }, actual.trunks.associate { it.parentId to it.segments }, "$what listed $order: trunks")
                assertEquals(expected.bounds, actual.bounds, "$what listed $order")
            }
        }
    }

    /** "The order of boxes in a layer is a property of the forest, not of the layout": no cross-link ever changes it. */
    @Test
    fun crossLinksMoveBoxesApartButNeverPastEachOther() {
        fun GraphLayout.layers(): Map<Int, List<Long>> = boxes.groupBy({ it.depth }) { it }.mapValues { (_, row) -> row.sortedBy { it.rect.left }.map { it.id } }
        for ((what, graph) in forests(1..15, nodes = 50, links = 150)) {
            val bare = LayoutEngine.layout(graph.copy(links = emptyList()))
            val linked = LayoutEngine.layout(graph)
            assertEquals(bare.layers(), linked.layers(), what)
        }
    }

    // ------------------------------------------------------------------ bounds and labels

    private fun shapesForBounds(): Sequence<Pair<String, GraphModel>> = forests(1..10, nodes = 45, links = 60) +
        ("a single node" to GraphModel(listOf(GraphNode(1, 0)), emptyList())) +
        ("two roots joined both ways" to GraphModel(listOf(GraphNode(1, 0), GraphNode(2, 0)), listOf(GraphLink(EdgeKind.CANCELS, 1, 2, 60), GraphLink(EdgeKind.INTERRUPTS, 2, 1, 60))))

    /** GraphMetrics.MARGIN: "Empty space around the drawing"; normalizeX: "starts at MARGIN, by whole columns". */
    @Test
    fun theDrawingStartsAtTheMarginAndItsBoundsLeaveTheMarginAroundBoxesAndLines() {
        val margin = GraphMetrics.MARGIN
        for ((what, graph) in shapesForBounds()) {
            val layout = LayoutEngine.layout(graph)
            val all = (layout.boxes.map { it.rect } + layout.routes.map { it.bounds } + layout.trunks.map { it.bounds }).reduce(Rect::union)
            assertEquals(0 to 0, layout.bounds.left to layout.bounds.top, what)
            assertEquals(margin, all.top, "$what: the first layer stands at the margin")
            assertTrue(all.left in margin until margin + column, "$what: the leftmost shape is at the margin, within the column grid: ${all.left}")
            assertEquals(all.right + margin, layout.bounds.right, what)
            assertEquals(all.bottom + margin, layout.bounds.bottom, what)
        }
    }

    /**
     * The margin is around the drawing, and a label is part of the drawing: the pane fits `bounds`, so whatever
     * reaches beyond them minus the margin is nearer the edge of the pane than anything is meant to be.
     */
    @Test
    fun theBoundsLeaveTheMarginAroundLabelsToo() {
        val margin = GraphMetrics.MARGIN
        for ((what, graph) in shapesForBounds()) {
            val layout = LayoutEngine.layout(graph)
            for (route in layout.routes) {
                val label = route.label ?: continue
                assertTrue(
                    label.left >= margin && label.top >= margin && label.right <= layout.bounds.right - margin && label.bottom <= layout.bounds.bottom - margin,
                    "$what: the label of ${route.link} at $label is in the margin of ${layout.bounds}",
                )
            }
        }
    }

    /**
     * Rule 5: "text that does not fit is truncated"; DESIGN §12: "Less room truncates the label, less than 28 drops
     * it". A label is given in full, or cut to no less than MIN_LABEL_WIDTH, or not at all; never wider than asked.
     */
    @Test
    fun aLabelIsGivenInFullOrTruncatedToSomethingReadableOrDropped() {
        var truncated = 0
        var dropped = 0
        for (seed in 1..40) {
            val random = Random(seed)
            val graph = RandomGraphs.forest(seed, 35, RandomGraphs.Shape.entries[seed % 4], links = 70, labels = false)
            // From no label at all to one that no drawing of 35 boxes has a channel for.
            val widths = listOf(0, GraphMetrics.MIN_LABEL_WIDTH, 60, 400, HUGE_LABEL)
            val labelled = graph.copy(links = graph.links.map { it.copy(labelWidth = widths[random.nextInt(widths.size)]) })
            val layout = LayoutEngine.layout(labelled)
            assertInvariant(layout, "labels of every size, seed $seed")
            assertTrue(layout.bounds.width < HUGE_LABEL, "seed $seed: the huge label cannot fit anywhere")
            for (route in layout.routes) {
                val label = route.label
                val asked = route.link.labelWidth
                if (asked == 0) assertNull(label, "seed $seed: ${route.link} asked for no label")
                if (label == null) {
                    if (asked > 0) dropped++
                    continue
                }
                assertEquals(GraphMetrics.LABEL_HEIGHT, label.height, "seed $seed: ${route.link}")
                assertTrue(label.width in GraphMetrics.MIN_LABEL_WIDTH..asked, "seed $seed: ${route.link} got ${label.width}")
                if (label.width < asked) truncated++
            }
        }
        // Not vacuous: both things happened somewhere (every huge label that is drawn at all is a truncated one).
        assertTrue(truncated > 0 && dropped > 0, "$truncated truncated, $dropped dropped")
    }

    // ------------------------------------------------------------------ degenerate shapes under the invariant

    private fun check(graph: GraphModel, what: String): GraphLayout {
        val layout = LayoutEngine.layout(graph)
        assertEquals(graph.nodes.map { it.id }, layout.boxes.map { it.id }, "$what: boxes in the order of the nodes")
        assertInvariant(layout, what)
        return layout
    }

    @Test
    fun theSameLinkGivenAgainIsALineOfItsOwn() {
        val nodes = listOf(GraphNode(1, 0), GraphNode(2, 1), GraphNode(3, 1), GraphNode(4, 3), GraphNode(5, 0))
        val once = listOf(GraphLink(EdgeKind.CANCELS, 2, 4, 50), GraphLink(EdgeKind.CANCELS, 5, 1, 50), GraphLink(EdgeKind.CANCELS, 2, 3, 50))
        val layout = check(GraphModel(nodes, once + once + once), "every link three times")
        assertEquals(9, layout.routes.size)
        assertEquals(9, layout.routes.map { it.points }.toSet().size, "no two on the same way")
    }

    @Test
    fun aForestOfNothingButRootsLinkedEachToEach() {
        val nodes = (1L..14L).map { GraphNode(it, 0, width = 96 + (it % 5).toInt() * 30) }
        val links = nodes.flatMap { a -> nodes.filter { it.id != a.id }.map { b -> GraphLink(EdgeKind.entries[((a.id + b.id) % 4).toInt()], a.id, b.id, 54) } }
        val layout = check(GraphModel(nodes, links), "14 roots, 182 links")
        assertEquals(1, layout.boxes.map { it.rect.top }.toSet().size, "one layer")
        assertEquals(182, layout.routes.size)
    }

    @Test
    fun linksFromEveryRootToEveryDeepestLeafPassThroughEveryLayerBetween() {
        for (shape in listOf(RandomGraphs.Shape.BUSHY, RandomGraphs.Shape.DEEP, RandomGraphs.Shape.SCATTERED)) for (seed in 1..6) {
            val forest = RandomGraphs.forest(seed, 80, shape, links = 0)
            val depth = depths(forest)
            val deepest = depth.values.max()
            val roots = forest.nodes.filter { depth.getValue(it.id) == 0 }.take(6)
            val leaves = forest.nodes.filter { depth.getValue(it.id) == deepest }
            val links = roots.flatMap { root -> leaves.flatMap { leaf -> listOf(GraphLink(EdgeKind.CANCELS, root.id, leaf.id, 54), GraphLink(EdgeKind.LAUNCHED_FROM, leaf.id, root.id)) } }
            val layout = check(GraphModel(forest.nodes, links), "$shape seed $seed: ${links.size} links over $deepest layers")
            for (route in layout.routes) assertEquals(2 * (deepest + 1), route.points.size, "$shape seed $seed: ${route.link} bends once into and once out of every channel")
        }
    }

    @Test
    fun aNodeThatIsItsOwnParentIsARootAndLinksToNowhereAreNotDrawn() {
        val nodes = listOf(GraphNode(1, 1), GraphNode(2, 1), GraphNode(3, 99))
        val links = listOf(GraphLink(EdgeKind.CANCELS, 1, 1, 50), GraphLink(EdgeKind.CANCELS, 99, 2, 50), GraphLink(EdgeKind.CANCELS, 2, 99, 50), GraphLink(EdgeKind.CANCELS, 3, 2, 50))
        val layout = check(GraphModel(nodes, links), "self-parent, self-link, dangling links")
        assertEquals(listOf(0, 1, 0), layout.boxes.map { it.depth })
        assertEquals(listOf(links[3]), layout.routes.map { it.link }, "an edge needs two boxes")
    }

    @Test
    fun longTitlesAndLongLabelsEverywhere() {
        for (seed in 1..10) {
            val forest = RandomGraphs.forest(seed, 40, RandomGraphs.Shape.entries[seed % 4], links = 60)
            val wide = forest.copy(nodes = forest.nodes.map { it.copy(width = GraphMetrics.MAX_NODE_WIDTH) }, links = forest.links.map { it.copy(labelWidth = 900) })
            check(wide, "everything at its widest, seed $seed")
        }
    }

    /** More of what GraphInvariantTest does, on other seeds and denser: small forests are where two links most often want the same gap. */
    @Test
    fun manySmallForestsDenselyLinked() {
        for (seed in 101..400) {
            val random = Random(seed)
            val nodes = 2 + random.nextInt(18)
            val links = random.nextInt(4 * nodes)
            val shape = RandomGraphs.Shape.entries[random.nextInt(4)]
            check(RandomGraphs.forest(seed, nodes, shape, links, labels = random.nextBoolean()), "forest(seed=$seed, nodes=$nodes, $shape, links=$links)")
        }
    }
}
