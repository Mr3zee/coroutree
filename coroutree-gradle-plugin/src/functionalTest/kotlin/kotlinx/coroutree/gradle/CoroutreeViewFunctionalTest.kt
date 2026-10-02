package kotlinx.coroutree.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `coroutreeView` for real: the fake agent jar is also a GUI that records how it was started. */
class CoroutreeViewFunctionalTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun theGuiIsStartedOncePerInvocationOnTheDataOfTheRootProject() {
        val project = TestProject(tempDir.canonicalFile)
        project.append("settings.gradle.kts", """include(":app")""")
        project.file(
            "build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlinx.coroutree") }
            dependencies { coroutreeGui(files("${project.fakeAgent}")) }
            """,
        )
        project.javaApplication(
            projectDir = "app",
            extraBuildScript = """
                dependencies { coroutreeGui(files(if (providers.gradleProperty("noGui").isPresent) emptyList() else listOf("${project.fakeAgent}"))) }
            """,
        )
        val dataDir = File(project.dir, "build/coroutree")
        // No ideCommand in the build script: the GUI is left to its own default.
        val expected = listOf("--dir", dataDir.path, "--open-latest")
        fun started(output: String) = Regex("coroutree GUI started").findAll(output).count()

        // Nothing has been recorded yet; the GUI opens on a directory that is there and empty.
        val first = project.run("coroutreeView", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, first.task(":coroutreeView")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":app:coroutreeView")?.outcome)
        assertEquals(1, started(first.output), "every project has the task, and they all mean the same GUI")
        assertContains(first.output, File(dataDir, "gui.log").path)
        assertEquals(listOf(expected), GuiLaunches.await(dataDir, 1))

        // The next invocation is a new request, also when Gradle does not configure the build again.
        val second = project.run("coroutreeView", "--configuration-cache")
        assertContains(second.output, "Reusing configuration cache")
        assertEquals(1, started(second.output))
        assertEquals(listOf(expected, expected), GuiLaunches.await(dataDir, 2))
        assertEquals(2, File(dataDir, "gui.log").readLines().count { it.startsWith("fake GUI is up") }, "the GUI's output of every start is kept")

        val one = project.run(":app:coroutreeView")
        assertEquals(1, started(one.output))
        assertEquals(3, GuiLaunches.await(dataDir, 3).size)

        val empty = project.runner(":app:coroutreeView", "-PnoGui").buildAndFail()
        assertContains(empty.output, "'coroutreeGui' configuration is empty")
        assertFalse("coroutree GUI started" in empty.output)
    }
}
