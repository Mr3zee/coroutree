package kotlinx.coroutree.gui.graph

import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.GraphLink
import kotlinx.coroutree.gui.view.graph.GraphMetrics
import kotlinx.coroutree.gui.view.graph.GraphModel
import kotlinx.coroutree.gui.view.graph.GraphNode
import kotlinx.coroutree.gui.view.graph.LayoutEngine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The drawing invariant (DESIGN §6.2) on randomly generated forests with random cross-links, and on the shapes that
 * stress the engine most. The corpus is checked by GraphInvariantCorpusTest in coroutree-integration-tests, the demo
 * trace by GraphBuilderTest and TraceGraphInvariantTest.
 */
class GraphInvariantTest {
    private val label = GraphMetrics.labelWidth("launches".length)

    private fun check(graph: GraphModel, what: String) {
        val layout = LayoutEngine.layout(graph)
        assertEquals(graph.nodes.size, layout.boxes.size, what)
        assertInvariant(layout, what)
    }

    @Test
    fun randomForests() {
        for (shape in RandomGraphs.Shape.entries) {
            for (seed in 1..60) {
                val nodes = 1 + (seed * 7) % 90
                val links = (seed * 5) % (nodes + 20)
                check(RandomGraphs.forest(seed, nodes, shape, links), "forest(seed=$seed, nodes=$nodes, $shape, links=$links)")
            }
        }
    }

    @Test
    fun largeRandomForests() {
        for (shape in RandomGraphs.Shape.entries) {
            for (seed in 1..3) {
                check(RandomGraphs.forest(1000 + seed, 1500, shape, links = 600), "forest(seed=${1000 + seed}, nodes=1500, $shape, links=600)")
            }
        }
    }

    @Test
    fun denseCrossLinks() {
        for (seed in 1..20) {
            check(RandomGraphs.forest(seed, 25, RandomGraphs.Shape.BUSHY, links = 400), "dense(seed=$seed)")
            check(RandomGraphs.forest(seed, 12, RandomGraphs.Shape.SCATTERED, links = 150), "dense scattered(seed=$seed)")
        }
    }

    @Test
    fun wideFanOut() {
        val nodes = listOf(GraphNode(1, 0, width = 120)) + (2L..1001L).map { GraphNode(it, 1, width = 100 + (it % 7).toInt() * 2) }
        check(GraphModel(nodes, emptyList()), "repeat(1000) { launch {} }")
        // The same, with every tenth child cancelling its neighbour and the parent cancelling some children.
        val links = (2L..1000L step 10).map { GraphLink(EdgeKind.CANCELS, it, it + 1, label) } +
            (5L..1000L step 50).map { GraphLink(EdgeKind.CANCELS, 1, it, label) } +
            (7L..1000L step 100).map { GraphLink(EdgeKind.LAUNCHED_FROM, it, 1, label) }
        check(GraphModel(nodes, links), "fan-out with links")
    }

    @Test
    fun deepChain() {
        val nodes = (1L..3000L).map { GraphNode(it, it - 1) }
        val links = (1L..2900L step 97).map { GraphLink(EdgeKind.CANCELS, it, it + 40 + it % 13, label) } +
            (100L..3000L step 211).map { GraphLink(EdgeKind.LAUNCHED_FROM, it, it - 60) }
        check(GraphModel(nodes, links), "chain of 3000")
    }

    @Test
    fun oneNodeLinkedToManyGrowsToFitItsPorts() {
        val nodes = listOf(GraphNode(1, 0), GraphNode(2, 0)) + (3L..120L).map { GraphNode(it, if (it % 2 == 0L) 1 else 2) } +
            (121L..200L).map { GraphNode(it, it - 100) }
        val links = (3L..200L).map { GraphLink(EdgeKind.LAUNCHED_FROM, 1, it, label) } + (3L..200L step 3).map { GraphLink(EdgeKind.CANCELS, it, 2) }
        val graph = GraphModel(nodes, links)
        check(graph, "hub")
        val hub = LayoutEngine.layout(graph).box(1)!!.rect
        assertEquals(true, hub.width >= 198 * GraphMetrics.COLUMN, "198 lines leave the hub, each through a port of its own")
    }

    @Test
    fun parallelAndOppositeLinksBetweenTheSameTwoNodes() {
        val nodes = listOf(GraphNode(1, 0), GraphNode(2, 1), GraphNode(3, 1), GraphNode(4, 2), GraphNode(5, 0))
        val pairs = listOf(1L to 2L, 2L to 1L, 1L to 4L, 4L to 1L, 2L to 3L, 3L to 2L, 1L to 5L, 5L to 1L, 4L to 5L, 5L to 4L, 3L to 4L)
        val links = pairs.flatMap { (from, to) -> EdgeKind.entries.map { GraphLink(it, from, to, label) } }
        check(GraphModel(nodes, links), "all kinds, both directions")
    }

    @Test
    fun edgeCases() {
        check(GraphModel.EMPTY, "empty")
        check(GraphModel(listOf(GraphNode(1, 0)), listOf(GraphLink(EdgeKind.CANCELS, 1, 1))), "a node that cancels itself")
        check(GraphModel(listOf(GraphNode(1, 0), GraphNode(2, 0)), listOf(GraphLink(EdgeKind.CANCELS, 1, 2, label), GraphLink(EdgeKind.CANCELS, 2, 7))), "two roots, a dangling link")
        check(GraphModel(listOf(GraphNode(2, 1), GraphNode(1, 9)), emptyList()), "a child listed before its parent, a parent that is not there")
    }
}
