package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.ide.RunningIde
import kotlinx.coroutree.gui.ide.RunningIdes
import kotlinx.coroutree.gui.ide.RunningIdes.Os
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** RunningIdes: "A process is an IDE when its executable is that launcher, or the JVM of the installation running the IDE's main class". */
class RunningIdesTest {
    private val temp: File = Files.createTempDirectory("coroutree-ides").toFile()
    private val home = File(temp, "home").apply { mkdirs() }
    private val configRoot = File(temp, "config")

    @AfterTest
    fun tearDown() {
        temp.deleteRecursively()
    }

    private fun productInfo(name: String, version: String, dataDir: String, launcher: String, java: String, os: String) = """
        {"name": "$name", "version": "$version", "buildNumber": "263.1", "productCode": "IU", "dataDirectoryName": "$dataDir",
         "launch": [{"os": "$os", "arch": "aarch64", "launcherPath": "$launcher", "javaExecutablePath": "$java"}]}
    """.trimIndent()

    /** An installation like `IntelliJ IDEA.app` on macOS. */
    private fun macApp(name: String = "IntelliJ IDEA", version: String = "2026.3", dataDir: String = "IntelliJIdea2026.3"): File {
        val app = File(temp, "$name $version.app")
        File(app, "Contents/Resources/product-info.json").apply { parentFile.mkdirs() }
            .writeText(productInfo(name, version, dataDir, "../MacOS/idea", "../jbr/Contents/Home/bin/java", "macOS"))
        for (file in listOf("Contents/MacOS/idea", "Contents/bin/fsnotifier", "Contents/jbr/Contents/Home/bin/java")) File(app, file).apply { parentFile.mkdirs() }.writeText("")
        return app
    }

    /** An installation like `/opt/idea` on Linux. */
    private fun linuxInstall(): File {
        val root = File(temp, "opt/idea")
        File(root, "product-info.json").apply { parentFile.mkdirs() }.writeText(productInfo("IntelliJ IDEA", "2026.3", "IntelliJIdea2026.3", "bin/idea", "jbr/bin/java", "Linux"))
        for (file in listOf("bin/idea", "bin/idea.sh", "bin/fsnotifier", "jbr/bin/java")) File(root, file).apply { parentFile.mkdirs() }.writeText("")
        return root
    }

    private fun recentProjects(dataDir: String, vararg entries: Pair<String, Boolean>) {
        val xml = entries.joinToString("\n") { (key, opened) ->
            """<entry key="$key"><value><RecentProjectMetaInfo frameTitle="x" ${if (opened) """opened="true" """ else ""}projectWorkspaceId="1"><option name="build" value="IU-263.1" /></RecentProjectMetaInfo></value></entry>"""
        }
        File(configRoot, "$dataDir/options/recentProjects.xml").apply { parentFile.mkdirs() }
            .writeText("<application><component name=\"RecentProjectsManager\"><option name=\"additionalInfo\"><map>\n$xml\n</map></option></component></application>")
    }

    private fun ideOf(command: File, arguments: List<String>? = null, os: Os = Os.MAC) = RunningIdes.ideOf(command, arguments, os, configRoot, home)

    @Test
    fun theLauncherProcessOfAMacAppIsTheIdeAndItsHelpersAreNot() {
        val app = macApp()
        recentProjects("IntelliJIdea2026.3", "\$USER_HOME\$/work/shop" to true, "\$USER_HOME\$/work/closed" to false, "/abs/a&amp;b" to true)

        val ide = assertNotNull(ideOf(File(app, "Contents/MacOS/idea")))
        assertEquals("IntelliJ IDEA 2026.3", ide.title)
        assertEquals(File(app, "Contents/MacOS/idea"), ide.launcher)
        assertEquals(listOf(File(home, "work/shop"), File("/abs/a&b")), ide.openProjects)

        assertNull(ideOf(File(app, "Contents/bin/fsnotifier")), "a helper of the IDE")
        assertNull(ideOf(File(app, "Contents/jbr/Contents/Home/bin/java"), listOf("-Didea.active=true", "org.gradle.launcher.daemon.bootstrap.GradleDaemon")), "a Gradle daemon on the IDE's runtime")
        assertNull(ideOf(File(temp, "elsewhere/idea")), "no installation above it")
        assertNull(ideOf(File(app, "Contents/MacOS/idea"), os = Os.LINUX), "the installation has no launcher for this OS")
    }

