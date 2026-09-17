package kotlinx.coroutree.gui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
