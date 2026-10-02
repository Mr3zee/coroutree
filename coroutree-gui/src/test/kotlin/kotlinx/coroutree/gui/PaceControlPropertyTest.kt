package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.view.EventLogItems
import kotlinx.coroutree.gui.view.Formatting
import kotlinx.coroutree.gui.view.SpeedScale
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.PaceDef
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two pieces of arithmetic behind execution control on screen, held to what they promise for any input rather than
 * for the handful of cases in PaceControlTest: where a change of the gate stands in the event log, and what the speed
 * slider makes of an interval. Seeded: a failure names the seed.
 */
class PaceControlPropertyTest {
    /**
     * EventLogItems: "the events, and between them the changes of the gate's settings, each behind the event it was
     * made after"; "the stream is where the order of changes comes from".
     */
    @Test
    fun theLogIsTheEventsInOrderWithEveryChangeBehindItsEventAndInStreamOrder() {
        for (seed in 1..300) {
            val random = Random(seed)
            var seq = 0L
            val events = List(random.nextInt(0, 40)) {
                seq += if (random.nextInt(5) == 0) random.nextLong(2, 6) else 1 // sequence numbers may have gaps
                Event(seq = seq, nodeId = 1, kind = EventKind.RESUMED)
            }
            val changes = List(random.nextInt(0, 12)) {
                // Mostly ascending, as an agent writes them; sometimes not, and sometimes ahead of the events.
                PaceDef(afterSeq = random.nextLong(0, seq + 4), steps = it)
            }.let { if (random.nextBoolean()) it.sortedBy { change -> change.afterSeq } else it }
            val log = EventLogItems(events, changes)
            val what = "seed $seed: events ${events.map { it.seq }}, changes after ${changes.map { it.afterSeq }}"

            assertEquals(events.size + changes.size, log.size, what)
            val items = (0 until log.size).map(log::get)
            assertEquals(events, items.filterIsInstance<EventLogItems.Item.Of>().map { it.event }, "$what: every event once, in order")
            val paces = items.filterIsInstance<EventLogItems.Item.Pace>()
            assertEquals(changes, paces.map { it.change }, "$what: every change once, in the order of the stream")
            assertEquals(changes.indices.toList(), paces.map { it.index }, what)

            items.forEachIndexed { position, item ->
                if (item !is EventLogItems.Item.Pace) return@forEachIndexed
                val before = items.take(position).filterIsInstance<EventLogItems.Item.Of>().map { it.event.seq }
                val madeAfter = events.map { it.seq }.filter { it <= item.change.afterSeq }
                assertTrue(before.containsAll(madeAfter), "$what: change ${item.index} stands before an event it was made after")
                // And no further back than it has to: right behind its event, or behind a change the stream puts first.
                val previous = items.getOrNull(position - 1)
                if (previous is EventLogItems.Item.Of) {
                    val farthest = changes.take(item.index + 1).maxOf { it.afterSeq }
                    assertTrue(previous.event.seq <= farthest, "$what: change ${item.index} stands behind event ${previous.event.seq}, which came after it")
                }
            }

            events.indices.forEach { assertEquals(EventLogItems.Item.Of(events[it]), log[log.positionOfEvent(it)], "$what: event $it is found") }
            assertEquals(log.size, (0 until log.size).map(log::key).toSet().size, "$what: keys are unique")
        }
    }

    /** `key`: "A key that stays the same while the log grows." */
    @Test
    fun keysOfTheLogStayTheSameWhileItGrows() {
        val random = Random(9)
        val events = (1L..60L).map { Event(seq = it, nodeId = 1, kind = EventKind.RESUMED) }
        val changes = List(10) { PaceDef(afterSeq = random.nextLong(0, 60)) }.sortedBy { it.afterSeq }
        val whole = EventLogItems(events, changes)
        val keysOfWhole = (0 until whole.size).map(whole::key)
        // The trace as it was when only the first events and the changes made by then had arrived.
        for (arrived in listOf(10, 25, 59)) {
            val partial = EventLogItems(events.take(arrived), changes.filter { it.afterSeq <= arrived })
            val keys = (0 until partial.size).map(partial::key)
            assertEquals(keys, keysOfWhole.filter { it in keys.toSet() }, "what was in the log after $arrived events is still there, under the same keys, in the same order")
        }
    }

    // ------------------------------------------------------------------ the speed scale

    /** `tidy`: "Two significant digits" — so there and back is the same pace to within the rounding of the second digit. */
    @Test
    fun everyPaceHasAPlaceOnTheSliderThatMeansThatPaceToTwoDigits() {
        val random = Random(3)
        repeat(2000) {
            // Log-uniform over the slider's range, 1 ms … 10 s.
            val interval = Math.pow(10.0, random.nextDouble(6.0, 10.0)).toLong()
            val position = SpeedScale.positionOf(interval)
            assertTrue(position in 0f..SpeedScale.UNLIMITED_FROM, "$interval → $position")
            val back = SpeedScale.intervalAt(position)
            // Half a unit of the second digit is at most 5 % (of 10); the float of the position adds a little.
            assertTrue(abs(back - interval) <= interval * 0.06, "$interval → $position → $back")
        }
    }

    /** `positionOf`: "Short of where 'no limit' begins, however fast: a pace is a pace." */
    @Test
    fun aSlowerPaceIsNeverFurtherRightAndNoPaceReachesNoLimit() {
        var previous = 2f
        var interval = 1L
        while (interval < 100_000_000_000) {
            val position = SpeedScale.positionOf(interval)
            assertTrue(position <= previous, "$interval is at $position, a faster one at $previous")
            assertTrue(position < SpeedScale.UNLIMITED_FROM, "$interval ns is a pace, however fast")
            assertTrue(SpeedScale.intervalAt(position) > 0, "$interval → no limit")
            previous = position
            interval = interval * 3 / 2 + 1
        }
        assertEquals(0f, previous, "anything slower than the slider goes sits at its slow end")
    }

    // ------------------------------------------------------------------ what the log's columns show

    /** `relativeTime`: "in milliseconds with microsecond digits: `1 234.567`. Fixed shape, so it aligns in a column." */
    @Test
    fun timesAndEnumsAreWrittenTheSameWayEverywhere() {
        val times = mapOf(
            0L to "0.000",
            999L to "0.000",
            1_000L to "0.001",
            1_234_567L to "1.234",
            999_999_999L to "999.999",
            1_234_567_890L to "1 234.567",
            3_600_000_000_000L to "3 600 000.000",
        )
        for ((nanos, text) in times) assertEquals(text, Formatting.relativeTime(nanos), "$nanos ns")

        assertEquals("", Formatting.wallClock(0), "a trace that does not say when it started")
        assertTrue(Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d""").matches(Formatting.wallClock(1_789_668_900_000)))

        assertEquals("cancellation requested", Formatting.enumLabel(EventKind.CANCELLATION_REQUESTED))
        assertEquals("completed", Formatting.enumLabel(NodeState.COMPLETED))
        for (kind in EventKind.entries) assertTrue(Formatting.enumLabel(kind).none { it == '_' || it.isUpperCase() }, "$kind")
    }
}
