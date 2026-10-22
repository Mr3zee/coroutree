package kotlinx.coroutree.gui.view.graph

import kotlin.math.max
import kotlin.math.min

/**
 * The structural forest as index arrays. Nothing here recurses: a coroutine tree can be tens of thousands deep.
 * A node whose parent isn't in the list is a root. Sibling order is list order.
 */
internal class Forest(nodes: List<GraphNode>) {
    val size: Int = nodes.size
    val indexOf: Map<Long, Int>

    /** -1 for a root. */
    val parent = IntArray(size)
    private val childStart = IntArray(size + 1)
    private val childList: IntArray
    val roots: IntArray
    val depth = IntArray(size)

    /** Parents before children, siblings left to right. */
    val preorder: IntArray

    /** Node indices at each depth, left to right: the order the boxes of a layer stand in, whatever the layout. */
    val layers: Array<IntArray>
    val posInLayer = IntArray(size)

    init {
        val index = HashMap<Long, Int>(size * 2)
        nodes.forEachIndexed { i, node -> require(index.put(node.id, i) == null) { "Node ${node.id} is in the graph twice" } }
        indexOf = index
        var rootCount = 0
        for (i in 0 until size) {
            val p = index[nodes[i].parentId]?.takeIf { it != i } ?: -1
            parent[i] = p
            if (p < 0) rootCount++ else childStart[p + 1]++
        }
        for (i in 0 until size) childStart[i + 1] += childStart[i]
        childList = IntArray(size - rootCount)
        roots = IntArray(rootCount)
        val fill = childStart.copyOf()
        var nextRoot = 0
        for (i in 0 until size) {
            val p = parent[i]
            if (p < 0) roots[nextRoot++] = i else childList[fill[p]++] = i
        }

        val order = IntArray(size)
        var visited = 0
        var maxDepth = -1
        val stack = IntArray(size)
        var top = 0
        for (r in roots.indices.reversed()) stack[top++] = roots[r]
        while (top > 0) {
            val v = stack[--top]
            order[visited++] = v
            depth[v] = if (parent[v] < 0) 0 else depth[parent[v]] + 1
            maxDepth = max(maxDepth, depth[v])
            for (c in childStart[v + 1] - 1 downTo childStart[v]) stack[top++] = childList[c]
        }
        require(visited == size) { "The structural parents of the graph form a cycle" }
        preorder = order

        val counts = IntArray(maxDepth + 1)
        for (v in order) counts[depth[v]]++
        layers = Array(maxDepth + 1) { IntArray(counts[it]) }
        counts.fill(0)
        for (v in order) {
            val d = depth[v]
            posInLayer[v] = counts[d]
            layers[d][counts[d]++] = v
        }
    }

    fun childCount(v: Int): Int = childStart[v + 1] - childStart[v]

    fun child(v: Int, i: Int): Int = childList[childStart[v] + i]
}

/**
 * Tidy-tree layout of the forest along x (Reingold-Tilford with contours, for boxes of different widths).
 * Box centers come out as multiples of [GraphMetrics.COLUMN].
 * `sepAfter[v]` is the free space needed between `v` and its right neighbor in [Forest.layers].
 * The merge of the shallower contour into the deeper one costs the shallower one's height, so the total work is
 * linear in the number of nodes.
 */
internal class TidyForest(private val forest: Forest, private val halfWidth: IntArray) {
    /** Outline of a subtree by depth. Entry `i` is depth `deepest - i`, so a parent appends and no entry moves. */
    private class Contour(capacity: Int) {
        /** Added to every stored coordinate: moving a subtree is O(1). */
        var base = 0
        var size = 0
        var leftX = IntArray(capacity)
        var rightX = IntArray(capacity)

        /** The node whose right edge is `rightX`, used to index `sepAfter`. */
        var rightNode = IntArray(capacity)

        fun append(left: Int, right: Int, node: Int) {
            if (size == leftX.size) {
                val capacity = size * 2
                leftX = leftX.copyOf(capacity)
                rightX = rightX.copyOf(capacity)
                rightNode = rightNode.copyOf(capacity)
            }
            leftX[size] = left - base
            rightX[size] = right - base
            rightNode[size] = node
            size++
        }
    }

    /** Centre x of every node. */
    fun layout(sepAfter: IntArray): IntArray {
        val contours = arrayOfNulls<Contour>(forest.size)
        val offset = IntArray(forest.size) // relative to the parent, absolute for roots
        val order = forest.preorder
        for (k in order.indices.reversed()) {
            val v = order[k]
            val count = forest.childCount(v)
            val contour: Contour
            if (count == 0) {
                contour = Contour(2)
            } else {
                var merged = contours[forest.child(v, 0)]!!
                contours[forest.child(v, 0)] = null
                for (i in 1 until count) {
                    val c = forest.child(v, i)
                    merged = place(merged, contours[c]!!, sepAfter)
                    contours[c] = null
                    offset[c] = lastShift
                }
                val centre = floorToClass(offset[forest.child(v, count - 1)] / 2, 0, GraphMetrics.COLUMN)
                for (i in 0 until count) offset[forest.child(v, i)] -= centre
                merged.base -= centre
                contour = merged
            }
            contour.append(-halfWidth[v], halfWidth[v], v)
            contours[v] = contour
        }

        val roots = forest.roots
        if (roots.isNotEmpty()) {
            var merged = contours[roots[0]]!!
            for (i in 1 until roots.size) {
                merged = place(merged, contours[roots[i]]!!, sepAfter)
                offset[roots[i]] = lastShift
            }
        }
        val x = IntArray(forest.size)
        for (v in order) x[v] = offset[v] + (if (forest.parent[v] < 0) 0 else x[forest.parent[v]])
        return x
    }

    private var lastShift = 0

    /**
     * Puts [sub] to the right of [acc], as far left as every common depth lets it. [acc]'s first root must be at 0.
     * Returns the outline of both, and the position of [sub]'s root in [lastShift].
     */
    private fun place(acc: Contour, sub: Contour, sepAfter: IntArray): Contour {
        val common = min(acc.size, sub.size)
        var shift = Int.MIN_VALUE
        for (j in 1..common) {
            val a = acc.size - j
            val s = sub.size - j
            val needed = acc.rightX[a] + acc.base + sepAfter[acc.rightNode[a]] - (sub.leftX[s] + sub.base)
            shift = max(shift, needed)
        }
        shift = ceilToClass(shift, 0, GraphMetrics.COLUMN)
        lastShift = shift
        if (acc.size >= sub.size) {
            for (j in 1..common) {
                val a = acc.size - j
                val s = sub.size - j
                acc.rightX[a] = sub.rightX[s] + sub.base + shift - acc.base
                acc.rightNode[a] = sub.rightNode[s]
            }
            return acc
        }
        sub.base += shift
        for (j in 1..common) sub.leftX[sub.size - j] = acc.leftX[acc.size - j] + acc.base - sub.base
        return sub
    }
}
