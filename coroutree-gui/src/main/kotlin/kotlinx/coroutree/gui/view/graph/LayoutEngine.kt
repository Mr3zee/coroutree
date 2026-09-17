package kotlinx.coroutree.gui.view.graph

import kotlinx.coroutree.gui.view.graph.GraphMetrics.BOX_GAP
import kotlinx.coroutree.gui.view.graph.GraphMetrics.CHANNEL_PAD
import kotlinx.coroutree.gui.view.graph.GraphMetrics.COLUMN
import kotlinx.coroutree.gui.view.graph.GraphMetrics.GAP_CLEARANCE
import kotlinx.coroutree.gui.view.graph.GraphMetrics.LABEL_CLEARANCE
import kotlinx.coroutree.gui.view.graph.GraphMetrics.LABEL_HEIGHT
import kotlinx.coroutree.gui.view.graph.GraphMetrics.LABEL_PAD
import kotlinx.coroutree.gui.view.graph.GraphMetrics.LINE_GAP
import kotlinx.coroutree.gui.view.graph.GraphMetrics.MARGIN
import kotlinx.coroutree.gui.view.graph.GraphMetrics.MIN_CHANNEL_HEIGHT
import kotlinx.coroutree.gui.view.graph.GraphMetrics.MIN_LABEL_WIDTH
import kotlinx.coroutree.gui.view.graph.GraphMetrics.NODE_HEIGHT
import kotlinx.coroutree.gui.view.graph.GraphMetrics.PORT_INSET
import kotlin.math.max
import kotlin.math.min

/**
 * Lays out a [GraphModel]: the structural forest as a tidy tree, top down, and every cross-link as an orthogonal
 * route. Pure geometry and deterministic: the same model gives the same drawing.
 *
 * The drawing invariant (DESIGN §6.2) holds by construction, not by repair:
 *
 * - Boxes stand in layers by depth. Between two layers lies a **channel** of horizontal tracks, between two
 *   neighbours of a layer a **gap** of vertical tracks; nothing but boxes is in a layer's band and nothing but
 *   lines in a channel.
 * - A cross-link leaves a port on its upper box, and on its way down alternates between a horizontal piece in a
 *   channel and a vertical piece through a gap of the next layer, until it reaches a port on its lower box. Which
 *   gap it takes in each layer is decided on a preliminary layout; the gaps are then made as wide as their tracks
 *   need and the forest is laid out again. That cannot invalidate the choice: the order of boxes in a layer is a
 *   property of the forest, not of the layout.
 * - Every vertical piece has an x of its own ([GraphMetrics]: three residue classes, so that pieces entering a
 *   channel from above and from below can never meet), every horizontal piece a track of its own wherever it
 *   overlaps another. So lines cross at right angles and away from bends, or not at all.
 * - A label lies on a horizontal piece of its edge, on a stretch no vertical line of the channel passes, and its
 *   track is as high as the label.
 *
 * What is heuristic is only the number of crossings: the order of ports on a box, of tracks in a gap and of tracks
 * in a channel is chosen so that lines heading the same way nest instead of crossing, and the bus of a parent's
 * structural edges runs below the cross-links of its channel (one crossing with the trunk instead of one per drop).
 */
object LayoutEngine {
    fun layout(graph: GraphModel): GraphLayout = if (graph.nodes.isEmpty()) GraphLayout.EMPTY else Pass(graph).run()

    private class Link(val input: Int, val from: Int, val upper: Int, val lower: Int, val sameLayer: Boolean, val labelWidth: Int) {
        /** Per layer strictly between the two ends: the gap taken (index of the box to its right) and roughly where. */
        var gaps = EMPTY
        var approx = EMPTY

        /** x of every vertical piece, top down: the upper port, the gap tracks, the lower port. */
        var xs = EMPTY

        /** y of every horizontal piece, top down. */
        var ys = EMPTY
        var labelPiece = -1
        var labelLeft = 0
        var labelRight = 0
    }

    private class PortEnd(val link: Link, val upperEnd: Boolean, val key: Int)

