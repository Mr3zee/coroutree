package kotlinx.coroutree.gradle

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.services.BuildService
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Adds `-javaagent:<agent>=config=<file>` to a forking task and writes that file and the merged source index right
 * before the JVM starts. While tracing is off, [agentClasspath], [sourceIndexParts] and [settings] are empty, so the
 * plugin changes no task input.
 */
internal abstract class AgentArgumentProvider : CommandLineArgumentProvider {
    @get:Input
    abstract val enabled: Property<Boolean>

    /**
     * The build id while tracing is on, else empty. A run with the agent exists for its trace, so each invocation is
     * new work. An `upToDateWhen` spec can't do that: it gives a task without outputs, such as a plain `JavaExec`,
     * an up-to-date check it never had.
     */
    @get:Input
    abstract val runId: Property<String>

    @get:Classpath
    abstract val agentClasspath: ConfigurableFileCollection

    /** Own index first, then the indexes of the projects on the runtime classpath. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceIndexParts: ConfigurableFileCollection

    @get:Internal
    abstract val live: Property<Boolean>

    @get:Internal
    abstract val stackDepth: Property<Int>

    @get:Internal
    abstract val pace: Property<Boolean>

    @get:Internal
    abstract val paceStartPaused: Property<Boolean>

    /** A positive decimal number or [UNLIMITED], validated by [CommandLine]. */
    @get:Internal
    abstract val paceEventsPerSecond: Property<String>

    @get:Internal
    abstract val includedPackages: ListProperty<String>

    @get:Internal
    abstract val excludedPackages: ListProperty<String>

    @get:Input
    val settings: Provider<String>
        get() = enabled.map { on ->
            if (on) {
                "live=${live.get()} stack=${stackDepth.get()} include=${includedPackages.get()} exclude=${excludedPackages.get()} " +
                    "pace=${pace.get()} paused=${paceStartPaused.get()} eventsPerSecond=${paceEventsPerSecond.get()}"
            } else {
                ""
            }
        }

    // Output locations and invocation ids don't affect the forked task's inputs.

    @get:Internal
    abstract val taskPath: Property<String>

    /** Unset: the agent's default, `~/.coroutree/sessions`. */
    @get:Internal
    abstract val sessionsDirectory: Property<String>

    @get:Internal
    abstract val rootProjectDirectory: Property<String>

    /** `<root build dir>/coroutree` */
    @get:Internal
    abstract val dataDirectory: Property<String>

    /** `<build dir>/coroutree/tmp/<task name>` */
    @get:Internal
    abstract val workDirectory: Property<String>

    /**
     * The [BuildIdService], typed loosely: projects can load this plugin in different class loaders, and the first
     * of them registers the service, so to the others its class is a different class of the same name.
     */
    @get:Internal
    abstract val buildIdService: Property<BuildService<*>>

    override fun asArguments(): Iterable<String> {
        if (!enabled.get()) return emptyList()
        val agentJar = agentClasspath.files.singleOrNull()
            ?: throw GradleException(
                "The '${CoroutreePlugin.AGENT_CONFIGURATION}' configuration must resolve to one file, the agent jar. " +
                    "It resolved to ${agentClasspath.files}."
            )
        val depth = stackDepth.get()
        if (depth < 1) throw GradleException("coroutree.stackDepth is $depth. It must be positive.")

        if (pace.get() && paceStartPaused.get() && !live.get()) {
            LOGGER.warn(
                "coroutree: ${taskPath.get()} ignores pace.startPaused because with live=false no GUI can resume it. " +
                    "The program runs."
            )
        }

        val buildId = BuildIdService.idOf(buildIdService.get())
        val workDir = File(workDirectory.get()).apply { mkdirs() }
        val traceDir = File(dataDirectory.get(), "traces/$buildId").apply { mkdirs() }

        val parts = sourceIndexParts.files
        parts.firstOrNull { !it.isFile }?.let { throw GradleException("The source index $it hasn't been built") }
        val index = SourceIndexFormat.merge(parts)
        val indexFile = File(workDir, "source-index.tsv")
        writeAtomically(indexFile) { it.write(index.joinToString(separator = "") { line -> "$line\n" }.toByteArray(Charsets.UTF_8)) }

        val include = includedPackages.get().ifEmpty { SourceIndexFormat.minimalPrefixes(SourceIndexFormat.packages(index)) }
        val config = Properties().apply {
            setProperty("trace.dir", traceDir.absolutePath)
            // Without sessions.dir the JVM registers in the user's registry, which the GUI reads, like every build's.
            sessionsDirectory.orNull?.let { setProperty("sessions.dir", File(it).apply { mkdirs() }.absolutePath) }
            setProperty("live", live.get().toString())
            setProperty("build.id", buildId)
            setProperty("task.path", taskPath.get())
            setProperty("project.dir", rootProjectDirectory.get())
            setProperty("source.index", indexFile.absolutePath)
            setProperty("include", include.joinToString(","))
            setProperty("exclude", excludedPackages.get().joinToString(","))
            setProperty("stack.depth", depth.toString())
            setProperty("pace", pace.get().toString())
            setProperty("pace.paused", paceStartPaused.get().toString())
            setProperty("pace.events.per.second", paceEventsPerSecond.get())
        }
        val configFile = File(workDir, "agent.properties")
        writeAtomically(configFile) { config.store(it, "coroutree agent configuration of ${taskPath.get()}") }

        return listOfNotNull(
            monitorProbe(agentJar, workDir)?.let { "-agentpath:${it.absolutePath}" },
            "-javaagent:${agentJar.absolutePath}=config=${configFile.absolutePath}",
            // The bootstrap append disables the JVM's class-data sharing for application classes, with a warning on
            // every start. Off from the start, there is no warning.
            "-Xshare:off",
        )
    }

    /**
     * Unpacks this machine's monitor probe from the agent jar, or returns `null` when the jar has none for it.
     * `-agentpath` avoids the native-access warning that `System.load` gets on JDK 24 and later. The forked JVM is
     * assumed to run on Gradle's platform. Nothing checks it. The macOS probe is a universal binary.
     */
    private fun monitorProbe(agentJar: File, workDir: File): File? {
        val entryName = HostPlatform.monitorProbeEntry() ?: return null
        ZipFile(agentJar).use { jar ->
            val entry = jar.getEntry(entryName) ?: return null
            val library = File(workDir, entryName.substringAfterLast('/'))
            writeAtomically(library) { out -> jar.getInputStream(entry).use { it.copyTo(out) } }
            return library
        }
    }

    internal companion object {
        const val UNLIMITED = "unlimited"

        private val LOGGER = org.gradle.api.logging.Logging.getLogger(AgentArgumentProvider::class.java)
    }

    /**
     * A `Test` task forks several JVMs at a time, so one worker's agent can read these files while the next fork's
     * arguments rewrite them. A file moved into place is always whole.
     */
    private fun writeAtomically(file: File, write: (OutputStream) -> Unit) {
        val temp = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            temp.outputStream().use(write)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }
}
