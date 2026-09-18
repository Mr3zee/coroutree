package kotlinx.coroutree.it

import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.TraceStore
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Locations handed over by the build script, see the `test` task of this module. */
object TestEnvironment {
    val java: String = property("java")
    val agentJar: String = property("agentJar")
    val samplesClasspath: String = property("samplesClasspath")
    val fakeCoroutinesClasses: String = property("fakeCoroutinesClasses")
    val goldenDir = File(property("goldenDir"))
    val workDir = File(property("workDir"))
    val updateGoldens: Boolean = property("updateGoldens").toBoolean()

    /** Class path of the sample corpus compiled against kotlinx.coroutines [version]. */
    fun samplesClasspathWith(version: String): String = property("coroutines.$version")

    private fun property(name: String): String =
        System.getProperty("coroutree.test.$name") ?: error("System property coroutree.test.$name is not set; run the tests through Gradle")
}

/**
 * The native monitor probe of this machine, unpacked from the agent jar the way the Gradle plugin does it.
 * `null` where the agent jar under test has none (it is built for the host platform only, and only with a C compiler).
 */
object MonitorProbeLibrary {
    val file: File? by lazy {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase().let { if (it == "amd64" || it == "x86_64") "x64" else "arm64" }
        val entryName = "kotlinx/coroutree/agent/native/" + when {
            "mac" in os -> "macos/libcoroutree-monitor.dylib"
            "linux" in os -> "linux-$arch/libcoroutree-monitor.so"
            else -> "windows-x64/coroutree-monitor.dll"
        }
        ZipFile(TestEnvironment.agentJar).use { jar ->
            val entry = jar.getEntry(entryName) ?: return@lazy null
            val target = File(TestEnvironment.workDir, "native/" + entryName.substringAfterLast('/')).apply { parentFile.mkdirs() }
            jar.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
            target
        }
    }
}

class AgentRun(val exitCode: Int, val output: String, val traceFile: File) {
    val snapshot: TraceSnapshot by lazy { traceFile.inputStream().use(TraceStore::read) }
}

/**
 * Runs [mainClass] in a fresh JVM under the agent and waits for it to end.
 * [input] is what the program finds on its standard input, `null` for nothing.
 */
fun runUnderAgent(
    mainClass: String,
    classpath: String = TestEnvironment.samplesClasspath,
    agentOptions: Map<String, String> = emptyMap(),
    jvmArgs: List<String> = emptyList(),
    input: String? = null,
    runName: String = mainClass.substringAfterLast('.'),
    timeoutSeconds: Long = 120,
    monitorProbeAsAgentPath: Boolean = true,
    programArgs: List<String> = emptyList(),
): AgentRun = startUnderAgent(mainClass, classpath, agentOptions, jvmArgs, input, runName, monitorProbeAsAgentPath, programArgs).await(timeoutSeconds)

/** A JVM under the agent that is still running: for tests that talk to it while it runs. */
class AgentProcess(val mainClass: String, val process: Process, val runDir: File, val traceFile: File, private val outputFile: File) {
    val output: String get() = outputFile.readText()

    /** Where the agent publishes its live session, if the run was started with `live=true`. */
    val sessionsDir: File get() = File(runDir, "sessions")

    fun await(timeoutSeconds: Long = 120): AgentRun {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            val dump = threadDump()
            process.destroyForcibly().waitFor()
            error("$mainClass did not finish in $timeoutSeconds s under the agent. Output so far:\n$output\n\nThreads:\n$dump")
        }
        return AgentRun(process.exitValue(), output, traceFile)
    }

    /** What every thread of the JVM is doing, from `jcmd`: what one wants to see of a program that hangs. */
    fun threadDump(): String = runCatching {
        val jcmd = File(File(TestEnvironment.java).parentFile, if (isWindows) "jcmd.exe" else "jcmd").path
        val dump = ProcessBuilder(jcmd, process.pid().toString(), "Thread.print").redirectErrorStream(true).start()
        dump.inputStream.bufferedReader().readText().also { dump.waitFor(20, TimeUnit.SECONDS) }
    }.getOrElse { "no thread dump: $it" }
}

/** Starts [mainClass] in a fresh JVM under the agent and returns at once. With `live=true` its session goes to `<run dir>/sessions`. */
fun startUnderAgent(
    mainClass: String,
    classpath: String = TestEnvironment.samplesClasspath,
    agentOptions: Map<String, String> = emptyMap(),
    jvmArgs: List<String> = emptyList(),
    input: String? = null,
    runName: String = mainClass.substringAfterLast('.'),
    monitorProbeAsAgentPath: Boolean = true,
    programArgs: List<String> = emptyList(),
    keepStdinOpen: Boolean = false,
): AgentProcess {
    val runDir = File(TestEnvironment.workDir, runName).apply {
        deleteRecursively()
        mkdirs()
    }
    val traceFile = File(runDir, "trace.ctrace")
    val options = mapOf("trace.file" to traceFile.path, "include" to "samples", "live" to "false", "sessions.dir" to File(runDir, "sessions").path) + agentOptions
    // The way the Gradle plugin starts a JVM: the native monitor probe, if the agent has one for this machine, as -agentpath.
    val probe = if (monitorProbeAsAgentPath) listOfNotNull(MonitorProbeLibrary.file?.let { "-agentpath:${it.path}" }) else emptyList()
    val command = listOf(TestEnvironment.java) + probe + jvmArgs + listOf(
        "-javaagent:${TestEnvironment.agentJar}=" + options.entries.joinToString(",") { "${it.key}=${it.value}" },
        "-Dcoroutree.debug=true",
        "-cp", classpath,
        mainClass,
    ) + programArgs
    val outputFile = File(runDir, "output.txt")
    val process = ProcessBuilder(command)
        .directory(runDir)
        .redirectErrorStream(true)
        .redirectOutput(outputFile)
        .apply { if (input == null && !keepStdinOpen) redirectInput(ProcessBuilder.Redirect.from(File(if (isWindows) "NUL" else "/dev/null"))) }
        .start()
    if (input != null) process.outputStream.use { it.write(input.toByteArray()) }
    return AgentProcess(mainClass, process, runDir, traceFile, outputFile)
}

private val isWindows = System.getProperty("os.name").startsWith("Windows")