    /** A horizontal piece in a channel. [link] is `null` for the bus of [parent]. */
    private class Piece(val left: Int, val right: Int, val link: Link?, val index: Int, val parent: Int, val halfHeight: Int)

    private val EMPTY = IntArray(0)

    private class Pass(private val graph: GraphModel) {
        private val forest = Forest(graph.nodes)
        private val n = forest.size
        private val depth = forest.depth
        private val layers = forest.layers
        private val links = ArrayList<Link>()
        private val halfWidth = IntArray(n)
        private lateinit var x: IntArray
        private val topPorts = arrayOfNulls<ArrayList<PortEnd>>(n)
        private val bottomPorts = arrayOfNulls<ArrayList<PortEnd>>(n)

        /** y of the structural buses of each channel. */
        private val busY = IntArray(layers.size)

        fun run(): GraphLayout {
            collectLinks()
            sizeBoxes()
            val tidy = TidyForest(forest, halfWidth)
            val rough = tidy.layout(IntArray(n) { BOX_GAP })
            val gapLinks = chooseGaps(rough)
            x = tidy.layout(separations(gapLinks))
            assignGapTracks(gapLinks, rough)
            assignPorts(rough)
            normalizeX()
            val channels = collectPieces()
            val layerTops = assignTracks(channels)
            return build(layerTops)
        }

        private fun collectLinks() {
            graph.links.forEachIndexed { i, link ->
                val from = forest.indexOf[link.from] ?: return@forEachIndexed
                val to = forest.indexOf[link.to] ?: return@forEachIndexed
                if (from == to) return@forEachIndexed // an edge needs two boxes
                val upper = if (depth[to] < depth[from]) to else from
                links += Link(i, from, upper, if (upper == from) to else from, depth[from] == depth[to], link.labelWidth)
            }
        }

        /** A link leaves its upper box at the bottom and enters its lower box at the top; between two boxes of one layer it hangs below both. */
        private fun sizeBoxes() {
            val top = IntArray(n)
            val bottom = IntArray(n)
            for (link in links) {
                bottom[link.upper]++
                if (link.sameLayer) bottom[link.lower]++ else top[link.lower]++
            }
            for (v in 0 until n) {
                val ports = max(top[v], bottom[v])
                val forPorts = if (ports == 0) 0 else 2 * (PORT_INSET + COLUMN * ((ports + 1) / 2))
                val width = max(graph.nodes[v].width, forPorts)
                halfWidth[v] = (width + 1) / 2
            }
        }

        /**
         * Walks every link down the rough layout and picks, in each layer it has to pass, the gap that takes it the
         * least out of its way: the one it is already over, or else the one beside the box in its way, on the side
         * of its target.
         */
        private fun chooseGaps(rough: IntArray): Array<Array<ArrayList<Link>?>> {
            val gapLinks = Array(layers.size) { arrayOfNulls<ArrayList<Link>>(layers[it].size + 1) }
            for (link in links) {
                val from = depth[link.upper]
                val between = depth[link.lower] - from - 1
                if (link.sameLayer || between <= 0) continue
                link.gaps = IntArray(between)
                link.approx = IntArray(between)
                var current = rough[link.upper]
                val target = rough[link.lower]
                for (m in 0 until between) {
                    val row = layers[from + 1 + m]
                    // First box whose right edge is not left of us.
                    var lo = 0
                    var hi = row.size
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (rough[row[mid]] + halfWidth[row[mid]] < current) lo = mid + 1 else hi = mid
                    }
                    var gap = lo
                    if (lo < row.size) {
                        val box = row[lo]
                        val left = rough[box] - halfWidth[box]
                        val right = rough[box] + halfWidth[box]
                        if (current >= left) {
                            val goRight = if (target != current) target > current else right - current < current - left
                            if (goRight) {
                                gap = lo + 1
                                current = right + GAP_CLEARANCE
                            } else {
                                current = left - GAP_CLEARANCE
                            }
                        }
                    }
                    link.gaps[m] = gap
                    link.approx[m] = current
                    val list = gapLinks[from + 1 + m][gap] ?: ArrayList<Link>().also { gapLinks[from + 1 + m][gap] = it }
                    list += link
                }
            }
            return gapLinks
        }

