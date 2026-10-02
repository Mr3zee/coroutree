package kotlinx.coroutree.gui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliOptionsTest {
    @Test
    fun noArgumentsMeanStartScreen() {
        assertEquals(CliOptions(), CliOptions.parse(emptyList()))
    }

    @Test
    fun parsesEveryOption() {
        assertEquals(
            CliOptions(
                trace = File("a.ctrace"),
                session = File("s.json"),
                dir = File("build/coroutree"),
                openLatest = true,
                ideCommand = "code -g {path}:{line}",
                demo = CliOptions.DemoMode.LIVE,
            ),
            CliOptions.parse(
                listOf("--trace", "a.ctrace", "--session", "s.json", "--dir", "build/coroutree", "--open-latest", "--ide", "code -g {path}:{line}", "--demo=live"),
            ),
        )
        assertEquals(CliOptions.DemoMode.INSTANT, CliOptions.parse(listOf("--demo")).demo)
    }

    @Test
    fun barePathIsATrace() {
        assertEquals(File("/tmp/x.ctrace"), CliOptions.parse(listOf("/tmp/x.ctrace")).trace)
    }

    @Test
    fun rejectsWhatItDoesNotUnderstand() {
        assertFailsWith<CliOptions.UsageException> { CliOptions.parse(listOf("--nope")) }
        assertFailsWith<CliOptions.UsageException> { CliOptions.parse(listOf("--trace")) }
        assertFailsWith<CliOptions.UsageException> { CliOptions.parse(listOf("--open-latest")) }
        assertFailsWith<CliOptions.UsageException> { CliOptions.parse(listOf("a.ctrace", "b.ctrace")) }
    }

    @Test
    fun anOptionGivenAgainReplacesWhatWasGivenBefore() {
        assertEquals(File("b.ctrace"), CliOptions.parse(listOf("--trace", "a.ctrace", "--trace", "b.ctrace")).trace)
        assertEquals(File("b.ctrace"), CliOptions.parse(listOf("a.ctrace", "--trace", "b.ctrace")).trace, "a bare path is a trace like any other")
        assertEquals(CliOptions.DemoMode.INSTANT, CliOptions.parse(listOf("--demo=live", "--demo")).demo)
        assertEquals(CliOptions(dir = File("d"), openLatest = true), CliOptions.parse(listOf("--open-latest", "--dir", "d")), "in any order")
    }

    @Test
    fun aValueIsTakenAsItIsAndAnOptionThatOnlyLooksKnownIsNot() {
        assertEquals("", CliOptions.parse(listOf("--ide", "")).ideCommand)
        assertEquals(File("my traces/run 1.ctrace"), CliOptions.parse(listOf("my traces/run 1.ctrace")).trace, "arguments are not split again")
        for (bad in listOf(listOf("--demo=fast"), listOf("--demo", "live", "x"), listOf("--ide"), listOf("--dir"), listOf("--session"), listOf("--Trace", "a"), listOf("-trace", "a", "b"), listOf("--trace", "a", "b"))) {
            assertFailsWith<CliOptions.UsageException>("$bad") { CliOptions.parse(bad) }
        }
    }

    /** What is printed when the arguments are wrong has to be what the parser takes. */
    @Test
    fun theUsageTextNamesExactlyTheOptionsThereAre() {
        val named = Regex("""--[a-z][a-z-]*(=[a-z]+)?""").findAll(CliOptions.USAGE.replace("[=live]", "")).map { it.value }.toSet() +
            Regex("""--demo\[(=[a-z]+)]""").findAll(CliOptions.USAGE).map { "--demo" + it.groupValues[1] }
        assertEquals(setOf("--trace", "--session", "--dir", "--open-latest", "--ide", "--demo", "--demo=live"), named)
        for (option in named) {
            val arguments = when (option) {
                "--trace", "--session", "--dir", "--ide" -> listOf(option, "value")
                "--open-latest" -> listOf("--dir", "d", option)
                else -> listOf(option)
            }
            assertTrue(CliOptions.parse(arguments) != CliOptions(), "$option is understood and changes something")
        }
    }
}
