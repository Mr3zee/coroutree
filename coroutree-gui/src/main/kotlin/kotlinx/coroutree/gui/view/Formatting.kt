package kotlinx.coroutree.gui.view

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatting {
    /** Time since the trace started, in milliseconds with microsecond digits: `1 234.567`. Fixed shape, so it aligns in a column. */
    fun relativeTime(nanos: Long): String {
        val micros = nanos / 1_000
        val millis = micros / 1_000
        val grouped = String.format(Locale.ROOT, "%,d", millis).replace(',', ' ')
        return String.format(Locale.ROOT, "%s.%03d", grouped, micros % 1_000)
    }

    private val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun wallClock(epochMillis: Long): String = if (epochMillis <= 0) "" else clock.format(Instant.ofEpochMilli(epochMillis))

    fun enumLabel(value: Enum<*>): String = value.name.lowercase().replace('_', ' ')
}