        /** Free space each box needs to its right: room for the tracks of the gap there. The gaps at the ends of a layer are unbounded. */
        private fun separations(gapLinks: Array<Array<ArrayList<Link>?>>): IntArray {
            val sepAfter = IntArray(n) { BOX_GAP }
            for (layer in layers.indices) {
                val row = layers[layer]
                for (gap in 1 until row.size) {
                    val tracks = gapLinks[layer][gap]?.size ?: continue
                    // One column more than the tracks take: the first one may be up to a column away from the box.
                    sepAfter[row[gap - 1]] = max(BOX_GAP, 2 * GAP_CLEARANCE + tracks * COLUMN)
                }
            }
            return sepAfter
        }

        private fun assignGapTracks(gapLinks: Array<Array<ArrayList<Link>?>>, rough: IntArray) {
            for (layer in layers.indices) {
                val row = layers[layer]
                val residue = GraphMetrics.linkClass(layer)
                for (gap in 0..row.size) {
                    val passing = gapLinks[layer][gap] ?: continue
                    // Left to right by where a link comes from and goes to, so that neighbours in a gap do not swap sides.
                    val piece = IntArray(passing.size) { layer - depth[passing[it].upper] - 1 }
                    val order = passing.indices.sortedWith(
                        compareBy<Int> { i ->
                            val link = passing[i]
                            val m = piece[i]
                            val before = if (m == 0) rough[link.upper] else link.approx[m - 1]
                            val after = if (m == link.gaps.size - 1) rough[link.lower] else link.approx[m + 1]
                            before.toLong() + after
                        }.thenBy { passing[it].input },
                    )
                    val count = order.size
                    val first = when (gap) {
                        0 -> floorToClass(x[row[0]] - halfWidth[row[0]] - GAP_CLEARANCE, residue, COLUMN) - (count - 1) * COLUMN
                        row.size -> ceilToClass(x[row.last()] + halfWidth[row.last()] + GAP_CLEARANCE, residue, COLUMN)
                        else -> {
                            val lo = ceilToClass(x[row[gap - 1]] + halfWidth[row[gap - 1]] + GAP_CLEARANCE, residue, COLUMN)
                            val hi = x[row[gap]] - halfWidth[row[gap]] - GAP_CLEARANCE
                            val available = (hi - lo) / COLUMN + 1
                            check(hi >= lo && available >= count) { "gap $gap of layer $layer has room for $available tracks, needs $count" }
                            lo + (available - count) / 2 * COLUMN
                        }
                    }
                    order.forEachIndexed { track, i ->
                        val link = passing[i]
                        if (link.xs.isEmpty()) link.xs = IntArray(link.gaps.size + 2)
                        link.xs[piece[i] + 1] = first + track * COLUMN
                    }
                }
            }
        }

        private fun assignPorts(rough: IntArray) {
            for (link in links) {
                if (link.xs.isEmpty()) link.xs = IntArray(2)
                val towardsLower = if (link.approx.isEmpty()) rough[link.lower] else link.approx.first()
                val towardsUpper = if (link.approx.isEmpty()) rough[link.upper] else link.approx.last()
                bottomPorts.add(link.upper, PortEnd(link, upperEnd = true, towardsLower))
                (if (link.sameLayer) bottomPorts else topPorts).add(link.lower, PortEnd(link, upperEnd = false, towardsUpper))
            }
            for (v in 0 until n) {
                for (side in arrayOf(topPorts[v], bottomPorts[v])) {
                    if (side == null) continue
                    // The line that heads furthest left takes the leftmost port.
                    side.sortWith(compareBy<PortEnd> { it.key }.thenBy { it.link.input })
                    val first = x[v] + GraphMetrics.linkClass(depth[v]) - side.size / 2 * COLUMN
                    side.forEachIndexed { i, end ->
                        val port = first + i * COLUMN
                        check(port - (x[v] - halfWidth[v]) >= PORT_INSET && x[v] + halfWidth[v] - port >= PORT_INSET) { "port outside its box" }
                        if (end.upperEnd) end.link.xs[0] = port else end.link.xs[end.link.xs.size - 1] = port
                    }
                }
            }
        }

