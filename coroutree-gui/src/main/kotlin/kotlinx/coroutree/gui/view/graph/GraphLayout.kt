package kotlinx.coroutree.gui.view.graph

class NodeBox(val node: GraphNode, val rect: Rect, val depth: Int) {
    val id: Long get() = node.id
}

/**
 * Structural edges of one parent share a trunk and horizontal bus. Each child has a drop from the bus.
 */
class Trunk(
    val parentId: Long,
    val childIds: List<Long>,
    /** On the parent's bottom edge. */
    val top: Point,
    val busY: Int,
    /** On each child's top edge, left to right. */
    val drops: List<Point>,
) {
    val straight: Boolean get() = drops.size == 1 && drops[0].x == top.x

    val segments: List<Segment> by lazy {
        if (straight) return@lazy listOf(Segment(top.x, top.y, top.x, drops[0].y))
        buildList {
            add(Segment(top.x, top.y, top.x, busY))
            val left = minOf(top.x, drops.first().x)
            val right = maxOf(top.x, drops.last().x)
            if (left < right) add(Segment(left, busY, right, busY))
            for (drop in drops) add(Segment(drop.x, busY, drop.x, drop.y))
        }
    }

    val bounds: Rect by lazy { Rect(minOf(top.x, drops.first().x), top.y, maxOf(top.x, drops.last().x), drops.maxOf { it.y }) }

    /** Path along the shape to [childId], or null if it isn't a child. */
    fun pathTo(childId: Long): List<Point>? {
        val drop = drops.getOrNull(childIds.indexOf(childId)) ?: return null
        if (drop.x == top.x) return listOf(top, drop)
        return listOf(top, Point(top.x, busY), Point(drop.x, busY), drop)
    }
}

/** A cross-link as routed: an orthogonal polyline from the `from` box to the `to` box. */
class LinkRoute(
    val link: GraphLink,
    val points: List<Point>,
    /** Centered on a horizontal segment, or null if no segment has room. */
    val label: Rect?,
) {
    val segments: List<Segment> get() = points.zipWithNext { a, b -> Segment(a.x, a.y, b.x, b.y) }

    val bounds: Rect by lazy { Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y }) }
}

/** A settled drawing of a [GraphModel]. The drawing invariant (DESIGN §6.2) applies to it. */
class GraphLayout internal constructor(
    /** In the order of [GraphModel.nodes]. */
    val boxes: List<NodeBox>,
    val trunks: List<Trunk>,
    /** [GraphModel.links] order, excluding self-links and links with missing endpoints. */
    val routes: List<LinkRoute>,
    val bounds: Rect,
    /** Top of each layer, and the indices into [boxes] of its nodes from left to right. */
    private val layerTops: IntArray,
    private val layerBoxes: Array<IntArray>,
) {
    private val byId: Map<Long, NodeBox> by lazy { boxes.associateBy { it.id } }

    fun box(id: Long): NodeBox? = byId[id]

    val isEmpty: Boolean get() = boxes.isEmpty()

    fun boxesIn(area: Rect): List<NodeBox> {
        val result = ArrayList<NodeBox>()
        for (layer in layerTops.indices) {
            val top = layerTops[layer]
            if (top > area.bottom) break
            if (top + GraphMetrics.NODE_HEIGHT < area.top) continue
            val row = layerBoxes[layer]
            var lo = 0
            var hi = row.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (boxes[row[mid]].rect.right < area.left) lo = mid + 1 else hi = mid
            }
            while (lo < row.size && boxes[row[lo]].rect.left <= area.right) result += boxes[row[lo++]]
        }
        return result
    }

    fun boxAt(x: Int, y: Int): NodeBox? = boxesIn(Rect(x, y, x, y)).firstOrNull()

    fun toDrawing(): Drawing = Drawing(
        boxes = boxes.map { DrawnBox(it.id, it.rect) },
        lines = trunks.map { trunk ->
            DrawnLine(
                name = "structure of ${trunk.parentId}",
                segments = trunk.segments,
                ends = listOf(LineEnd(trunk.top, trunk.parentId)) + trunk.drops.mapIndexed { i, drop -> LineEnd(drop, trunk.childIds[i]) },
                label = null,
            )
        } + routes.map { route ->
            DrawnLine(
                name = "${route.link.kind} ${route.link.from} → ${route.link.to}",
                segments = route.segments,
                ends = listOf(LineEnd(route.points.first(), route.link.from), LineEnd(route.points.last(), route.link.to)),
                label = route.label,
            )
        },
    )

    companion object {
        val EMPTY = GraphLayout(emptyList(), emptyList(), emptyList(), Rect.EMPTY, IntArray(0), emptyArray())
    }
}
