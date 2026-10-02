package kotlinx.coroutree.gui.graph

import kotlinx.coroutree.gui.view.graph.DrawnBox
import kotlinx.coroutree.gui.view.graph.DrawnLine
import kotlinx.coroutree.gui.view.graph.Drawing
import kotlinx.coroutree.gui.view.graph.InvariantChecker
import kotlinx.coroutree.gui.view.graph.LayoutEngine
import kotlinx.coroutree.gui.view.graph.LineEnd
import kotlinx.coroutree.gui.view.graph.Point
import kotlinx.coroutree.gui.view.graph.Rect
import kotlinx.coroutree.gui.view.graph.Segment
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corners of the checker that InvariantCheckerTest leaves open: the one exception of rule 2 (the shared trunk),
 * the exact distance at which each remaining clause turns, drawings that are malformed before any rule applies, and
 * the checker's own machinery (the sweep that finds near pairs) — which must not depend on how a drawing is oriented
 * or in which order its shapes are listed, or a tall trace would be judged by other rules than a wide one.
 */
class InvariantCheckerRulesTest {
    private val checker = InvariantChecker(minBoxGap = 24, minLineGap = 6, boxClearance = 8, labelClearance = 2)

    // The same four boxes as in InvariantCheckerTest: two rows of two, 100 × 40 each, a channel of 60 between the rows.
    private val a = DrawnBox(1, Rect(0, 0, 100, 40))
    private val b = DrawnBox(2, Rect(200, 0, 300, 40))
    private val c = DrawnBox(3, Rect(0, 100, 100, 140))
    private val d = DrawnBox(4, Rect(200, 100, 300, 140))
    private val boxes = listOf(a, b, c, d)

    private fun line(name: String, from: Long, to: Long, vararg points: Pair<Int, Int>, label: Rect? = null): DrawnLine {
        val path = points.map { Point(it.first, it.second) }
        return DrawnLine(name, path.zipWithNext { p, q -> Segment(p.x, p.y, q.x, q.y) }, listOf(LineEnd(path.first(), from), LineEnd(path.last(), to)), label)
    }

    private fun rules(lines: List<DrawnLine>, boxes: List<DrawnBox> = this.boxes): Set<Int> =
        checker.check(Drawing(boxes, lines)).map { it.rule }.toSet()

    private fun assertLegal(vararg lines: DrawnLine, boxes: List<DrawnBox> = this.boxes) =
        assertEquals(emptyList(), checker.check(Drawing(boxes, lines.toList())))

    // a → d along y = 60, as in InvariantCheckerTest.
    private val aToD = line("a→d", 1, 4, 50 to 40, 50 to 60, 250 to 60, 250 to 100)

    /** The structural edges of a to its children c and d as the engine draws them: one shape with a trunk, a bus and two drops. */
    private val family = DrawnLine(
        "structure of 1",
        listOf(Segment(50, 40, 50, 70), Segment(50, 70, 250, 70), Segment(50, 70, 50, 100), Segment(250, 70, 250, 100)),
        listOf(LineEnd(Point(50, 40), 1), LineEnd(Point(50, 100), 3), LineEnd(Point(250, 100), 4)),
        null,
    )

    // ------------------------------------------------------------------ rule 2: the trunk exception and its limits

    @Test
    fun oneParentsStructuralEdgesMayShareATrunk() {
        assertLegal(family)
        // A cross-link may cross the bus and a drop like any other line: at a point, at a right angle, away from bends.
        assertLegal(family, line("b→c", 2, 3, 230 to 40, 230 to 85, 30 to 85, 30 to 100))
    }

    @Test
    fun theTrunkExceptionIsForOneLineOnlyTwoLinesDrawnAsATrunkAreTooClose() {
        // The same picture as `family`, but drawn as two edges that happen to share the piece below a.
        val toC = line("a→c", 1, 3, 50 to 40, 50 to 100)
        val toD = line("a→d", 1, 4, 50 to 40, 50 to 70, 250 to 70, 250 to 100)
        assertTrue(2 in rules(listOf(toC, toD)), "two separate lines on one stretch")
    }

    @Test
    fun noOtherLineMayRunOnOrTouchATrunk() {
        // b → c comes down onto the bus and follows it.
        val onTheBus = rules(listOf(family, line("b→c", 2, 3, 230 to 40, 230 to 70, 30 to 70, 30 to 100)))
        assertTrue(2 in onTheBus && 3 in onTheBus, "runs on the bus (2) after meeting it in a T (3): $onTheBus")
        // b → d runs down beside the drop to d, 5 away from it.
        assertEquals(setOf(2), rules(listOf(family, line("b→d", 2, 4, 255 to 40, 255 to 100))))
        assertLegal(family, line("b→d", 2, 4, 256 to 40, 256 to 100))
    }