        private fun Array<ArrayList<PortEnd>?>.add(v: Int, end: PortEnd) {
            (this[v] ?: ArrayList<PortEnd>(2).also { this[v] = it }) += end
        }

        /** Moves the drawing so that it starts at [MARGIN], by whole columns: the residue classes stay what they are. */
        private fun normalizeX() {
            var left = Int.MAX_VALUE
            for (v in 0 until n) left = min(left, x[v] - halfWidth[v])
            for (link in links) for (value in link.xs) left = min(left, value)
            val shift = ceilToClass(MARGIN - left, 0, COLUMN)
            for (v in 0 until n) x[v] += shift
            for (link in links) for (i in link.xs.indices) link.xs[i] += shift
        }

        /** The horizontal pieces of every channel; channel `c` lies below layer `c`. Places the labels on the way. */
        private fun collectPieces(): Array<ArrayList<Piece>> {
            val channels = Array(layers.size) { ArrayList<Piece>() }
            val verticals = arrayOfNulls<IntArray>(layers.size)
            for (link in links) {
                val first = depth[link.upper]
                if (link.labelWidth > 0) placeLabel(link, first, verticals)
                for (i in 0 until link.xs.size - 1) {
                    val labelled = i == link.labelPiece
                    channels[first + i] += Piece(
                        min(link.xs[i], link.xs[i + 1]), max(link.xs[i], link.xs[i + 1]), link, i, -1,
                        if (labelled) LABEL_HEIGHT / 2 + LABEL_PAD else 0,
                    )
                }
                link.ys = IntArray(link.xs.size - 1)
            }
            for (v in 0 until n) {
                val count = forest.childCount(v)
                if (count == 0) continue
                val left = min(x[v], x[forest.child(v, 0)])
                val right = max(x[v], x[forest.child(v, count - 1)])
                if (left < right) channels[depth[v]] += Piece(left, right, null, 0, v, 0)
            }
            return channels
        }

        /**
         * A label goes on the longest horizontal piece that has room for it, between two neighbouring vertical
         * lines of that channel: whichever of them reach the label's track, none passes through the label. Room for
         * less than the whole label truncates it; room for less than [MIN_LABEL_WIDTH] leaves the edge without.
         */
        private fun placeLabel(link: Link, firstChannel: Int, verticals: Array<IntArray?>) {
            val pieces = (0 until link.xs.size - 1).sortedByDescending { kotlin.math.abs(link.xs[it + 1] - link.xs[it]) }
            var bestWidth = 0
            for (piece in pieces.take(LABEL_PIECES_TRIED)) {
                val channel = firstChannel + piece
                val all = verticals[channel] ?: verticalsOf(channel).also { verticals[channel] = it }
                val left = min(link.xs[piece], link.xs[piece + 1])
                val right = max(link.xs[piece], link.xs[piece + 1])
                if (right - left - 2 * LABEL_CLEARANCE <= bestWidth) break
                var i = all.binarySearch(left).let { if (it < 0) -it - 1 else it }
                var tried = 0
                while (i + 1 < all.size && all[i] < right && tried++ < LABEL_SLOTS_TRIED) {
                    val from = all[i] + LABEL_CLEARANCE
                    val to = min(all[i + 1], right) - LABEL_CLEARANCE
                    val width = min(to - from, link.labelWidth)
                    if (width > bestWidth) {
                        bestWidth = width
                        link.labelPiece = piece
                        link.labelLeft = from + (to - from - width) / 2
                        link.labelRight = link.labelLeft + width
                        if (width == link.labelWidth) return
                    }
                    i++
                }
            }
            if (bestWidth < MIN_LABEL_WIDTH) link.labelPiece = -1
        }

