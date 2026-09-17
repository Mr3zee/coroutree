package kotlinx.coroutree.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File

/** Starts the GUI on what the build has recorded so far and returns; the GUI keeps running after the build. */
@UntrackedTask(because = "Launches an application")
public abstract class CoroutreeViewTask : DefaultTask() {
    @get:Classpath
    public abstract val guiClasspath: ConfigurableFileCollection

    /** `<root build dir>/coroutree`: traces, live sessions, and the GUI's log. */
    @get:Internal
    public abstract val dataDirectory: Property<String>

    @get:Input
    @get:Optional
    public abstract val ideCommand: Property<String>

    /** Print the command line instead of running it. `-Pcoroutree.view.dryRun` */
    @get:Input
    public abstract val dryRun: Property<Boolean>

    /** See [AgentArgumentProvider.buildIdService] for the loose type. */
    @get:Internal
    public abstract val buildIdService: Property<BuildService<*>>

    @TaskAction
    protected fun launch() {
        val files = guiClasspath.files
        if (files.isEmpty()) throw GradleException("The '${CoroutreePlugin.GUI_CONFIGURATION}' configuration is empty: nothing to launch")
        val dataDir = File(dataDirectory.get())

        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "javaw.exe" else "java"
        val command = buildList {
            add(File(System.getProperty("java.home"), "bin/$executable").path)
            add("-cp")
            add(files.joinToString(File.pathSeparator) { it.absolutePath })
            add(CoroutreePlugin.GUI_MAIN_CLASS)
            add("--dir")
            add(dataDir.absolutePath)
            add("--open-latest")
            ideCommand.orNull?.let {
                add("--ide")
                add(it)
            }
        }

        if (dryRun.get()) {
            // One argument per line: arguments contain spaces, and tests want them back intact.
            logger.quiet("coroutreeView command line:")
            command.forEach { logger.quiet("  $it") }
            return
        }

        dataDir.mkdirs()
        // Every project that applies the plugin has this task, so `gradlew coroutreeView` from the root runs them all.
        // They all mean the same GUI on the same directory: the first one of an invocation opens it.
        val buildId = BuildIdService.idOf(buildIdService.get())
        dataDir.listFiles { file -> file.name.startsWith(LAUNCH_MARKER_PREFIX) && file.name != LAUNCH_MARKER_PREFIX + buildId }?.forEach { it.delete() }
        if (!File(dataDir, LAUNCH_MARKER_PREFIX + buildId).createNewFile()) {
            logger.info("coroutree GUI was already started by another project of this build")
            return
        }
        val log = File(dataDir, "gui.log")
        ProcessBuilder(command)
            .directory(dataDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        logger.lifecycle("coroutree GUI started; its output goes to ${log.absolutePath}")
    }

    private companion object {
        const val LAUNCH_MARKER_PREFIX = ".view-launched-"
    }
}
