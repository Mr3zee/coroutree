package kotlinx.coroutree.gui.view.graph

import kotlin.math.max
import kotlin.math.min

/** A drawing reduced to what the invariant talks about: rectangles, axis-aligned segments, label boxes. */
class Drawing(val boxes: List<DrawnBox>, val lines: List<DrawnLine>)

class DrawnBox(val id: Long, val rect: Rect)

/**
 * One line of the drawing: an edge, or the structural edges of one parent, which the design allows to share a trunk
 * and therefore counts as one line.
 */
class DrawnLine(val name: String, val segments: List<Segment>, val ends: List<LineEnd>, val label: Rect?)

/** Where a line meets a box it belongs to. */
class LineEnd(val point: Point, val boxId: Long)

/** [rule] is the number of the rule in DESIGN §6.2; 0 for a drawing that is malformed before any rule applies. */
data class Violation(val rule: Int, val message: String) {
    override fun toString(): String = "rule $rule: $message"
}

/**
 * Checks a settled drawing against the drawing invariant of DESIGN §6.2. It knows nothing of how the engine works:
 * it looks at the shapes only, so it holds the engine to the rules rather than to its own idea of them.
 *
 * 1. No two boxes are closer than [minBoxGap].
 * 2. No two lines are closer than [minLineGap] anywhere, unless they cross.
 * 3. Two lines cross at one point, at a right angle, at least [minLineGap] away from any bend or end of either.
 * 4. A line keeps [boxClearance] from every box except the ones it ends at, and those it touches with its end only.
 * 5. A label lies on its own line and keeps [labelClearance] from every box, every other label and every other line.
 *
 * Distances are in the maximum norm. The cost is that of the pairs that are near each other, not of all pairs.
 */
