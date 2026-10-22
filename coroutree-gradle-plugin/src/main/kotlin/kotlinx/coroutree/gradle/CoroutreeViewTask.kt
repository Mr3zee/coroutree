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

/**
 * Starts the standalone GUI and returns immediately. The GUI continues after the build ends.
 * It lists traced JVMs from all builds.
 */
@UntrackedTask(because = "Launches an application")
public abstract class CoroutreeViewTask : DefaultTask() {
    @get:Classpath
    public abstract val guiClasspath: ConfigurableFileCollection

    /** `<root build dir>/coroutree`: traces, and the GUI's log. */
    @get:Internal
    public abstract val dataDirectory: Property<String>

    @get:Input
    @get:Optional
    public abstract val ideCommand: Property<String>

    /** Whether to print the command without execution. Set with `-Pcoroutree.view.dryRun`. Default is false. */
    @get:Input
    public abstract val dryRun: Property<Boolean>

    @get:Internal
    public abstract val buildIdService: Property<BuildService<*>>

    @TaskAction
    protected fun launch() {
        val files = guiClasspath.files
        if (files.isEmpty()) throw GradleException("The '${CoroutreePlugin.GUI_CONFIGURATION}' configuration is empty. Add the coroutree GUI to it.")
        val dataDir = File(dataDirectory.get())

        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "javaw.exe" else "java"
        val command = buildList {
            add(File(System.getProperty("java.home"), "bin/$executable").path)
            add("-cp")
            add(files.joinToString(File.pathSeparator) { it.absolutePath })
            add(CoroutreePlugin.GUI_MAIN_CLASS)
            ideCommand.orNull?.let {
                add("--ide")
                add(it)
            }
        }

        if (dryRun.get()) {
            // One argument on each line lets tests read arguments that contain spaces.
            logger.quiet("coroutreeView command line:")
            command.forEach { logger.quiet("  $it") }
            return
        }

        dataDir.mkdirs()
        // A root invocation can run this task in several projects. Only the first task starts a GUI.
        val buildId = BuildIdService.idOf(buildIdService.get())
        dataDir.listFiles { file -> file.name.startsWith(LAUNCH_MARKER_PREFIX) && file.name != LAUNCH_MARKER_PREFIX + buildId }?.forEach { it.delete() }
        if (!File(dataDir, LAUNCH_MARKER_PREFIX + buildId).createNewFile()) {
            logger.info("coroutree: another project of this build already started the GUI")
            return
        }
        val log = File(dataDir, "gui.log")
        ProcessBuilder(command)
            .directory(dataDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        logger.lifecycle("coroutree GUI started. Its output goes to ${log.absolutePath}")
    }

    private companion object {
        const val LAUNCH_MARKER_PREFIX = ".view-launched-"
    }
}