    @Test
    fun onLinuxTheNativeLauncherOrTheJvmWithTheIdeMainClassIsTheIde() {
        val root = linuxInstall()
        assertNotNull(ideOf(File(root, "bin/idea"), os = Os.LINUX))
        assertNotNull(ideOf(File(root, "jbr/bin/java"), listOf("-Xmx2g", "-cp", "lib/app.jar", "com.intellij.idea.Main"), Os.LINUX), "idea.sh starts the JVM itself")
        assertNull(ideOf(File(root, "jbr/bin/java"), listOf("-cp", "x.jar", "org.gradle.launcher.daemon.bootstrap.GradleDaemon"), Os.LINUX))
        assertNull(ideOf(File(root, "jbr/bin/java"), null, Os.LINUX), "arguments unknown: can't tell it from any other JVM")
        assertNull(ideOf(File(root, "bin/idea.sh"), os = Os.LINUX), "the script hands over to the launcher, it isn't the process")
    }

    @Test
    fun anUnreadableOrMissingRecentProjectsFileMeansNoKnownProjects() {
        val app = macApp()
        assertEquals(emptyList(), assertNotNull(ideOf(File(app, "Contents/MacOS/idea"))).openProjects)
        File(configRoot, "IntelliJIdea2026.3/options/recentProjects.xml").apply { parentFile.mkdirs() }.writeText("<application><component")
        assertEquals(emptyList(), assertNotNull(ideOf(File(app, "Contents/MacOS/idea"))).openProjects)
    }

    @Test
    fun aBrokenProductInfoIsNoIde() {
        val app = macApp()
        File(app, "Contents/Resources/product-info.json").writeText("{\"name\": ")
        assertNull(ideOf(File(app, "Contents/MacOS/idea")))
        File(app, "Contents/Resources/product-info.json").writeText("{\"name\": \"X\", \"launch\": []}")
        assertNull(ideOf(File(app, "Contents/MacOS/idea")))
    }

    @Test
    fun theIdeWithTheFilesProjectOpenWinsThenTheFirstOne() {
        val idea = RunningIde("IntelliJ IDEA", "2026.3", File("/a/idea"), listOf(File(home, "work")))
        val studio = RunningIde("Android Studio", "2026.1", File("/a/studio"), listOf(File(home, "work/app")))
        val file = File(home, "work/app/src/Main.kt")
        assertSame(studio, RunningIdes.choose(listOf(idea, studio), file), "the closest project")
        assertSame(idea, RunningIdes.choose(listOf(idea, studio), File(home, "work/lib/A.kt")))
        assertSame(idea, RunningIdes.choose(listOf(idea, studio), File("/elsewhere/B.kt")), "nobody has it: the first")
        assertNull(RunningIdes.choose(emptyList(), file))
        assertEquals(File(home, "work/app"), studio.projectOf(file))
        assertNull(studio.projectOf(File(home, "work/application/X.kt")), "a prefix of the name is not the directory")
    }

    @Test
    fun theConfigRootFollowsTheOs() {
        assertEquals(File(home, "Library/Application Support/JetBrains"), Os.MAC.configRoot(home))
        assertEquals("JetBrains", Os.LINUX.configRoot(home).name)
        assertEquals("JetBrains", Os.WINDOWS.configRoot(home).name)
    }

    /** Whatever runs on the build machine, the scan ends without an exception. */
    @Test
    fun scanningTheRealProcessesDoesNotThrow() {
        RunningIdes.find().forEach { assertNotNull(it.launcher) }
    }
}