    @Test
    fun aLineMayNotRunOverItself() {
        // a → d goes right along y = 60, comes back along the same track, and only then goes down.
        val doubled = line("a→d", 1, 4, 50 to 40, 50 to 60, 230 to 60, 150 to 60, 150 to 80, 250 to 80, 250 to 100)
        assertEquals(setOf(2), rules(listOf(doubled)))
    }

    // ------------------------------------------------------------------ rule 3: exactly where a crossing stops being one

    @Test
    fun aCrossingIsLegalSixAwayFromEveryBendAndIllegalAtFive() {
        fun crossingAt(x: Int) = line("b→c", 2, 3, x to 40, x to 80, 30 to 80, 30 to 100)
        // a → d bends at (250, 60).
        assertLegal(aToD, crossingAt(244))
        assertTrue(3 in rules(listOf(aToD, crossingAt(245))))

        // b → c crosses y = 60 and bends itself at y = 66, then at 65.
        fun bendingAt(y: Int) = line("b→c", 2, 3, 230 to 40, 230 to y, 30 to y, 30 to 100)
        assertLegal(aToD, bendingAt(66))
        assertTrue(3 in rules(listOf(aToD, bendingAt(65))))
    }

    // ------------------------------------------------------------------ rule 4: a line and its own boxes

    @Test
    fun aLineMayNotComeBackToABoxItEndsAt() {
        // Leaves a, turns back up and passes along a's bottom edge 5 below it on its way to d.
        val back = line("a→d", 1, 4, 50 to 40, 50 to 60, 90 to 60, 90 to 45, 350 to 45, 350 to 80, 250 to 80, 250 to 100)
        assertEquals(setOf(4), rules(listOf(back)))
    }

    // ------------------------------------------------------------------ rule 5: the exact clearances of a label

    @Test
    fun aLabelKeepsItsClearanceFromBoxesLinesAndLabelsAndNotOneUnitLess() {
        fun labelled(label: Rect) = line("a→d", 1, 4, 50 to 40, 50 to 60, 250 to 60, 250 to 100, label = label)
        val bToC = line("b→c", 2, 3, 230 to 40, 230 to 80, 30 to 80, 30 to 100)

        // A box below the label: 2 away, then 1.
        val label = Rect(120, 53, 180, 67)
        assertLegal(labelled(label), boxes = boxes + DrawnBox(5, Rect(125, 69, 175, 109)))
        assertEquals(setOf(5), rules(listOf(labelled(label)), boxes + DrawnBox(5, Rect(125, 68, 175, 108))))

        // Another line beside the label: b → c comes down at x = 230.
        assertLegal(labelled(Rect(168, 53, 228, 67)), bToC)
        assertEquals(setOf(5), rules(listOf(labelled(Rect(169, 53, 229, 67)), bToC)))

        // Another label below it.
        fun other(top: Int) = line("b→c", 2, 3, 230 to 40, 230 to 80, 30 to 80, 30 to 100, label = Rect(130, top, 190, top + 14))
        assertLegal(labelled(label), other(69))
        assertEquals(setOf(5), rules(listOf(labelled(label), other(68))))
    }

    @Test
    fun aLabelLiesOnAHorizontalPieceOfItsOwnLineFromEndToEnd() {
        fun labelled(label: Rect) = line("a→d", 1, 4, 50 to 40, 50 to 60, 250 to 60, 250 to 100, label = label)
        assertLegal(labelled(Rect(50, 53, 250, 67)))
        assertEquals(setOf(5), rules(listOf(labelled(Rect(44, 53, 180, 67)))), "sticks out past the bend of its line")
        assertEquals(setOf(5), rules(listOf(labelled(Rect(243, 70, 257, 90)))), "sits on a vertical piece")
    }

    // ------------------------------------------------------------------ rule 0: not a drawing at all

    @Test
    fun malformedDrawingsAreReportedBeforeAnyRule() {
        assertTrue(0 in rules(emptyList(), boxes + DrawnBox(1, Rect(400, 0, 500, 40))), "one box drawn twice")
        assertTrue(0 in rules(emptyList(), boxes + DrawnBox(5, Rect(400, 0, 400, 40))), "a box without width")
        assertTrue(0 in rules(listOf(DrawnLine("nothing", emptyList(), listOf(LineEnd(Point(50, 40), 1), LineEnd(Point(250, 100), 4)), null))), "a line without segments")
        val withAPoint = DrawnLine("a→d", aToD.segments + Segment(50, 60, 50, 60), aToD.ends, null)
        assertTrue(0 in rules(listOf(withAPoint)), "a segment of no length")
        val emptyLabel = DrawnLine("a→d", aToD.segments, aToD.ends, Rect(120, 60, 180, 60))
        assertTrue(0 in rules(listOf(emptyLabel)), "a label without height")
    }

