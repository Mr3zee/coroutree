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
 * The checker is what enforces the drawing invariant, so it is itself held to it: every rule of DESIGN §6.2 has
 * drawings here that break it, next to legal ones that differ from them by one unit.
 *
 * In an orthogonal drawing a bad crossing (rule 3) always brings two lines too close as well (rule 2), so those cases
 * assert that rule 3 is among the findings; the others assert that their rule is the only one broken.
 */
class InvariantCheckerTest {
    private val checker = InvariantChecker(minBoxGap = 24, minLineGap = 6, boxClearance = 8, labelClearance = 2)

    // Two rows of two boxes, 100 × 40 each, with a channel of 60 between the rows.
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

    private fun assertLegal(vararg lines: DrawnLine) = assertEquals(emptyList(), checker.check(Drawing(boxes, lines.toList())))

    private fun assertBreaksOnly(rule: Int, vararg lines: DrawnLine) = assertEquals(setOf(rule), rules(lines.toList()))

    // a → d runs along y = 60; b → c comes down at x = 230, crosses it there, and runs back along y = 80.
    private val aToD = line("a→d", 1, 4, 50 to 40, 50 to 60, 250 to 60, 250 to 100)
    private val bToC = line("b→c", 2, 3, 230 to 40, 230 to 80, 30 to 80, 30 to 100)

    @Test
    fun acceptsBoxesApartAndLinesThatCrossProperly() {
        assertLegal()
        assertLegal(aToD)
        assertLegal(aToD, bToC)
        assertLegal(line("a→c", 1, 3, 50 to 40, 50 to 100), line("b→d", 2, 4, 250 to 40, 250 to 100))
    }

    @Test
    fun rule1BoxesOverlapTouchOrStandTooClose() {
        fun with(other: Rect) = rules(emptyList(), listOf(a, DrawnBox(9, other)))
        assertEquals(setOf(1), with(Rect(50, 20, 150, 60)), "overlap")
        assertEquals(setOf(1), with(Rect(100, 0, 200, 40)), "touch")
        assertEquals(setOf(1), with(Rect(123, 0, 223, 40)), "23 apart")
        assertEquals(setOf(1), with(Rect(110, 50, 210, 90)), "diagonal, 10 apart")
        assertEquals(emptySet(), with(Rect(124, 0, 224, 40)), "24 apart")
        assertEquals(emptySet(), with(Rect(0, 64, 100, 104)), "24 below")
    }

    @Test
    fun rule2LinesRunTooCloseSideBySide() {
        // a → b hangs below both boxes; its run is above a → d's, over the same stretch.
        assertBreaksOnly(2, aToD, line("a→b", 1, 2, 70 to 40, 70 to 55, 230 to 55, 230 to 40))
        assertLegal(aToD, line("a→b", 1, 2, 70 to 40, 70 to 54, 230 to 54, 230 to 40))

        val straight = line("a→c", 1, 3, 50 to 40, 50 to 100)
        assertBreaksOnly(2, straight, line("a→c'", 1, 3, 55 to 40, 55 to 100))
        assertLegal(straight, line("a→c'", 1, 3, 56 to 40, 56 to 100))
    }

    @Test
    fun rule2LinesOnOneTrackEndToEnd() {
        val left = line("a→c", 1, 3, 20 to 40, 20 to 60, 80 to 60, 80 to 100)
        assertBreaksOnly(2, left, line("a→d", 1, 4, 85 to 40, 85 to 60, 250 to 60, 250 to 100))
        assertLegal(left, line("a→d", 1, 4, 86 to 40, 86 to 60, 250 to 60, 250 to 100))
    }

    @Test
    fun rule2LinesShareAStretch() {
        // b → d comes down to a → d's track and runs back along it.
        assertTrue(2 in rules(listOf(aToD, line("b→d", 2, 4, 270 to 40, 270 to 60, 220 to 60, 220 to 100))))
        assertTrue(2 in rules(listOf(aToD, line("a→d again", 1, 4, 50 to 40, 50 to 60, 250 to 60, 250 to 100))))
    }

    @Test
    fun rule3LinesMeetAtOrNearABendInsteadOfCrossing() {
        // b → c turns where it reaches a → d's run: a T, not a crossing.
        assertTrue(3 in rules(listOf(aToD, line("b→c", 2, 3, 230 to 40, 230 to 60, 30 to 60, 30 to 100))))
        // It crosses 3 away from a → d's bend at (250, 60).
        assertTrue(3 in rules(listOf(aToD, line("b→c", 2, 3, 247 to 40, 247 to 80, 30 to 80, 30 to 100))))
        // It crosses 3 before its own bend.
        assertTrue(3 in rules(listOf(aToD, line("b→c", 2, 3, 230 to 40, 230 to 63, 30 to 63, 30 to 100))))
        // Not at a right angle.
        val slanted = DrawnLine("a→d", listOf(Segment(50, 40, 250, 100)), listOf(LineEnd(Point(50, 40), 1), LineEnd(Point(250, 100), 4)), null)
        assertTrue(3 in rules(listOf(slanted)))
    }