        /** x of every vertical line that enters channel [channel], sorted. */
        private fun verticalsOf(channel: Int): IntArray {
            val result = ArrayList<Int>()
            for (v in layers[channel]) {
                val count = forest.childCount(v)
                if (count > 0) result += x[v]
                for (i in 0 until count) result += x[forest.child(v, i)]
            }
            for (link in links) {
                val i = channel - depth[link.upper]
                if (i in 0 until link.xs.size - 1) {
                    result += link.xs[i]
                    result += link.xs[i + 1]
                }
            }
            return result.toIntArray().also { it.sort() }
        }

        /**
         * Gives every horizontal piece its y. Pieces are taken in the order that makes lines nest, and each goes
         * below whatever it overlaps among those taken before it, so two pieces are on one track only where they
         * are [LINE_GAP] apart along it. Returns the top of every layer.
         */
        private fun assignTracks(channels: Array<ArrayList<Piece>>): IntArray {
            val layerTops = IntArray(layers.size)
            var top = MARGIN
            for (channel in channels.indices) {
                layerTops[channel] = top
                val channelTop = top + NODE_HEIGHT
                val pieces = channels[channel]
                if (pieces.isEmpty()) {
                    top = channelTop + MIN_CHANNEL_HEIGHT
                    continue
                }
                val (buses, crossing) = pieces.partition { it.link == null }
                val ordered = crossing.sortedWith(PIECE_ORDER)
                val tracks = TrackProfile(pieces)
                val offsets = IntArray(ordered.size)
                ordered.forEachIndexed { i, piece ->
                    val above = tracks.lowest(piece.left, piece.right + LINE_GAP)
                    offsets[i] = (if (above == 0) CHANNEL_PAD else above + LINE_GAP) + piece.halfHeight
                    tracks.occupy(piece.left, piece.right + LINE_GAP, offsets[i] + piece.halfHeight)
                }
                // Buses of one channel never overlap, so they can share the lowest track any of them needs: siblings line up.
                var busOffset = 0
                for (bus in buses) {
                    val above = tracks.lowest(bus.left, bus.right + LINE_GAP)
                    busOffset = max(busOffset, if (above == 0) CHANNEL_PAD else above + LINE_GAP)
                }
                val content = max(busOffset, tracks.lowest()) + CHANNEL_PAD
                val height = max(content, MIN_CHANNEL_HEIGHT)
                val base = channelTop + (height - content) / 2
                ordered.forEachIndexed { i, piece -> piece.link!!.ys[piece.index] = base + offsets[i] }
                busY[channel] = base + busOffset
                top = channelTop + height
            }
            return layerTops
        }

