package kotlinx.coroutree.gui.graph

import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.GraphLayout
import kotlinx.coroutree.gui.view.graph.GraphLink
import kotlinx.coroutree.gui.view.graph.GraphMetrics
import kotlinx.coroutree.gui.view.graph.GraphModel
import kotlinx.coroutree.gui.view.graph.GraphNode
import kotlinx.coroutree.gui.view.graph.InvariantChecker
import kotlin.random.Random
import kotlin.test.fail

/** Seeded random forests with random cross-links: a failure names its seed, and the seed reproduces it. */
object RandomGraphs {
    enum class Shape {
        /** Any earlier node may be the parent: bushy, moderately deep. */
        BUSHY,

        /** Parents are recent nodes: long chains with short side branches. */
        DEEP,

        /** Few parents, many children each: wide fan-outs. */
        WIDE,

        /** Many roots, small trees. */
        SCATTERED,
    }

    fun forest(seed: Int, nodes: Int, shape: Shape, links: Int, labels: Boolean = true): GraphModel {
        val random = Random(seed)
        val parents = LongArray(nodes)
        for (i in 1 until nodes) {
            parents[i] = when (shape) {
                Shape.BUSHY -> if (random.nextInt(12) == 0) 0 else 1L + random.nextInt(i)
                Shape.DEEP -> if (random.nextInt(40) == 0) 0 else 1L + (i - 1 - random.nextInt(minOf(i, 3)))
                Shape.WIDE -> if (random.nextInt(60) == 0) 0 else 1L + random.nextInt(minOf(i, 1 + i / 25))
                Shape.SCATTERED -> if (random.nextInt(4) == 0) 0 else 1L + (i - 1 - random.nextInt(minOf(i, 6)))
            }
        }
        val graphNodes = List(nodes) { i ->
            val chars = random.nextInt(4, 40)
            GraphNode(id = i + 1L, parentId = parents[i], title = "n".repeat(chars), width = GraphMetrics.nodeWidth(chars))
        }
        val kinds = EdgeKind.entries
        val graphLinks = List(links) {
            val kind = kinds[random.nextInt(kinds.size)]
            // Some go to a neighbour in creation order (close in the tree), some anywhere, a few to the node itself.
            val from = 1L + random.nextInt(nodes)
            val to = when (random.nextInt(10)) {
                0 -> from
                in 1..4 -> (from + random.nextInt(-5, 6)).coerceIn(1L, nodes.toLong())
                else -> 1L + random.nextInt(nodes)
            }
            GraphLink(kind, from, to, labelWidth = if (labels && random.nextInt(3) != 0) GraphMetrics.labelWidth(kind.name.length) else 0)
        }
        return GraphModel(graphNodes, graphLinks)
    }
}

private val checker = InvariantChecker()

/** Fails with every violation of the drawing invariant found in [layout], described as [what]. */
fun assertInvariant(layout: GraphLayout, what: String) {
    val violations = checker.check(layout.toDrawing())
    if (violations.isNotEmpty()) fail("$what violates the drawing invariant (DESIGN §6.2):\n" + violations.joinToString("\n"))
}