    @Test
    fun rule4LinePassesThroughOrTooNearABoxThatIsNotItsEnd() {
        val inTheWay = DrawnBox(5, Rect(125, 50, 175, 90))
        assertEquals(setOf(4), rules(listOf(line("a→d", 1, 4, 50 to 40, 50 to 70, 250 to 70, 250 to 100)), boxes + inTheWay), "through it")
        assertEquals(setOf(4), rules(listOf(line("a→d", 1, 4, 50 to 40, 50 to 49, 250 to 49, 250 to 100)), boxes + DrawnBox(5, Rect(125, 56, 175, 96))), "7 above it")
        assertEquals(emptySet(), rules(listOf(line("a→d", 1, 4, 50 to 40, 50 to 48, 250 to 48, 250 to 100)), boxes + DrawnBox(5, Rect(125, 56, 175, 96))), "8 above it")
    }

    @Test
    fun rule4LineEndsOnTheOutlineOfItsBoxesAndTouchesThemNowhereElse() {
        assertBreaksOnly(4, line("a→d", 1, 4, 50 to 42, 50 to 60, 250 to 60, 250 to 100)) // starts below a
        assertBreaksOnly(4, line("a→d", 1, 4, 50 to 20, 50 to 60, 250 to 60, 250 to 100)) // starts inside a
        assertBreaksOnly(4, line("a→d", 1, 4, 50 to 40, 50 to 60, 250 to 60, 250 to 120)) // ends inside d
        assertBreaksOnly(4, line("a→d", 1, 4, 100 to 20, 100 to 60, 250 to 60, 250 to 100)) // runs down a's side
        assertBreaksOnly(4, line("a→b", 1, 2, 50 to 40, 50 to 44, 250 to 44, 250 to 40)) // hugs the bottom of both
        assertBreaksOnly(4, line("a→?", 1, 99, 50 to 40, 50 to 60, 250 to 60, 250 to 100)) // ends at a box that is not there
        assertBreaksOnly(4, DrawnLine("loose", listOf(Segment(50, 40, 50, 60)), listOf(LineEnd(Point(50, 40), 1)), null))
    }

    @Test
    fun rule5LabelOffItsLineOrCoveringSomething() {
        fun labelled(label: Rect) = line("a→d", 1, 4, 50 to 40, 50 to 60, 250 to 60, 250 to 100, label = label)
        assertLegal(labelled(Rect(120, 53, 180, 67)))
        assertLegal(labelled(Rect(120, 53, 180, 67)), bToC)
        assertBreaksOnly(5, labelled(Rect(120, 70, 180, 84))) // below its line
        assertBreaksOnly(5, labelled(Rect(40, 34, 110, 48))) // on box a
        assertBreaksOnly(5, labelled(Rect(200, 53, 245, 67)), bToC) // b → c comes down through it
        assertBreaksOnly(5, labelled(Rect(120, 53, 180, 67)), line("b→c", 2, 3, 230 to 40, 230 to 80, 30 to 80, 30 to 100, label = Rect(130, 66, 190, 94)))
        assertLegal(labelled(Rect(120, 53, 180, 67)), line("b→c", 2, 3, 230 to 40, 230 to 80, 30 to 80, 30 to 100, label = Rect(130, 73, 190, 87)))
    }

    @Test
    fun rule0LineInPieces() {
        val broken = DrawnLine(
            "a→d", listOf(Segment(50, 40, 50, 60), Segment(60, 70, 250, 70), Segment(250, 70, 250, 100)),
            listOf(LineEnd(Point(50, 40), 1), LineEnd(Point(250, 100), 4)), null,
        )
        assertEquals(setOf(0), rules(listOf(broken)))
    }

    /**
     * Whatever the engine draws is legal; damage any one thing in it and it no longer is. This says the checker
     * would notice the engine going wrong in drawings far bigger than the hand-made ones above.
     */
    @Test
    fun noticesDamageToARealLayout() {
        val random = Random(7)
        for (seed in 1..40) {
            val drawing = LayoutEngine.layout(RandomGraphs.forest(seed, 40, RandomGraphs.Shape.entries[seed % 4], links = 30)).toDrawing()
            assertEquals(emptyList(), InvariantChecker().check(drawing))
            if (drawing.lines.isEmpty()) continue
            val line = drawing.lines[random.nextInt(drawing.lines.size)]
            fun illegal(boxes: List<DrawnBox>, lines: List<DrawnLine>, what: String) =
                assertTrue(InvariantChecker().check(Drawing(boxes, lines)).isNotEmpty(), "seed $seed: $what went unnoticed")

            // A box moves away from under the line that ends at it.
            val end = line.ends[random.nextInt(line.ends.size)]
            illegal(drawing.boxes.map { if (it.id == end.boxId) DrawnBox(it.id, Rect(it.rect.left + 40, it.rect.top + 30, it.rect.right + 40, it.rect.bottom + 30)) else it }, drawing.lines, "a moved box")

            // The same line drawn twice.
            illegal(drawing.boxes, drawing.lines + DrawnLine("copy of ${line.name}", line.segments, line.ends, null), "a line on top of another")

            // A box dropped into the middle of the line's longest segment.
            val longest = line.segments.maxBy { it.length }
            val cx = (longest.x1 + longest.x2) / 2
            val cy = (longest.y1 + longest.y2) / 2
            illegal(drawing.boxes + DrawnBox(-1, Rect(cx - 3, cy - 3, cx + 3, cy + 3)), drawing.lines, "a box on a line")
        }
    }
}
