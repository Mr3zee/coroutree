package kotlinx.coroutree.it

import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.HandledBy
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.SourceFile
import kotlinx.coroutree.model.TraceHeader
import kotlinx.coroutree.model.tree.TraceStore
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The contract between the Gradle plugin and the agent: the properties file, the source index, the trace directory. */
class AgentConfigTest {
    @Test
    fun configFileAsTheGradlePluginWritesIt() {
        val dir = File(TestEnvironment.workDir, "ConfigFile").apply { deleteRecursively(); mkdirs() }
        val index = File(dir, "source-index.tsv").apply {
            writeText(
                "M\t:app\t/work/app\n" +
                    "F\tsamples\tExceptionPropagation.kt\tsrc/main/kotlin/samples/ExceptionPropagation.kt\n" +
                    "F\t\tRoot.kt\tsrc/main/kotlin/Root.kt\n" +
                    "X\tfrom a newer plugin\n" +
                    "M\t:lib\t/work/lib\n"
            )
        }
        val config = File(dir, "agent.properties")
        Properties().apply {
            setProperty("trace.dir", File(dir, "traces/b42").path)
            setProperty("sessions.dir", File(dir, "sessions/b42").path)
            setProperty("live", "false")
            setProperty("build.id", "b42")
            setProperty("task.path", ":app:run")
            setProperty("project.dir", "/work")
            setProperty("source.index", index.path)
            setProperty("include", "samples,com.acme")
            setProperty("exclude", "samples.generated")
            setProperty("stack.depth", "3")
        }.also { properties -> config.outputStream().use { properties.store(it, null) } }

        val process = ProcessBuilder(
            TestEnvironment.java, "-javaagent:${TestEnvironment.agentJar}=config=$config",
            "-cp", TestEnvironment.samplesClasspath, "samples.ExceptionPropagationKt",
        ).directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "output.txt")).start()
        assertEquals(0, process.waitFor(), File(dir, "output.txt").readText())

        val trace = File(dir, "traces/b42/app-run-${process.pid()}.ctrace")
        assertTrue(trace.exists(), "the agent names the trace after the task and the pid: ${File(dir, "traces/b42").list()?.toList()}")
        val snapshot = trace.inputStream().use(TraceStore::read)

        val header = assertNotNull(snapshot.header)
        assertEquals(TraceHeader.FORMAT_VERSION, header.formatVersion)
        assertEquals("b42", header.buildId)
        assertEquals(":app:run", header.taskPath)
        assertEquals("/work", header.projectDir)
        assertEquals(listOf("samples", "com.acme"), header.includePackages)
        assertEquals(listOf("samples.generated"), header.excludePackages)
        assertEquals(process.pid(), header.jvm.pid)
        assertTrue(header.jvm.command.startsWith("samples.ExceptionPropagationKt"))
        assertTrue(header.agentVersion.isNotEmpty())
        assertEquals(listOf(":app", ":lib"), header.sourceIndex.modules.map { it.path })
        assertEquals(
            listOf(
                SourceFile("samples", "ExceptionPropagation.kt", "src/main/kotlin/samples/ExceptionPropagation.kt"),
                SourceFile("", "Root.kt", "src/main/kotlin/Root.kt"),
            ),
            header.sourceIndex.modules[0].files,
        )

        // The index resolves the site of a node to a file on disk.
        val failing = snapshot.nodes.values.single { it.info.name == "failing" }
        val location = assertNotNull(snapshot.sources.resolve(assertNotNull(snapshot.frame(failing.info.siteFrame))))
        assertEquals(":app", location.module)
        assertEquals(File("/work/app", "src/main/kotlin/samples/ExceptionPropagation.kt").path, location.absolutePath)
        assertEquals(19, location.line)

        assertTrue(snapshot.events.all { it.stack.size <= 3 }, "stack.depth limits captured stacks")
        assertEquals(emptyList(), snapshot.diagnostics)
    }

    @Test
    fun codeOutsideTheProjectPackagesIsLibraryCode() {
        val project = runUnderAgent("samples.ExceptionPropagationKt", runName = "OriginProject")
        val library = runUnderAgent("samples.ExceptionPropagationKt", runName = "OriginLibrary", agentOptions = mapOf("include" to "com.acme"))

        fun AgentRun.coroutineOrigins() = snapshot.nodes.values.filter { it.info.kind == NodeKind.COROUTINE }.map { it.info.origin }.toSet()
        fun AgentRun.catches() = snapshot.events.count { it.kind == EventKind.EXCEPTION_HANDLED && it.handledBy == HandledBy.CATCH }

        assertEquals(setOf(Origin.PROJECT), project.coroutineOrigins())
        assertEquals(setOf(Origin.LIBRARY), library.coroutineOrigins())
        assertEquals(1, project.catches())
        assertEquals(0, library.catches(), "catch blocks are only instrumented in project classes")
    }

    @Test
    fun badOptionsAreReportedAndDoNotStopTheProgram() {
        val run = runUnderAgent(
            "samples.StructuredConcurrencyKt",
            runName = "BadOptions",
            agentOptions = mapOf("stack.depth" to "many", "source.index" to "/nonexistent/index.tsv"),
        )
        assertEquals(0, run.exitCode, run.output)
        val messages = run.snapshot.diagnostics.filter { it.severity == Diagnostic.Severity.WARNING }.map { it.message }
        assertTrue(messages.any { "stack.depth" in it }, messages.toString())
        assertTrue(messages.any { "/nonexistent/index.tsv" in it }, messages.toString())
        assertTrue(run.snapshot.nodes.values.any { it.info.construct == "runBlocking" })
    }

    @Test
    fun unwritableTraceLocationLeavesTheProgramAlone() {
        val blocker = File(TestEnvironment.workDir, "Unwritable").apply { deleteRecursively(); mkdirs() }.resolve("file").apply { writeText("") }
        val process = ProcessBuilder(
            TestEnvironment.java, "-javaagent:${TestEnvironment.agentJar}=trace.file=${File(blocker, "sub/trace.ctrace")}",
            "-cp", TestEnvironment.samplesClasspath, "samples.StructuredConcurrencyKt",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertTrue("total = 3" in output, output)
        assertTrue("agent failed to start" in output, output)
    }
}