        private fun build(layerTops: IntArray): GraphLayout {
            val boxes = ArrayList<NodeBox>(n)
            for (v in 0 until n) {
                val top = layerTops[depth[v]]
                boxes += NodeBox(graph.nodes[v], Rect(x[v] - halfWidth[v], top, x[v] + halfWidth[v], top + NODE_HEIGHT), depth[v])
            }
            val trunks = ArrayList<Trunk>()
            for (v in forest.preorder) {
                val count = forest.childCount(v)
                if (count == 0) continue
                val children = List(count) { forest.child(v, it) }
                trunks += Trunk(
                    parentId = graph.nodes[v].id,
                    childIds = children.map { graph.nodes[it].id },
                    top = Point(x[v], boxes[v].rect.bottom),
                    busY = busY[depth[v]],
                    drops = children.map { Point(x[it], boxes[it].rect.top) },
                )
            }
            val routes = ArrayList<LinkRoute>(links.size)
            for (link in links) {
                val points = ArrayList<Point>(link.xs.size * 2)
                points += Point(link.xs.first(), boxes[link.upper].rect.bottom)
                for (i in link.ys.indices) {
                    points += Point(link.xs[i], link.ys[i])
                    points += Point(link.xs[i + 1], link.ys[i])
                }
                val lowerBox = boxes[link.lower].rect
                points += Point(link.xs.last(), if (link.sameLayer) lowerBox.bottom else lowerBox.top)
                if (link.upper != link.from) points.reverse()
                val label = if (link.labelPiece < 0) null else {
                    val y = link.ys[link.labelPiece]
                    Rect(link.labelLeft, y - LABEL_HEIGHT / 2, link.labelRight, y + LABEL_HEIGHT / 2)
                }
                routes += LinkRoute(graph.links[link.input], points, label)
            }

            var right = 0
            var bottom = 0
            for (box in boxes) {
                right = max(right, box.rect.right)
                bottom = max(bottom, box.rect.bottom)
            }
            for (route in routes) for (point in route.points) {
                right = max(right, point.x)
                bottom = max(bottom, point.y)
            }
            val layerBoxes = Array(layers.size) { layers[it] }
            return GraphLayout(boxes, trunks, routes, Rect(0, 0, right + MARGIN, bottom + MARGIN), layerTops, layerBoxes)
        }
    }

    private const val LABEL_PIECES_TRIED = 3
    private const val LABEL_SLOTS_TRIED = 512

    /**
     * Top to bottom in a channel: links between two boxes of the layer above (both ends go up, the shorter inside
     * the longer), then links passing through: those heading right in descending order of where they come down,
     * those heading left in ascending order, which is the order in which two of a kind do not cross.
     */
    private val PIECE_ORDER: Comparator<Piece> = compareBy<Piece> { piece ->
        val link = piece.link!!
        when {
            link.sameLayer -> 0
            link.xs[piece.index] < link.xs[piece.index + 1] -> 1
            else -> 2
        }
    }.thenBy { piece ->
        val link = piece.link!!
        when {
            link.sameLayer -> piece.right - piece.left
            link.xs[piece.index] < link.xs[piece.index + 1] -> -link.xs[piece.index]
            else -> link.xs[piece.index]
        }
    }.thenBy { it.link!!.input }

    /**
     * How far down a channel is occupied, as a function of x: range maximum with range "raise to" updates, over the
     * coordinates the pieces of the channel begin and end at.
     */
    private class TrackProfile(pieces: List<Piece>) {
        private val coordinates: IntArray
        private val size: Int
        private val raised: IntArray // applies to the whole range of a tree node
        private val highest: IntArray // maximum within the range of a tree node, `raised` of its ancestors not included

        init {
            val all = IntArray(pieces.size * 2)
            pieces.forEachIndexed { i, piece ->
                all[2 * i] = piece.left
                all[2 * i + 1] = piece.right + LINE_GAP
            }
            all.sort()
            var unique = 0
            for (i in all.indices) if (i == 0 || all[i] != all[i - 1]) all[unique++] = all[i]
            coordinates = all.copyOf(unique)
            size = max(1, unique - 1) // elementary intervals between consecutive coordinates
            raised = IntArray(4 * size)
            highest = IntArray(4 * size)
        }

        private fun index(coordinate: Int): Int = coordinates.binarySearch(coordinate).also { check(it >= 0) }

        fun lowest(): Int = highest[1]

        /** The lowest occupied offset anywhere in `[from, to)`, 0 when the range is free. */
        fun lowest(from: Int, to: Int): Int = query(1, 0, size, index(from), index(to))

        fun occupy(from: Int, to: Int, downTo: Int) = update(1, 0, size, index(from), index(to), downTo)

        private fun query(node: Int, lo: Int, hi: Int, from: Int, to: Int): Int {
            if (from >= hi || to <= lo) return 0
            if (from <= lo && hi <= to) return highest[node]
            val mid = (lo + hi) ushr 1
            return max(raised[node], max(query(2 * node, lo, mid, from, to), query(2 * node + 1, mid, hi, from, to)))
        }

        private fun update(node: Int, lo: Int, hi: Int, from: Int, to: Int, value: Int) {
            if (from >= hi || to <= lo) return
            highest[node] = max(highest[node], value)
            if (from <= lo && hi <= to) {
                raised[node] = max(raised[node], value)
                return
            }
            val mid = (lo + hi) ushr 1
            update(2 * node, lo, mid, from, to, value)
            update(2 * node + 1, mid, hi, from, to, value)
        }
    }
}
