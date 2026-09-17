package kotlinx.coroutree.gui.view.graph

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Integer geometry of the graph. One unit is one dp at zoom 1; integers make "do these two lines touch" an exact
 * question, for the engine and for the checker alike.
 */

data class Point(val x: Int, val y: Int)

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    fun intersects(other: Rect): Boolean = left <= other.right && other.left <= right && top <= other.bottom && other.top <= bottom

    fun inflated(by: Int): Rect = Rect(left - by, top - by, right + by, bottom + by)

    fun union(other: Rect): Rect = Rect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    /** Whether [x], [y] lies on the outline. */
    fun onBorder(x: Int, y: Int): Boolean = contains(x, y) && (x == left || x == right || y == top || y == bottom)

    /**
     * Distance to [other] in the maximum norm: the side of the largest square that fits between the two.
     * 0 when they touch or overlap.
     */
    fun distanceTo(other: Rect): Int = max(axisGap(left, right, other.left, other.right), axisGap(top, bottom, other.top, other.bottom))

    companion object {
        val EMPTY = Rect(0, 0, 0, 0)
    }
}

/** An axis-aligned piece of a line, stored with `x1 <= x2` and `y1 <= y2`. */
class Segment(x1: Int, y1: Int, x2: Int, y2: Int) {
    val x1: Int = min(x1, x2)
    val y1: Int = min(y1, y2)
    val x2: Int = max(x1, x2)
    val y2: Int = max(y1, y2)

    val horizontal: Boolean get() = y1 == y2
    val vertical: Boolean get() = x1 == x2
    val length: Int get() = abs(x2 - x1) + abs(y2 - y1)
    val bounds: Rect get() = Rect(x1, y1, x2, y2)

    fun hasEndpoint(point: Point): Boolean = (point.x == x1 && point.y == y1) || (point.x == x2 && point.y == y2)

    override fun equals(other: Any?): Boolean = other is Segment && x1 == other.x1 && y1 == other.y1 && x2 == other.x2 && y2 == other.y2

    override fun hashCode(): Int = ((x1 * 31 + y1) * 31 + x2) * 31 + y2

    override fun toString(): String = "($x1,$y1)-($x2,$y2)"
}

/** Free space between the intervals `[a1, a2]` and `[b1, b2]`, 0 when they touch or overlap. */
internal fun axisGap(a1: Int, a2: Int, b1: Int, b2: Int): Int = max(0, max(b1 - a2, a1 - b2))

/** The smallest value `>= value` that is congruent to [residue] modulo [modulus]. */
internal fun ceilToClass(value: Int, residue: Int, modulus: Int): Int = value + Math.floorMod(residue - value, modulus)

/** The largest value `<= value` that is congruent to [residue] modulo [modulus]. */
internal fun floorToClass(value: Int, residue: Int, modulus: Int): Int = value - Math.floorMod(value - residue, modulus)
