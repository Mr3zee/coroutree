package kotlinx.coroutree.gui.view

import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.PaceDef
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * An execution-control command (DESIGN §3.1), one text line on the live socket.
 * Node 0 selects the whole program. A nonzero id selects that node's structural subtree.
 * A command has no reply. Its effect comes back in the trace, and the GUI shows that, never what it sent.
 */
sealed interface PaceCommand {
    val node: Long
    val line: String

    /** Minimum interval between steps of each sequence, in nanoseconds. 0 means no limit. */
    data class SetPace(val intervalNanos: Long, override val node: Long = 0) : PaceCommand {
        override val line get() = "pace ${intervalNanos.coerceAtLeast(0)}" + suffix(node)
    }

    data class Pause(override val node: Long = 0) : PaceCommand {
        override val line get() = "pause" + suffix(node)
    }

    data class Resume(override val node: Long = 0) : PaceCommand {
        override val line get() = "resume" + suffix(node)
    }

    /** Lets [count] steps through. If the program is running, pauses it after those steps. */
    data class Step(val count: Int = 1, override val node: Long = 0) : PaceCommand {
        override val line get() = "step ${count.coerceAtLeast(1)}" + suffix(node)
    }

    /** Removes the subtree's setting so it inherits its parent's setting. */
    data class Inherit(override val node: Long) : PaceCommand {
        override val line get() = "inherit $node"
    }

    private companion object {
        fun suffix(node: Long) = if (node == 0L) "" else " $node"
    }
}

object SpeedScale {
    const val SLOWEST_INTERVAL_NANOS = 10_000_000_000L
    const val FASTEST_INTERVAL_NANOS = 1_000_000L

    /** Positions from here on mean unlimited. The room makes the setting easy to hit. */
    const val UNLIMITED_FROM = 0.97f

    fun intervalAt(position: Float): Long {
        val p = position.coerceIn(0f, 1f)
        if (p >= UNLIMITED_FROM) return 0
        val t = p / UNLIMITED_FROM
        val interval = exp(ln(SLOWEST_INTERVAL_NANOS.toDouble()) + t * (ln(FASTEST_INTERVAL_NANOS.toDouble()) - ln(SLOWEST_INTERVAL_NANOS.toDouble())))
        return tidy(interval)
    }

    fun positionOf(intervalNanos: Long): Float {
        if (intervalNanos <= 0) return 1f
        val clamped = intervalNanos.coerceIn(FASTEST_INTERVAL_NANOS, SLOWEST_INTERVAL_NANOS).toDouble()
        val t = (ln(SLOWEST_INTERVAL_NANOS.toDouble()) - ln(clamped)) / (ln(SLOWEST_INTERVAL_NANOS.toDouble()) - ln(FASTEST_INTERVAL_NANOS.toDouble()))
        // A finite interval stays below the unlimited region, whatever its length.
        return (t * UNLIMITED_FROM).toFloat().coerceAtMost(UNLIMITED_FROM - 0.0005f)
    }

    private fun tidy(intervalNanos: Double): Long {
        var magnitude = 1.0
        while (intervalNanos / magnitude >= 100) magnitude *= 10
        return ((intervalNanos / magnitude).roundToLong() * magnitude).toLong().coerceIn(FASTEST_INTERVAL_NANOS, SLOWEST_INTERVAL_NANOS)
    }
}

/**
 * The items of the event log: the events, with each execution-control change after the event [PaceDef.afterSeq].
 * A trace can have millions of events and few changes, so nothing is copied: an index maps through the changes only.
 */
class EventLogItems(val events: List<Event>, paceChanges: List<PaceDef>) {
    sealed interface Item {
        data class Of(val event: Event) : Item
        data class Pace(val change: PaceDef, val index: Int) : Item
    }

    private val changes: List<PaceDef> = paceChanges
    private val positions: IntArray = IntArray(paceChanges.size).also { positions ->
        var previous = -1
        for ((i, change) in paceChanges.withIndex()) {
            // Changes keep their stream order even where afterSeq doesn't ascend. afterSeq only places a change among the events.
            val position = maxOf(eventsUpTo(change.afterSeq) + i, previous + 1)
            positions[i] = position
            previous = position
        }
    }

    val size: Int get() = events.size + changes.size

    operator fun get(index: Int): Item {
        val found = positions.binarySearch(index)
        if (found >= 0) return Item.Pace(changes[found], found)
        val changesBefore = -(found + 1)
        return Item.Of(events[index - changesBefore])
    }

    /** Position in the log of the event at [eventIndex] of [events]. */
    fun positionOfEvent(eventIndex: Int): Int {
        if (eventIndex < 0) return -1
        var low = 0
        var high = positions.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (positions[middle] - middle <= eventIndex) low = middle + 1 else high = middle
        }
        return eventIndex + low
    }

    /** A key that stays the same while the log grows. */
    fun key(index: Int): Any = when (val item = get(index)) {
        is Item.Of -> item.event.seq
        is Item.Pace -> "pace-${item.index}"
    }

    private fun eventsUpTo(seq: Long): Int {
        val found = events.binarySearch { it.seq.compareTo(seq) }
        return if (found >= 0) found + 1 else -(found + 1)
    }
}