    // ------------------------------------------------------------------ the sweep

    private fun Rect.transposed() = Rect(top, left, bottom, right)

    /** The drawing mirrored at the diagonal: what was wide is deep. Labels are left out, they exist on horizontals only. */
    private fun Drawing.transposed() = Drawing(
        boxes.map { DrawnBox(it.id, it.rect.transposed()) },
        lines.map { line ->
            DrawnLine(line.name, line.segments.map { Segment(it.y1, it.x1, it.y2, it.x2) }, line.ends.map { LineEnd(Point(it.point.y, it.point.x), it.boxId) }, null)
        },
    )

    private fun Drawing.withoutLabels() = Drawing(boxes, lines.map { DrawnLine(it.name, it.segments, it.ends, null) })

    private fun count(drawing: Drawing): Map<Int, Int> =
        InvariantChecker(maxViolations = 100_000).check(drawing).groupingBy { it.rule }.eachCount()

    /** A real layout with several things wrong in it, all over the drawing. */
    private fun damaged(drawing: Drawing, random: Random): Drawing {
        // The first box always moves, away from under the lines that end at it: every damaged drawing is illegal.
        val boxes = drawing.boxes.mapIndexed { i, it ->
            if (i == 0 || random.nextInt(6) == 0) DrawnBox(it.id, Rect(it.rect.left + 30, it.rect.top + 20, it.rect.right + 30, it.rect.bottom + 20)) else it
        }
        val lines = drawing.lines.map { line ->
            if (random.nextInt(4) != 0) line
            else DrawnLine(line.name, line.segments.map { Segment(it.x1 + 4, it.y1 + 3, it.x2 + 4, it.y2 + 3) }, line.ends, null)
        }
        return Drawing(boxes, lines)
    }

    @Test
    fun aDrawingIsJudgedTheSameWhicheverWayItIsTurned() {
        val random = Random(11)
        for (shape in RandomGraphs.Shape.entries) for (seed in 1..12) {
            val drawing = LayoutEngine.layout(RandomGraphs.forest(seed, 50, shape, links = 35)).toDrawing().withoutLabels()
            assertEquals(emptyMap(), count(drawing.transposed()), "$shape seed $seed: a legal drawing is legal on its side")
            val broken = damaged(drawing, random)
            val found = count(broken)
            assertTrue(found.isNotEmpty(), "$shape seed $seed: the damage is real")
            assertEquals(found, count(broken.transposed()), "$shape seed $seed: the same violations, wide or deep")
        }
    }

    @Test
    fun aDrawingIsJudgedTheSameInWhateverOrderItsShapesAreListed() {
        val random = Random(5)
        for (seed in 1..20) {
            val layout = LayoutEngine.layout(RandomGraphs.forest(seed, 60, RandomGraphs.Shape.entries[seed % 4], links = 40))
            val broken = damaged(layout.toDrawing(), random)
            val shuffled = Drawing(broken.boxes.shuffled(random), broken.lines.shuffled(random).map { DrawnLine(it.name, it.segments.shuffled(random), it.ends.shuffled(random), it.label) })
            assertEquals(count(broken), count(shuffled), "seed $seed")
        }
    }

    @Test
    fun violationsFarApartInADeepDrawingAreAllFound() {
        // A chain of 400 boxes straight down, each joined to the next; every 50th box is moved onto its neighbour.
        val chain = (0 until 400).map { DrawnBox(it + 1L, Rect(0, it * 100, 100, it * 100 + 40)) }
        val edges = (0 until 399).map { line("edge $it", it + 1L, it + 2L, 50 to it * 100 + 40, 50 to it * 100 + 100) }
        assertEquals(emptyList(), checker.check(Drawing(chain, edges)))
        val moved = chain.map { if (it.id % 50 == 0L) DrawnBox(it.id, Rect(0, it.rect.top - 45, 100, it.rect.bottom - 45)) else it }
        val violations = InvariantChecker(maxViolations = 1000).check(Drawing(moved, edges))
        for (id in 50L..400L step 50) {
            assertTrue(violations.any { it.rule == 1 && "boxes ${id - 1} and $id" in it.message || "boxes $id and ${id - 1}" in it.message }, "box $id on top of ${id - 1} went unnoticed")
        }
    }
}
