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
 * Adds `-javaagent:<agent>=config=<file>` to a forking task and, right before the JVM starts, writes that file
 * and the merged source index it points to.
 *
 * Everything that only matters while the agent is attached is either empty ([agentClasspath], [sourceIndexParts]) or
 * folded into [settings] when it is not, so an unused plugin changes neither the inputs nor the work of a build.
 */
internal abstract class AgentArgumentProvider : CommandLineArgumentProvider {
    @get:Input
    abstract val enabled: Property<Boolean>

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

    /** Execution control, as the agent reads it: the three `pace*` keys of `agent.properties`. */
    @get:Internal
    abstract val pace: Property<Boolean>

    @get:Internal
    abstract val paceStartPaused: Property<Boolean>

    /** A positive decimal number or [UNLIMITED]; checked where it is resolved, see [CommandLine]. */
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

    // Where things go says nothing about what the task produces, and the build id differs every time by design.

    @get:Internal
    abstract val taskPath: Property<String>

    @get:Internal
    abstract val rootProjectDirectory: Property<String>

    /** `<root build dir>/coroutree` */
    @get:Internal
    abstract val dataDirectory: Property<String>

    /** `<build dir>/coroutree/tmp/<task name>` */
    @get:Internal
    abstract val workDirectory: Property<String>

    /**
     * The [BuildIdService], typed loosely on purpose. Projects of one build may load this plugin in different class
     * loaders (different `plugins {}` blocks, no common declaration in the root); the service is registered once, by
     * whichever came first, and to the others its class is a stranger with a familiar name.
     */
    @get:Internal
    abstract val buildIdService: Property<BuildService<*>>

    override fun asArguments(): Iterable<String> {
        if (!enabled.get()) return emptyList()
        val agentJar = agentClasspath.files.singleOrNull()
            ?: throw GradleException(
                "The '${CoroutreePlugin.AGENT_CONFIGURATION}' configuration must resolve to exactly one file, the agent jar, " +
                    "but it resolved to ${agentClasspath.files}"
            )
        val depth = stackDepth.get()
        if (depth < 1) throw GradleException("coroutree.stackDepth must be positive, but it is $depth")

        // Checks that combine settings run on what they resolved to, command line included.
        if (pace.get() && paceStartPaused.get() && !live.get()) {
            LOGGER.warn(
                "coroutree: ${taskPath.get()} was to start paused, but without the live socket nothing could ever resume it: " +
                    "pace.startPaused is ignored and the program runs."
            )
        }

        val buildId = BuildIdService.idOf(buildIdService.get())
        val workDir = File(workDirectory.get()).apply { mkdirs() }
        val traceDir = File(dataDirectory.get(), "traces/$buildId").apply { mkdirs() }
        val sessionsDir = File(dataDirectory.get(), "sessions/$buildId").apply { mkdirs() }

        val parts = sourceIndexParts.files
        parts.firstOrNull { !it.isFile }?.let { throw GradleException("The source index $it has not been built") }
        val index = SourceIndexFormat.merge(parts)
        val indexFile = File(workDir, "source-index.tsv")
        writeAtomically(indexFile) { it.write(index.joinToString(separator = "") { line -> "$line\n" }.toByteArray(Charsets.UTF_8)) }

        val include = includedPackages.get().ifEmpty { SourceIndexFormat.minimalPrefixes(SourceIndexFormat.packages(index)) }
        val config = Properties().apply {
            setProperty("trace.dir", traceDir.absolutePath)
            setProperty("sessions.dir", sessionsDir.absolutePath)
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
            // Before -javaagent only for the reader's sake; the JVM loads native agents first whatever the order.
            monitorProbe(agentJar, workDir)?.let { "-agentpath:${it.absolutePath}" },
            "-javaagent:${agentJar.absolutePath}=config=${configFile.absolutePath}",
            // The agent appends its runtime to the bootstrap class path, after which the JVM cannot use its shared
            // class archive for application classes and says so with a warning on every start. Nothing is lost by
            // turning sharing off up front, and the warning goes away.
            "-Xshare:off",
        )
    }

    /**
     * The agent jar carries its native monitor probe (what reports threads blocked on `synchronized`) for a number of
     * platforms. The agent can load it by itself, but only with `System.load`, which newer JDKs answer with a
     * native-access warning and promise to refuse one day; `-agentpath` is the way in that has no such strings
     * attached, and it takes a file. `null` when the jar has no probe for this machine: everything else still works.
     *
     * The JVM being started is assumed to be of the platform Gradle runs on. (On macOS the probe is a universal
     * binary, so an x64 JVM on Apple silicon is fine too.)
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
        /** `pace.events.per.second` for "no limit"; the agent reads it as that. */
        const val UNLIMITED = "unlimited"

        private val LOGGER = org.gradle.api.logging.Logging.getLogger(AgentArgumentProvider::class.java)
    }

    /**
     * Gradle asks for the arguments once per forked JVM, and a Test task may fork several at a time: while one
     * worker's agent reads these files, the next call is already rewriting them. Moved into place, a file is
     * always whole.
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
