package kotlinx.coroutree.runtime

import kotlinx.coroutree.runtime.SourceMaps.Inlined
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Source maps beyond what kotlinc writes today (those are SourceMapsTest's): the corners of JSR-45 the parser
 * takes on, and what happens when the class file and the map do not tell the same story. Every class has a name of
 * its own: maps are kept per class name for the life of the JVM.
 */
internal class SourceMapsCornersTest {
    @Test
    fun aRangeMapsEveryOutputLineOfItsStepToTheSameInputLine() {
        // InputStartLine#File,RepeatCount:OutputStartLine,OutputLineIncrement: three input lines, two output lines each.
        SourceMaps.register(
            "com.acme.Stepped",
            HEAD + "*S Kotlin\n" + files("Stepped") + "*L\n1#1,50:1\n10#2,3:100,2\n" +
                "*S KotlinDebug\n" + files("Stepped") + "*L\n7#1:100,6\n*E\n",
            null
        )
        val site = "com.acme.Stepped.run(Main.kt:7)"
        assertEquals(listOf("inline com.acme.util.UtilKt.(Util.kt:10)", site), frames("Stepped", 100))
        assertEquals(listOf("inline com.acme.util.UtilKt.(Util.kt:10)", site), frames("Stepped", 101))
        assertEquals(listOf("inline com.acme.util.UtilKt.(Util.kt:11)", site), frames("Stepped", 102))
        assertEquals(listOf("inline com.acme.util.UtilKt.(Util.kt:12)", site), frames("Stepped", 105))
        assertEquals(listOf("com.acme.Stepped.run(Main.kt:106)"), frames("Stepped", 106), "one past the range")
        assertEquals(listOf("com.acme.Stepped.run(Main.kt:99)"), frames("Stepped", 99), "one before it")
    }

    @Test
    fun theMapIsReadWhateverItsLineEndsItsOrderOfStrataAndItsOtherSections() {
        val body = "*S Kotlin\n" + files("Shuffled") + "*L\n1#1,35:1\n23#2,3:36\n"
        val callSite = "*S KotlinDebug\n" + files("Shuffled") + "*L\n14#1:36,3\n"
        // A vendor section, strata of other languages, the call sites before the bodies, no *E at the end, CRLF.
        val map = HEAD + "*V\nsomething of a vendor's\n*S Other\n*F\n1 other.x\n*L\n1#1,500:1\n" + callSite + body
        SourceMaps.register("com.acme.Shuffled", map.replace("\n", "\r\n"), null)
        assertEquals(listOf("inline com.acme.util.UtilKt.(Util.kt:25)", "com.acme.Shuffled.run(Main.kt:14)"), frames("Shuffled", 38))
        assertEquals(listOf("com.acme.Shuffled.run(Main.kt:20)"), frames("Shuffled", 20))
    }

    @Test
    fun aClassDefinedAgainHasTheMapOfWhicheverWasLoadedLast() {
        val name = "com.acme.Reloaded"
        SourceMaps.register(name, HEAD + "*S Kotlin\n" + files("Reloaded") + "*L\n23#2,3:36\n*S KotlinDebug\n" + files("Reloaded") + "*L\n14#1:36,3\n*E\n", null)
        assertEquals(2, frames("Reloaded", 36).size)
        SourceMaps.register(name, HEAD + "*S Kotlin\n" + files("Reloaded") + "*L\n40#2,3:60\n*S KotlinDebug\n" + files("Reloaded") + "*L\n15#1:60,3\n*E\n", null)
        assertEquals(listOf("com.acme.Reloaded.run(Main.kt:36)"), frames("Reloaded", 36))
        assertEquals(listOf("inline com.acme.util.UtilKt.(Util.kt:41)", "com.acme.Reloaded.run(Main.kt:15)"), frames("Reloaded", 61))
    }

    @Test
    fun aClassDefinedAgainWithoutInlinedCodeHasNoMapAnyMore() {
        // "A class that two loaders define differently has the map of whichever was loaded last": the one loaded last
        // has only its own lines, so line 36 of it is its line 36, not what the first definition had inlined there.
        val name = "com.acme.Replaced"
        SourceMaps.register(name, HEAD + "*S Kotlin\n" + files("Replaced") + "*L\n1#1,35:1\n23#2,3:36\n*S KotlinDebug\n" + files("Replaced") + "*L\n14#1:36,3\n*E\n", null)
        assertEquals(2, frames("Replaced", 36).size)
        SourceMaps.register(name, HEAD + "*S Kotlin\n*F\n+ 1 Main.kt\ncom/acme/Replaced\n*L\n1#1,80:1\n*E\n", null)
        assertEquals(listOf("com.acme.Replaced.run(Main.kt:36)"), frames("Replaced", 36))
    }

    @Test
    fun inlineCallsTheClassFileNamesButTheMapDoesNotKnowEndTheChainNotTheStack() {
        // Line 53 is in withLock, inlined through retry and outer; but the line retry is said to be called from is no line of any copy.
        SourceMaps.register(
            "com.acme.Chained",
            HEAD + "*S Kotlin\n" + files("Chained") + "*L\n1#1,35:1\n23#2,3:36\n117#2,10:52\n" +
                "*S KotlinDebug\n" + files("Chained") + "*L\n14#1:36,3\n18#1:52,10\n*E\n",
            mapOf(
                53 to Inlined(arrayOf("withLock", "retry", "outer"), intArrayOf(37, 999, 18)),
                // A line the map does not have as inlined code: whatever the variable tables said about it is nothing.
                20 to Inlined(arrayOf("ghost"), intArrayOf(19))
            )
        )
        assertEquals(
            listOf("inline com.acme.util.UtilKt.withLock(Util.kt:118)", "inline com.acme.util.UtilKt.retry(Util.kt:24)", "com.acme.Chained.run(Main.kt:18)"),
            frames("Chained", 53)
        )
        assertEquals(listOf("com.acme.Chained.run(Main.kt:20)"), frames("Chained", 20))
    }

    @Test
    fun linesThatAreNoLinesAreLeftAlone() {
        SourceMaps.register("com.acme.Unknown", HEAD + "*S Kotlin\n" + files("Unknown") + "*L\n23#2,3:36\n*S KotlinDebug\n" + files("Unknown") + "*L\n14#1:36,3\n*E\n", null)
        // Native (-2), no line information (-1): unknown, which the format spells 0.
        assertEquals(listOf("com.acme.Unknown.run(Main.kt:0)"), frames("Unknown", -2))
        assertEquals(listOf("com.acme.Unknown.run(Main.kt:0)"), frames("Unknown", -1))
        assertEquals(listOf("com.acme.Unknown.run(Main.kt:0)"), frames("Unknown", 0))
        assertEquals(listOf("com.acme.Unknown.run(Main.kt:2147483647)"), frames("Unknown", Int.MAX_VALUE))
    }

    companion object {
        private const val HEAD = "SMAP\nMain.kt\nKotlin\n"

        private fun files(className: String) = "*F\n+ 1 Main.kt\ncom/acme/$className\n+ 2 Util.kt\ncom/acme/util/UtilKt\n"

        private fun frames(className: String, line: Int): List<String> = RuntimeProbe.logicalFrames("com.acme.$className", "run", "Main.kt", line)
    }
}