class InvariantChecker(
    private val minBoxGap: Int = GraphMetrics.BOX_GAP,
    private val minLineGap: Int = GraphMetrics.LINE_GAP,
    private val boxClearance: Int = GraphMetrics.BOX_CLEARANCE,
    private val labelClearance: Int = 2,
    private val maxViolations: Int = 40,
) {
    private sealed class Shape(val bounds: Rect)
    private class BoxShape(val box: DrawnBox) : Shape(box.rect)
    private class SegmentShape(val line: Int, val index: Int, val segment: Segment) : Shape(segment.bounds)
    private class LabelShape(val line: Int, rect: Rect) : Shape(rect)

    fun check(drawing: Drawing): List<Violation> = Run(drawing).run()

    private inner class Run(private val drawing: Drawing) {
        private val violations = ArrayList<Violation>()
        private val boxes = HashMap<Long, DrawnBox>()
        private val endBoxes = drawing.lines.map { line -> line.ends.mapTo(HashSet()) { it.boxId } }
        private val reach = max(max(minBoxGap, minLineGap), max(boxClearance, labelClearance))

        // Union-find over the segments of each line: a line is one connected shape.
        private val offsets = IntArray(drawing.lines.size + 1)
        private lateinit var component: IntArray

        private fun report(rule: Int, message: String) {
            if (violations.size < maxViolations) violations += Violation(rule, message)
        }

        fun run(): List<Violation> {
            for (box in drawing.boxes) {
                if (box.rect.width <= 0 || box.rect.height <= 0) report(0, "box ${box.id} is empty: ${box.rect}")
                if (boxes.put(box.id, box) != null) report(0, "box ${box.id} is drawn twice")
            }
            drawing.lines.forEachIndexed { i, line -> offsets[i + 1] = offsets[i] + line.segments.size }
            component = IntArray(offsets.last()) { it }

            val shapes = ArrayList<Shape>()
            drawing.boxes.mapTo(shapes) { BoxShape(it) }
            drawing.lines.forEachIndexed { i, line ->
                checkShapeOf(line)
                line.segments.forEachIndexed { s, segment -> shapes += SegmentShape(i, s, segment) }
                line.label?.let { shapes += LabelShape(i, it) }
            }
            forEachNearPair(shapes, ::checkPair)

            drawing.lines.forEachIndexed { i, line ->
                val roots = (offsets[i] until offsets[i + 1]).mapTo(HashSet()) { find(it) }
                if (roots.size > 1) report(0, "${line.name} is in ${roots.size} pieces")
            }
            return violations
        }

        private fun checkShapeOf(line: DrawnLine) {
            if (line.segments.isEmpty()) report(0, "${line.name} has no segments")
            for (segment in line.segments) {
                if (!segment.horizontal && !segment.vertical) report(3, "${line.name} has a slanted segment $segment")
                if (segment.length == 0) report(0, "${line.name} has an empty segment $segment")
            }
            if (line.ends.size < 2) report(4, "${line.name} does not connect two boxes")
            for (end in line.ends) {
                val box = boxes[end.boxId]
                when {
                    box == null -> report(4, "${line.name} ends at box ${end.boxId}, which is not drawn")
                    !box.rect.onBorder(end.point.x, end.point.y) -> report(4, "${line.name} does not end on the outline of box ${end.boxId}: ${end.point} vs ${box.rect}")
                    line.segments.none { it.hasEndpoint(end.point) } -> report(4, "${line.name} has no segment ending at ${end.point}")
                }
            }
            line.label?.let { label ->
                if (label.width <= 0 || label.height <= 0) report(0, "label of ${line.name} is empty: $label")
                if (line.segments.none { it.horizontal && it.y1 in label.top..label.bottom && it.x1 <= label.left && it.x2 >= label.right }) {
                    report(5, "label of ${line.name} does not lie on its line: $label")
                }
            }
        }

        private fun checkPair(a: Shape, b: Shape) {
            when (a) {
                is BoxShape -> when (b) {
                    is BoxShape -> boxes(a.box, b.box)
                    is SegmentShape -> segmentAndBox(b, a.box)
                    is LabelShape -> labelAndBox(b, a.box)
                }
                is SegmentShape -> when (b) {
                    is BoxShape -> segmentAndBox(a, b.box)
                    is SegmentShape -> segments(a, b)
                    is LabelShape -> labelAndSegment(b, a)
                }
                is LabelShape -> when (b) {
                    is BoxShape -> labelAndBox(a, b.box)
                    is SegmentShape -> labelAndSegment(a, b)
                    is LabelShape -> if (a.bounds.distanceTo(b.bounds) < labelClearance || a.bounds.intersects(b.bounds)) {
                        report(5, "labels of ${name(a.line)} and ${name(b.line)} cover each other: ${a.bounds} and ${b.bounds}")
                    }
                }
            }
        }

        private fun name(line: Int) = drawing.lines[line].name

        private fun boxes(a: DrawnBox, b: DrawnBox) {
            if (a.rect.distanceTo(b.rect) < minBoxGap) report(1, "boxes ${a.id} and ${b.id} are closer than $minBoxGap: ${a.rect} and ${b.rect}")
        }

        private fun segments(a: SegmentShape, b: SegmentShape) {
            val p = a.segment
            val q = b.segment
            val touching = p.bounds.intersects(q.bounds)
            if (a.line == b.line) {
                if (touching) {
                    union(offsets[a.line] + a.index, offsets[b.line] + b.index)
                    val overlap = if (p.horizontal && q.horizontal) min(p.x2, q.x2) - max(p.x1, q.x1) else if (p.vertical && q.vertical) min(p.y2, q.y2) - max(p.y1, q.y1) else 0
                    if (overlap > 0) report(2, "${name(a.line)} runs over itself: $p and $q")
                }
                return
            }
            val parallel = p.horizontal == q.horizontal
            if (parallel || !touching) {
                if (p.bounds.distanceTo(q.bounds) < minLineGap) {
                    report(2, "${name(a.line)} and ${name(b.line)} ${if (touching) "run on each other" else "are closer than $minLineGap"}: $p and $q")
                }
                return
            }
            val h = if (p.horizontal) p else q
            val v = if (p.horizontal) q else p
            val clear = v.x1 - h.x1 >= minLineGap && h.x2 - v.x1 >= minLineGap && h.y1 - v.y1 >= minLineGap && v.y2 - h.y1 >= minLineGap
            if (!clear) report(3, "${name(a.line)} and ${name(b.line)} meet at or near a bend or an end instead of crossing: $p and $q")
        }

        private fun segmentAndBox(shape: SegmentShape, box: DrawnBox) {
            val line = drawing.lines[shape.line]
            val segment = shape.segment
            if (box.id !in endBoxes[shape.line]) {
                if (segment.bounds.distanceTo(box.rect) < boxClearance) report(4, "${line.name} passes box ${box.id} closer than $boxClearance: $segment vs ${box.rect}")
                return
            }
            val end = line.ends.firstOrNull { it.boxId == box.id && segment.hasEndpoint(it.point) }
            if (end == null) {
                if (segment.bounds.distanceTo(box.rect) < boxClearance) report(4, "${line.name} comes back to its own box ${box.id}: $segment vs ${box.rect}")
                return
            }
            // The segment that ends here touches the outline with that end and with nothing else: what remains of it
            // one step away from the end is outside the box.
            val rest = when {
                segment.length <= 1 -> null
                segment.vertical -> if (end.point.y == segment.y1) Segment(segment.x1, segment.y1 + 1, segment.x2, segment.y2) else Segment(segment.x1, segment.y1, segment.x2, segment.y2 - 1)
                else -> if (end.point.x == segment.x1) Segment(segment.x1 + 1, segment.y1, segment.x2, segment.y2) else Segment(segment.x1, segment.y1, segment.x2 - 1, segment.y2)
            }
            if (rest == null || rest.bounds.intersects(box.rect)) report(4, "${line.name} runs into or along its box ${box.id}: $segment vs ${box.rect}")
        }

        private fun labelAndBox(label: LabelShape, box: DrawnBox) {
            if (label.bounds.intersects(box.rect) || label.bounds.distanceTo(box.rect) < labelClearance) {
                report(5, "label of ${name(label.line)} covers box ${box.id}: ${label.bounds} vs ${box.rect}")
            }
        }

        private fun labelAndSegment(label: LabelShape, shape: SegmentShape) {
            if (label.line == shape.line) return
            if (label.bounds.intersects(shape.segment.bounds) || label.bounds.distanceTo(shape.segment.bounds) < labelClearance) {
                report(5, "label of ${name(label.line)} covers ${name(shape.line)}: ${label.bounds} vs ${shape.segment}")
            }
        }

        private fun find(i: Int): Int {
            var root = i
            while (component[root] != root) root = component[root]
            var current = i
            while (component[current] != root) {
                val next = component[current]
                component[current] = root
                current = next
            }
            return root
        }

        private fun union(a: Int, b: Int) {
            component[find(a)] = find(b)
        }

        /**
         * Calls [action] once for every two shapes whose bounds are within [reach] of each other. Sweep and prune:
         * shapes are met in order along the axis the drawing is longer in, and each is compared with the ones still
         * open at that point. A forest is either wide or deep, and along its long axis few shapes are open at once
         * (the long lines of a channel are, but comparing against them is two integer comparisons each).
         */
        private fun forEachNearPair(shapes: List<Shape>, action: (Shape, Shape) -> Unit) {
            if (shapes.isEmpty()) return
            val grown = shapes.map { it.bounds.inflated((reach + 1) / 2) }
            val all = grown.reduce(Rect::union)
            val alongX = all.width >= all.height
            val from = IntArray(shapes.size) { if (alongX) grown[it].left else grown[it].top }
            val to = IntArray(shapes.size) { if (alongX) grown[it].right else grown[it].bottom }
            val order = shapes.indices.sortedBy { from[it] }
            var open = IntArray(64)
            var openCount = 0
            for (i in order) {
                val bounds = grown[i]
                var k = 0
                while (k < openCount) {
                    val other = open[k]
                    if (to[other] < from[i]) {
                        open[k] = open[--openCount] // closed before this one begins, and so before all that follow
                        continue
                    }
                    val near = if (alongX) bounds.top <= grown[other].bottom && grown[other].top <= bounds.bottom
                    else bounds.left <= grown[other].right && grown[other].left <= bounds.right
                    if (near) action(shapes[other], shapes[i])
                    k++
                }
                if (openCount == open.size) open = open.copyOf(openCount * 2)
                open[openCount++] = i
            }
        }
    }
}
