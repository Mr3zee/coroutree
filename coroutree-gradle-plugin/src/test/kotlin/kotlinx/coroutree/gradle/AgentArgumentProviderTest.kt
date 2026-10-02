package kotlinx.coroutree.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a forked JVM is started with, and what the agent finds where it is pointed to. The functional tests show the
 * same through real builds; here are the cases a build cannot be bent into cheaply.
 */
class AgentArgumentProviderTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var project: Project

    // The characters that mean something to a properties file, to -javaagent options and to a shell.
    private lateinit var oddDir: File
    private lateinit var agentJar: File

    @BeforeTest
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(File(tempDir, "project").apply { mkdirs() }).build()
        // A colon and a backslash are ordinary characters of a name only where they are not separators.
        oddDir = File(tempDir.canonicalFile, if (File.separatorChar == '/') "odd dir, a=b #1 !x: ü\\y" else "odd dir, a=b #1 !x ü")
        agentJar = jar(File(tempDir, "agent.jar"), emptyMap())
    }

    private fun jar(file: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
            }
        }
        return file
    }

    private fun index(name: String, vararg lines: String): File =
        File(tempDir, name).apply { writeText(lines.joinToString("") { "$it\n" }) }

    /** As the plugin fills it for an enabled task with nothing set in the build script. */
    private fun provider(): AgentArgumentProvider = project.objects.newInstance(AgentArgumentProvider::class.java).apply {
        enabled.set(true)
        agentClasspath.from(agentJar)
        live.set(true)
        stackDepth.set(32)
        pace.set(true)
        paceStartPaused.set(false)
        paceEventsPerSecond.set(AgentArgumentProvider.UNLIMITED)
        taskPath.set(":app:run")
        rootProjectDirectory.set(File(oddDir, "root").path)
        dataDirectory.set(File(oddDir, "root/build/coroutree").path)
        workDirectory.set(File(oddDir, "root/app/build/coroutree/tmp/run").path)
        buildIdService.set(project.gradle.sharedServices.registerIfAbsent(BuildIdService.NAME, BuildIdService::class.java) {})
    }

    /** The file the way the agent reads it (AgentConfig): UTF-8, java.util.Properties. */
    private fun AgentArgumentProvider.config(): Properties =
        Properties().apply { File(workDirectory.get(), "agent.properties").reader(Charsets.UTF_8).use(::load) }

    @Test
    fun aSwitchedOffProviderAddsNothingWritesNothingAndLooksTheSameWhateverItsSettings() {
        val off = project.objects.newInstance(AgentArgumentProvider::class.java).apply {
            enabled.set(false)
            // Nothing else is set: none of it may be asked for.
            workDirectory.set(File(oddDir, "work").path)
            dataDirectory.set(File(oddDir, "data").path)
        }
        assertEquals(emptyList(), off.asArguments().toList())
        assertFalse(oddDir.exists(), "a run without the agent leaves nothing behind")

        // Settings of the agent are no input of a task the agent is not attached to: changing them must not rerun its tests.
        val alsoOff = provider().apply {
            enabled.set(false)
            live.set(false)
            stackDepth.set(3)
            includedPackages.add("com.acme")
        }
        assertEquals(off.settings.get(), alsoOff.settings.get())
        // … and they are an input of one it is attached to.
        val on = provider()
        val changed = listOf<AgentArgumentProvider.() -> Unit>(
            { live.set(false) }, { stackDepth.set(3) }, { pace.set(false) }, { paceStartPaused.set(true) },
            { paceEventsPerSecond.set("2") }, { includedPackages.add("com.acme") }, { excludedPackages.add("com.acme") },
        ).map { change -> provider().apply(change).settings.get() }
        assertEquals(changed.size + 1, (changed + on.settings.get()).toSet().size, "every setting shows in the task's inputs: $changed")
    }

    @Test
    fun configurationSurvivesPathsWithSpacesSeparatorsAndEscapes() {
        val own = index("own.tsv", "M\t:app\t${File(oddDir, "root/app").path}", "F\tcom.acme.app\tMain.kt\tsrc/main/kotlin/Main.kt")
        val provider = provider().apply {
            sourceIndexParts.from(own)
            excludedPackages.addAll("com.acme.generated", "com.acme.shaded")
            paceEventsPerSecond.set("0.2")
        }

        val arguments = provider.asArguments().toList()

        val workDir = File(oddDir, "root/app/build/coroutree/tmp/run")
        val probe = arguments.filter { it.startsWith("-agentpath:") }
        assertEquals(emptyList(), probe, "an agent jar without a probe for this machine: everything else still works")
        assertEquals(listOf("-javaagent:${agentJar.absolutePath}=config=${File(workDir, "agent.properties").path}", "-Xshare:off"), arguments)

        val config = provider.config()
        val buildId = config.getProperty("build.id")
        assertEquals(
            mapOf(
                "trace.dir" to File(oddDir, "root/build/coroutree/traces/$buildId").path,
                "sessions.dir" to File(oddDir, "root/build/coroutree/sessions/$buildId").path,
                "live" to "true",
                "build.id" to buildId,
                "task.path" to ":app:run",
                "project.dir" to File(oddDir, "root").path,
                "source.index" to File(workDir, "source-index.tsv").path,
                "include" to "com.acme.app",
                "exclude" to "com.acme.generated,com.acme.shaded",
                "stack.depth" to "32",
                "pace" to "true",
                "pace.paused" to "false",
                "pace.events.per.second" to "0.2",
            ),
            config.entries.associate { it.key.toString() to it.value.toString() },
        )
        assertTrue(File(config.getProperty("trace.dir")).isDirectory && File(config.getProperty("sessions.dir")).isDirectory, "the agent is given directories that exist")
        assertEquals(own.readLines(), File(config.getProperty("source.index")).readLines())
        assertEquals(setOf("agent.properties", "source-index.tsv"), workDir.list()!!.toSet(), "nothing half-written is left next to the files")
    }

    @Test
    fun projectCodeIsWhatTheScriptSaysOrElseThePackagesOfTheMergedIndex() {
        val own = index("own.tsv", "M\t:app\t/p/app", "F\tcom.acme.app\tMain.kt\tsrc/Main.kt", "F\t\tRoot.kt\tsrc/Root.kt")
        val lib = index("lib.tsv", "M\t:lib\t/p/lib", "F\tcom.acme\tLib.kt\tsrc/Lib.kt", "F\torg.other\tOther.java\tsrc/Other.java")
        val again = index("again.tsv", "M\t:lib\t/p/lib", "F\tcom.twice\tLib.kt\tsrc/Lib.kt")

        val derived = provider().apply { sourceIndexParts.from(own, lib, again) }
        derived.asArguments()
        assertEquals("com.acme,org.other", derived.config().getProperty("include"))
        assertEquals(own.readLines() + lib.readLines(), File(derived.config().getProperty("source.index")).readLines(), "own index first, a module reached twice once")

        val explicit = provider().apply {
            sourceIndexParts.from(own, lib)
            includedPackages.addAll("org.other", "com.elsewhere")
        }
        explicit.asArguments()
        assertEquals("org.other,com.elsewhere", explicit.config().getProperty("include"), "as written, in the order written")

        // No sources at all, or only in the root package: the root package is never a prefix, it would claim every library.
        val rootOnly = provider().apply { sourceIndexParts.from(index("root.tsv", "M\t:\t/p", "F\t\tMain.kt\tMain.kt")) }
        rootOnly.asArguments()
        assertEquals("", rootOnly.config().getProperty("include"))
        val none = provider()
        none.asArguments()
        assertEquals("", none.config().getProperty("include"))
        assertEquals(emptyList(), File(none.config().getProperty("source.index")).readLines())
    }

    @Test
    fun theHostsMonitorProbeIsUnpackedAndPassedAsANativeAgent() {
        val entry = HostPlatform.monitorProbeEntry()
        assumeTrue(entry != null, "no probe is built for this platform")
        val binary = ByteArray(70_000) { (it * 31).toByte() }
        val withProbe = jar(File(tempDir, "agent-with-probe.jar"), mapOf(entry!! to binary, "kotlinx/coroutree/agent/native/other-os/libother.so" to ByteArray(3)))
        val provider = provider().apply { agentClasspath.setFrom(withProbe) }

        val arguments = provider.asArguments().toList()

        val library = File(provider.workDirectory.get(), entry.substringAfterLast('/'))
        assertEquals(
            listOf("-agentpath:${library.path}", "-javaagent:${withProbe.absolutePath}=config=${File(provider.workDirectory.get(), "agent.properties").path}", "-Xshare:off"),
            arguments,
        )
        assertContentEquals(binary, library.readBytes())
        // Asked again for the next fork, with the library of the first one possibly loaded: same file, same content.
        assertEquals(arguments, provider.asArguments().toList())
        assertContentEquals(binary, library.readBytes())
    }

    @Test
    fun whatCannotWorkFailsTheBuildAndSaysWhatToChange() {
        val second = jar(File(tempDir, "second.jar"), emptyMap())
        val two = assertFailsWith<GradleException> { provider().apply { agentClasspath.from(second) }.asArguments() }
        assertTrue("coroutreeAgent" in two.message!! && "second.jar" in two.message!!, two.message)
        val none = assertFailsWith<GradleException> { provider().apply { agentClasspath.setFrom() }.asArguments() }
        assertTrue("coroutreeAgent" in none.message!!, none.message)

        for (depth in listOf(0, -1)) {
            val failure = assertFailsWith<GradleException> { provider().apply { stackDepth.set(depth) }.asArguments() }
            assertTrue("coroutree.stackDepth" in failure.message!! && "$depth" in failure.message!!, failure.message)
        }
        assertEquals("1", provider().apply { stackDepth.set(1); asArguments() }.config().getProperty("stack.depth"))

        val missing = File(tempDir, "never-built.tsv")
        val failure = assertFailsWith<GradleException> { provider().apply { sourceIndexParts.from(missing) }.asArguments() }
        assertTrue(missing.path in failure.message!!, failure.message)
    }

    @Test
    fun everyCallOfOneInvocationNamesTheSameBuild() {
        val run = provider()
        val test = provider().apply {
            taskPath.set(":app:test")
            workDirectory.set(File(oddDir, "root/app/build/coroutree/tmp/test").path)
        }
        run.asArguments()
        test.asArguments()
        test.asArguments()
        assertEquals(run.config().getProperty("build.id"), test.config().getProperty("build.id"))
        assertEquals(run.config().getProperty("trace.dir"), test.config().getProperty("trace.dir"))
        assertEquals(":app:run", run.config().getProperty("task.path"))
        assertEquals(":app:test", test.config().getProperty("task.path"))
    }

    /**
     * A Test task asks for the arguments once per fork, while the agents of earlier forks are reading the files.
     * Whoever reads must find all of a file or the previous one, never a part.
     */
    @Test
    fun aForkThatStartsWhileTheFilesAreRewrittenReadsThemWhole() {
        val own = index("own.tsv", "M\t:app\t/p/app", *Array(2000) { "F\tcom.acme.p$it\tFile$it.kt\tsrc/File$it.kt" })
        val provider = provider().apply { sourceIndexParts.from(own) }
        provider.asArguments()
        val expectedIndex = own.readLines()
        val expectedKeys = provider.config().keys

        val failure = AtomicReference<Throwable>()
        val writersDone = CountDownLatch(4)
        val writers = List(4) {
            thread {
                try {
                    repeat(25) { provider.asArguments() }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    writersDone.countDown()
                }
            }
        }
        var reads = 0
        while (writersDone.count > 0 || reads == 0) {
            val config = provider.config()
            assertEquals(expectedKeys, config.keys, "agent.properties, read $reads")
            assertEquals(expectedIndex, File(config.getProperty("source.index")).readLines(), "source index, read $reads")
            reads++
        }
        writers.forEach { it.join() }
        failure.get()?.let { throw it }
        assertEquals(setOf("agent.properties", "source-index.tsv"), File(provider.workDirectory.get()).list()!!.toSet())
    }
}
