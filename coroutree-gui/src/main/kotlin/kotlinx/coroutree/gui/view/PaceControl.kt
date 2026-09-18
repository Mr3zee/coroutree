package kotlinx.coroutree.gui.view

import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.PaceDef
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * A command of execution control (DESIGN §3.1), as the agent reads it from the live socket: one text line. `node` 0 is
 * the whole program, anything else a node whose structural subtree the command is for. There is no reply to a
 * command: what it did comes back in the stream, and that — never what was last sent — is what the GUI shows.
 */
sealed interface PaceCommand {
    val node: Long
    val line: String

    /** At most one event per [intervalNanos] in every sequence; 0 = no limit. */
    data class SetPace(val intervalNanos: Long, override val node: Long = 0) : PaceCommand {
        override val line get() = "pace ${intervalNanos.coerceAtLeast(0)}" + suffix(node)
    }

    data class Pause(override val node: Long = 0) : PaceCommand {
        override val line get() = "pause" + suffix(node)
    }

    data class Resume(override val node: Long = 0) : PaceCommand {
        override val line get() = "resume" + suffix(node)
    }

    /** Lets [count] steps through a paused gate; asked of a running program it stops it after that many. */
    data class Step(val count: Int = 1, override val node: Long = 0) : PaceCommand {
        override val line get() = "step ${count.coerceAtLeast(1)}" + suffix(node)
    }

    /** Drops the setting of a subtree: it goes by what governs its parent again. */
    data class Inherit(override val node: Long) : PaceCommand {
        override val line get() = "inherit $node"
    }

    private companion object {
        fun suffix(node: Long) = if (node == 0L) "" else " $node"
    }
}

/**
 * The speed slider: a logarithmic scale from "one event in ten seconds" at 0 to a thousand events a second just below
 * 1, and at 1 itself no limit at all. Logarithmic because both ends matter: one step in a few seconds to watch a
 * single hand-over, hundreds a second to watch a program breathe.
 */
object SpeedScale {
    const val SLOWEST_INTERVAL_NANOS = 10_000_000_000L
    const val FASTEST_INTERVAL_NANOS = 1_000_000L

    /** Positions from here on mean "unlimited": a little room at the end of the track, so that it can be hit. */
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
        // Short of where "no limit" begins, however fast: a pace is a pace.
        return (t * UNLIMITED_FROM).toFloat().coerceAtMost(UNLIMITED_FROM - 0.0005f)
    }

    /** Two significant digits: a slider is not the place to ask for 3.1622776 events a second. */
    private fun tidy(intervalNanos: Double): Long {
        var magnitude = 1.0
        while (intervalNanos / magnitude >= 100) magnitude *= 10
        return ((intervalNanos / magnitude).roundToLong() * magnitude).toLong().coerceIn(FASTEST_INTERVAL_NANOS, SLOWEST_INTERVAL_NANOS)
    }
}

/**
 * What the event log shows: the events, and between them the changes of the gate's settings, each behind the event
 * it was made after ([PaceDef.afterSeq]). Nothing is copied: a trace has few changes and may have millions of events,
 * so an item is found by looking at the changes only.
 */
class EventLogItems(val events: List<Event>, paceChanges: List<PaceDef>) {
    sealed interface Item {
        data class Of(val event: Event) : Item
        data class Pace(val change: PaceDef, val index: Int) : Item
    }

    private val changes: List<PaceDef> = paceChanges
    /** Position of change i in the log: the events before it, and the changes before it. */
    private val positions: IntArray = IntArray(paceChanges.size).also { positions ->
        var previous = -1
        for ((i, change) in paceChanges.withIndex()) {
            // The stream is where the order of changes comes from; afterSeq only says how far into the events one is.
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
        // The changes in front of it are those whose position, counted in events alone, is not behind it.
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
