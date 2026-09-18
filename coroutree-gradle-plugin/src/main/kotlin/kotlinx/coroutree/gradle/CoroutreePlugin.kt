package kotlinx.coroutree.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.process.JavaForkOptions
import java.util.Properties

public class CoroutreePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(EXTENSION, CoroutreeExtension::class.java)
        extension.enabled.convention(false)
        extension.stackDepth.convention(32)
        extension.live.enabled.convention(true)
        extension.pace.enabled.convention(true)
        extension.pace.startPaused.convention(false)

        val agent = project.configurations.create(AGENT_CONFIGURATION) { configuration ->
            configuration.description = "The coroutree agent jar attached to forked JVMs."
            configuration.isCanBeConsumed = false
            configuration.isTransitive = false // the agent is self-contained
            configuration.attributes { it.javaRuntime(project) }
            configuration.defaultDependencies {
                it.add(project.dependencies.create("${BuildInfo.group}:coroutree-agent:${BuildInfo.version}"))
            }
        }
        val gui = project.configurations.create(GUI_CONFIGURATION) { configuration ->
            configuration.description = "The coroutree GUI launched by $VIEW_TASK."
            configuration.isCanBeConsumed = false
            configuration.attributes { it.javaRuntime(project) }
            configuration.defaultDependencies {
                it.add(project.dependencies.create("${BuildInfo.group}:coroutree-gui:${BuildInfo.version}:${hostClassifier()}"))
            }
        }

        val indexTask = registerSourceIndex(project)
        val dependencyIndexes = dependencyIndexes(project)
        val dataDirectory = project.rootProject.layout.buildDirectory.dir("coroutree").map { it.asFile.absolutePath }

        project.tasks.withType(JavaExec::class.java).configureEach { attachAgent(project, it, it, extension, agent, indexTask, dependencyIndexes, dataDirectory) }
        project.tasks.withType(Test::class.java).configureEach { attachAgent(project, it, it, extension, agent, indexTask, dependencyIndexes, dataDirectory) }

        project.tasks.register(VIEW_TASK, CoroutreeViewTask::class.java) { task ->
            task.group = TASK_GROUP
            task.description = "Opens the coroutree GUI on the latest trace or live session of this build."
            task.guiClasspath.from(gui)
            task.dataDirectory.set(dataDirectory)
            task.ideCommand.set(extension.ideCommand)
            task.dryRun.set(project.providers.gradleProperty("coroutree.view.dryRun").map { it != "false" }.orElse(false))
            val buildId = project.gradle.sharedServices.registerIfAbsent(BuildIdService.NAME, BuildIdService::class.java) {}
            task.buildIdService.set(buildId)
            task.usesService(buildId)
        }
    }

    private fun attachAgent(
        project: Project,
        task: Task,
        forkOptions: JavaForkOptions,
        extension: CoroutreeExtension,
        agent: Configuration,
        indexTask: TaskProvider<CoroutreeSourceIndexTask>,
        dependencyIndexes: FileCollection,
        dataDirectory: Provider<String>,
    ) {
        // What the command line says wins over what the build script says, see CommandLine.
        val commandLine = CommandLine(project.providers)
        val enabled = commandLine.enabled(extension.enabled)
        val buildId = project.gradle.sharedServices.registerIfAbsent(BuildIdService.NAME, BuildIdService::class.java) {}

        val arguments = project.objects.newInstance(AgentArgumentProvider::class.java)
        arguments.enabled.set(enabled)
        // Nothing is resolved and no index is built unless the agent is going to be attached.
        arguments.agentClasspath.from(enabled.map<Any> { if (it) agent else emptyList<Any>() })
        arguments.sourceIndexParts.from(enabled.map<Any> { if (it) listOf(indexTask.flatMap { index -> index.indexFile }, dependencyIndexes) else emptyList<Any>() })
        // Gradle does not reliably find the producers of files that hide behind a provider like the two above
        // (an included build's agent jar was not built, the index task was dropped), so they are named outright.
        task.dependsOn(enabled.map<Any> { if (it) listOf(agent, indexTask, dependencyIndexes) else emptyList<Any>() })
        arguments.live.set(commandLine.boolean(CommandLine.LIVE, extension.live.enabled))
        arguments.pace.set(commandLine.boolean(CommandLine.PACE, extension.pace.enabled))
        arguments.paceStartPaused.set(commandLine.boolean(CommandLine.START_PAUSED, extension.pace.startPaused))
        arguments.paceEventsPerSecond.set(commandLine.eventsPerSecond(extension.pace.eventsPerSecond))
        arguments.stackDepth.set(extension.stackDepth)
        arguments.includedPackages.set(extension.includedPackages)
        arguments.excludedPackages.set(extension.excludedPackages)
        arguments.taskPath.set(task.path)
        arguments.rootProjectDirectory.set(project.rootDir.absolutePath)
        arguments.dataDirectory.set(dataDirectory)
        arguments.workDirectory.set(project.layout.buildDirectory.dir("coroutree/tmp/${task.name}").map { it.asFile.absolutePath })
        arguments.buildIdService.set(buildId)

        forkOptions.jvmArgumentProviders.add(arguments)
        task.usesService(buildId)
        // A run with the agent exists for its trace: test results being up to date is no reason to skip it.
        task.outputs.upToDateWhen { !enabled.get() }
        task.outputs.doNotCacheIf("The coroutree agent is attached") { enabled.get() }
    }

    private fun registerSourceIndex(project: Project): TaskProvider<CoroutreeSourceIndexTask> {
        val indexTask = project.tasks.register(SOURCE_INDEX_TASK, CoroutreeSourceIndexTask::class.java) { task ->
            task.group = TASK_GROUP
            task.description = "Indexes the JVM sources of this project by package and file name."
            task.projectPath.set(project.path)
            task.projectDirectory.set(project.projectDir.absolutePath)
            task.indexFile.set(project.layout.buildDirectory.file("coroutree/source-index.tsv"))
        }

        fun collect(sourceSetName: String, directories: FileCollection) = indexTask.configure { task ->
            val target = if (sourceSetName.contains("test", ignoreCase = true)) task.testSourceDirectories else task.mainSourceDirectories
            target.from(directories)
        }

        project.plugins.withId("java-base") {
            project.extensions.getByType(SourceSetContainer::class.java).all { collect(it.name, it.java.sourceDirectories) }
        }
        for (kotlinPlugin in listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.multiplatform")) {
            project.plugins.withId(kotlinPlugin) {
                try {
                    KotlinSourceSets.forEachJvmSourceSet(project, ::collect)
                } catch (e: LinkageError) {
                    // The Kotlin Gradle plugin lives in a class loader this plugin cannot see,
                    // which happens when it is applied to a child project only but this plugin comes from the parent.
                    project.logger.warn(
                        "coroutree: cannot read the Kotlin source sets of ${project.path} ($e); Kotlin sources will not open from the GUI. " +
                            "Declare the Kotlin Gradle plugin in the same plugins { } block as coroutree, with 'apply false' if need be."
                    )
                }
            }
        }

        project.configurations.create(SOURCE_INDEX_ELEMENTS) { configuration ->
            configuration.description = "The source index of this project, for the projects that depend on it."
            configuration.isCanBeResolved = false
            configuration.attributes { it.sourceIndex(project) }
            configuration.outgoing.artifact(indexTask.flatMap { it.indexFile })
        }
        return indexTask
    }

    /**
     * Source indexes of the projects on the runtime classpath, found the way `jacoco-report-aggregation` finds
     * coverage data: re-select a different variant of every project component, skipping those that have none.
     */
    private fun dependencyIndexes(project: Project): FileCollection = project.files(project.provider {
        val classpath = RUNTIME_CLASSPATHS.firstNotNullOfOrNull(project.configurations::findByName)
        classpath?.incoming?.artifactView { view ->
            view.withVariantReselection()
            view.lenient(true)
            view.componentFilter { it is ProjectComponentIdentifier }
            view.attributes { it.sourceIndex(project) }
        }?.files ?: project.files()
    })

    private fun AttributeContainer.sourceIndex(project: Project) {
        attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, SOURCE_INDEX_CATEGORY))
    }

    private fun AttributeContainer.javaRuntime(project: Project) {
        val objects = project.objects
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
        // Tells the JVM variant of a multiplatform library from its Android one.
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM))
    }

    internal object BuildInfo {
        private val properties = Properties().apply {
            val resource = CoroutreePlugin::class.java.getResourceAsStream("plugin.properties")
                ?: error("plugin.properties is missing from the coroutree Gradle plugin jar")
            resource.use(::load)
        }
        val version: String get() = properties.getProperty("version")
        val group: String get() = properties.getProperty("group")
    }

    public companion object {
        public const val EXTENSION: String = "coroutree"
        public const val AGENT_CONFIGURATION: String = "coroutreeAgent"
        public const val GUI_CONFIGURATION: String = "coroutreeGui"
        public const val SOURCE_INDEX_ELEMENTS: String = "coroutreeSourceIndexElements"
        public const val SOURCE_INDEX_CATEGORY: String = "coroutree-source-index"
        public const val SOURCE_INDEX_TASK: String = "coroutreeSourceIndex"
        public const val VIEW_TASK: String = "coroutreeView"
        public const val TASK_GROUP: String = "coroutree"
        internal const val GUI_MAIN_CLASS: String = "kotlinx.coroutree.gui.MainKt"

        // The test classpath is a superset of the main one, so it serves JavaExec and Test tasks alike.
        private val RUNTIME_CLASSPATHS = listOf("testRuntimeClasspath", "jvmTestRuntimeClasspath", "runtimeClasspath", "jvmRuntimeClasspath")

        internal fun hostClassifier(
            osName: String = System.getProperty("os.name"),
            osArch: String = System.getProperty("os.arch"),
        ): String {
            val os = osName.lowercase()
            val arch = when (osArch.lowercase()) {
                "aarch64", "arm64" -> "arm64"
                "x86_64", "amd64" -> "x64"
                else -> null
            }
            val classifier = when {
                arch == null -> null
                os.startsWith("mac") || os.startsWith("darwin") -> "macos-$arch"
                os.startsWith("linux") -> "linux-$arch"
                os.startsWith("windows") && arch == "x64" -> "windows-x64"
                else -> null
            }
            return classifier ?: throw GradleException(
                "The coroutree GUI is not published for $osName/$osArch. Point the '$GUI_CONFIGURATION' configuration at a build of it."
            )
        }
    }
}
