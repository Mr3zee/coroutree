package kotlinx.coroutree.gradle

import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandLineTest {
    @Test
    fun booleansAreTrueBareOrFalse() {
        assertTrue(CommandLine.parseBoolean("coroutree.live", ""))
        assertTrue(CommandLine.parseBoolean("coroutree.live", "true"))
        assertTrue(CommandLine.parseBoolean("coroutree.live", " true "))
        assertFalse(CommandLine.parseBoolean("coroutree.live", "false"))
        for (bad in listOf("yes", "no", "1", "0", "TRUE", "False", "on", "off", "null")) {
            val failure = assertFailsWith<GradleException>(bad) { CommandLine.parseBoolean("coroutree.pace.startPaused", bad) }
            assertTrue("-Pcoroutree.pace.startPaused=$bad" in failure.message!!, failure.message)
        }
    }

    @Test
    fun eventsPerSecondIsAPositiveNumberOrUnlimited() {
        assertEquals("unlimited", CommandLine.parseEventsPerSecond("x", "unlimited"))
        assertEquals("unlimited", CommandLine.parseEventsPerSecond("x", "Unlimited"))
        for (good in listOf("1", "0.2", "2.5", "1e3", "1.0E-7", "1000000")) assertEquals(good, CommandLine.parseEventsPerSecond("x", good))
        for (bad in listOf("", "0", "0.0", "-1", "fast", "NaN", "Infinity", "-Infinity", "1/2", "2 per second", "0x10")) {
            val failure = assertFailsWith<GradleException>(bad) { CommandLine.parseEventsPerSecond("-Pcoroutree.pace.eventsPerSecond", bad) }
            assertTrue("-Pcoroutree.pace.eventsPerSecond=$bad" in failure.message!!, failure.message)
        }
    }
}
