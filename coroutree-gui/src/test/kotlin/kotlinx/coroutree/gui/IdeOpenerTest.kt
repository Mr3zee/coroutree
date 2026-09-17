package kotlinx.coroutree.gui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutree.gui.ide.IdeOpener
import kotlinx.coroutree.model.tree.SourceLocation
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeOpenerTest {
    @Test
    fun buildsTheBuiltInServerUrl() {
        assertEquals(
            "http://localhost:63342/api/file?file=%2FUsers%2Fme%2Fmy%20project%2Fsrc%2FMain.kt&line=12",
            IdeOpener.builtInServerUri("/Users/me/my project/src/Main.kt", 12).toString(),
        )
        assertEquals("http://localhost:63342/api/file?file=%2Fa.kt", IdeOpener.builtInServerUri("/a.kt", 0).toString())
    }

    @Test
    fun substitutesPlaceholdersAfterSplitting() {
        assertEquals(
            listOf("idea", "--line", "7", "/my project/Main.kt"),
            IdeOpener.buildCommand(IdeOpener.DEFAULT_COMMAND, "/my project/Main.kt", 7),
        )
        assertEquals(
            listOf("/Applications/VS Code.app/code", "-g", "/p/Main.kt:3"),
            IdeOpener.buildCommand(""""/Applications/VS Code.app/code"   -g '{path}:{line}'""", "/p/Main.kt", 3),
        )
        assertEquals(listOf("open", "/p/Main.kt", "1"), IdeOpener.buildCommand("open {path} {line}", "/p/Main.kt", 0), "unknown line opens the top")
        assertFailsWith<IllegalArgumentException> { IdeOpener.buildCommand("idea \"{path}", "/p", 1) }
        assertFailsWith<IllegalArgumentException> { IdeOpener.buildCommand("   ", "/p", 1) }
    }

    @Test
    fun runsTheConfiguredCommandAndReportsFailuresInsteadOfThrowing() = runBlocking {
        val dir = Files.createTempDirectory("coroutree-ide").toFile()
        val source = File(dir, "Main.kt").apply { writeText("fun main() {}") }
        val marker = File(dir, "opened.txt")
        val location = SourceLocation(":app", "Main.kt", source.path, 3)

        val opener = IdeOpener("/bin/sh -c 'echo \"$1:$2\" > \"$0\"' ${marker.path} {path} {line}")
        assertIs<IdeOpener.Outcome.Opened>(opener.open(location))
        assertEquals("${source.path}:3", marker.readText().trim())

        assertIs<IdeOpener.Outcome.Failed>(IdeOpener("/bin/sh -c 'exit 3'").open(location))
        assertIs<IdeOpener.Outcome.Failed>(IdeOpener("/no/such/launcher {path}").open(location))
        val gone = IdeOpener("true").open(location.copy(absolutePath = "/no/such/file.kt"))
        assertTrue(gone is IdeOpener.Outcome.Failed && "No such file" in gone.message)
    }
}
