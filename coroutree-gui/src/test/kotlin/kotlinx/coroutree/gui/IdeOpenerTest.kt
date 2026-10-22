package kotlinx.coroutree.gui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutree.gui.ide.IdeOpener
import kotlinx.coroutree.gui.ide.IdeOpener.Outcome
import kotlinx.coroutree.gui.ide.IdeOpener.Run
import kotlinx.coroutree.gui.ide.IdeOpener.Target
import kotlinx.coroutree.gui.ide.RunningIde
import kotlinx.coroutree.model.tree.SourceLocation
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdeOpenerTest {
    private val temp: File = Files.createTempDirectory("coroutree-ide").toFile()
    private val project = File(temp, "shop").apply { mkdirs() }
    private val source = File(project, "src/Main.kt").apply { parentFile.mkdirs(); writeText("fun main() {}") }
    private val location = SourceLocation(":app", "src/Main.kt", source.path, 3)
    private val idea = RunningIde("IntelliJ IDEA", "2026.3", File(temp, "IDEA.app/Contents/MacOS/idea"), listOf(project))
    private val studio = RunningIde("Android Studio", "2026.1", File(temp, "Studio.app/Contents/MacOS/studio"), listOf(File(temp, "other")))

    private val commands = mutableListOf<List<String>>()
    private var result = Run(0, "")

    private fun opener(vararg ides: RunningIde, template: String? = null) = IdeOpener(template, runningIdes = { ides.toList() }, run = { commands += it; result })

    private fun IdeOpener.openNow(target: Target): Outcome = runBlocking { open(target) }

    @AfterTest
    fun tearDown() {
        temp.deleteRecursively()
    }

    @Test
    fun aProjectFileOpensThroughTheLauncherOfTheIdeThatHasTheProject() {
        assertEquals(Outcome.Opened("IntelliJ IDEA 2026.3"), opener(studio, idea).openNow(Target.Source(location)))
        assertEquals(listOf(listOf(idea.launcher.path, "--line", "3", source.path)), commands)

        commands.clear()
        opener(idea).openNow(Target.Source(location.copy(line = 0)))
        assertEquals(listOf(listOf(idea.launcher.path, source.path)), commands, "no line: the file alone")
    }

    @Test
    fun withoutARunningIdeOrAFileNothingIsRun() {
        val none = opener().openNow(Target.Source(location))
        assertEquals(Outcome.Failed(IdeOpener.NO_IDE), none)
        val gone = opener(idea).openNow(Target.Source(location.copy(absolutePath = "/no/such/file.kt")))
        assertTrue(gone is Outcome.Failed && "No such file" in gone.message, gone.toString())
        assertEquals(emptyList(), commands)
    }

    @Test
    fun theLaunchersAnswerDecidesTheOutcome() {
        result = Run(3, "boom")
        val failed = assertIs<Outcome.Failed>(opener(idea).openNow(Target.Source(location)))
        assertTrue("exited with code 3" in failed.message && "boom" in failed.message, failed.message)

        result = Run(null, "")
        assertIs<Outcome.Opened>(opener(idea).openNow(Target.Source(location)), "still running after the wait: an IDE starting up")

        result = Run(0, "Cannot find or open the specified project\n")
        val lost = assertIs<Outcome.Failed>(opener(idea).openNow(Target.Symbol("kotlinx.coroutines.JobSupport", project.path)))
        assertTrue("Cannot find or open the specified project" in lost.message, lost.message)
    }

    @Test
    fun aClassOutsideTheProjectSourcesOpensByNameInTheIdeThatHasTheProjectOpen() {
        assertEquals(Outcome.Opened("IntelliJ IDEA 2026.3"), opener(studio, idea).openNow(Target.Symbol("kotlinx.coroutines.JobSupport", project.path)))
        assertEquals(listOf(listOf(idea.launcher.path, "jetbrains://idea/navigate/reference?project=shop&fqn=kotlinx.coroutines.JobSupport")), commands)

        File(project, ".idea/.name").apply { parentFile.mkdirs() }.writeText("My Shop\n")
        commands.clear()
        opener(idea).openNow(Target.Symbol("java.lang.Thread", project.path))
        assertEquals("jetbrains://idea/navigate/reference?project=My%20Shop&fqn=java.lang.Thread", commands.single()[1], "the project's own name, when it has one")

        val closed = assertIs<Outcome.Failed>(opener(studio).openNow(Target.Symbol("java.lang.Thread", project.path)))
        assertTrue("Open ${project.path} in Android Studio 2026.1" in closed.message, closed.message)
        assertEquals(Outcome.Failed(IdeOpener.NO_IDE), opener().openNow(Target.Symbol("java.lang.Thread", project.path)))
        val unknown = assertIs<Outcome.Failed>(opener(idea).openNow(Target.Symbol("java.lang.Thread", "")))
        assertTrue("names no project" in unknown.message, unknown.message)
    }

    @Test
    fun aConfiguredCommandReplacesTheLaunchersAndOpensFilesOnly() {
        assertEquals(Outcome.Opened("my-ide"), opener(idea, template = "my-ide -g {path}:{line}").openNow(Target.Source(location)))
        assertEquals(listOf(listOf("my-ide", "-g", "${source.path}:3")), commands)
        val symbol = assertIs<Outcome.Failed>(opener(idea, template = "my-ide {path}").openNow(Target.Symbol("java.lang.Thread", project.path)))
        assertTrue("opens files only" in symbol.message, symbol.message)
    }

    @Test
    fun substitutesPlaceholdersAfterSplitting() {
        assertEquals(listOf("idea", "--line", "7", "/my project/Main.kt"), IdeOpener.buildCommand("idea --line {line} {path}", "/my project/Main.kt", 7))
        assertEquals(
            listOf("/Applications/VS Code.app/code", "-g", "/p/Main.kt:3"),
            IdeOpener.buildCommand(""""/Applications/VS Code.app/code"   -g '{path}:{line}'""", "/p/Main.kt", 3),
        )
        assertEquals(listOf("open", "/p/Main.kt", "1"), IdeOpener.buildCommand("open {path} {line}", "/p/Main.kt", 0), "unknown line opens the top")
        assertFailsWith<IllegalArgumentException> { IdeOpener.buildCommand("idea \"{path}", "/p", 1) }
        assertFailsWith<IllegalArgumentException> { IdeOpener.buildCommand("   ", "/p", 1) }
    }

    @Test
    fun theNavigateUrlEncodesWhatTheIdeParses() {
        assertEquals("jetbrains://idea/navigate/reference?project=a%20%26%20b&fqn=p.Outer.Inner", IdeOpener.navigateUrl("a & b", "p.Outer.Inner"))
    }

    /** The real process runner, on real commands. */
    @Test
    fun runsTheConfiguredCommandAndReportsFailuresInsteadOfThrowing() = runBlocking {
        val marker = File(temp, "opened.txt")
        val opener = IdeOpener("/bin/sh -c 'echo \"$1:$2\" > \"$0\"' ${marker.path} {path} {line}")
        assertIs<Outcome.Opened>(opener.openNow(Target.Source(location)))
        assertEquals("${source.path}:3", marker.readText().trim())

        val failed = assertIs<Outcome.Failed>(IdeOpener("/bin/sh -c 'echo nope; exit 3'").openNow(Target.Source(location)))
        assertTrue("exited with code 3: nope" in failed.message, failed.message)
        assertIs<Outcome.Failed>(IdeOpener("/no/such/launcher {path}").openNow(Target.Source(location)))
    }
}
