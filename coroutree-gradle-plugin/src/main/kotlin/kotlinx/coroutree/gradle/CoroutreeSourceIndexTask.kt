package kotlinx.coroutree.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Writes the module's part of the source index: which file declares which package.
 * A stack frame knows its class and a bare file name; this is what turns the pair into a path the IDE can open.
 */
@CacheableTask
public abstract class CoroutreeSourceIndexTask : DefaultTask() {
    /** Source directories of main-like source sets. Listed first, so that they win when a test file has the same name. */
    @get:Internal
    public abstract val mainSourceDirectories: ConfigurableFileCollection

    @get:Internal
    public abstract val testSourceDirectories: ConfigurableFileCollection

    @get:Input
    public abstract val projectPath: Property<String>

    /** Absolute, and part of the output: traces are opened on the machine that recorded them. */
    @get:Input
    public abstract val projectDirectory: Property<String>

    @get:OutputFile
    public abstract val indexFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    protected val sourceFiles: FileTree
        get() = (mainSourceDirectories + testSourceDirectories).asFileTree.matching { it.include("**/*.kt", "**/*.java") }

    /** The order of the directories decides the order of the records, and [sourceFiles] does not capture it. */
    @get:Input
    protected val sourceDirectoryOrder: List<String>
        get() {
            val projectDir = File(projectDirectory.get())
            return (mainSourceDirectories.files + testSourceDirectories.files).map { it.relativeToOrSelf(projectDir).invariantSeparatorsPath }
        }

    @TaskAction
    protected fun writeIndex() {
        val projectDir = File(projectDirectory.get())
        val lines = ArrayList<String>()
        lines += SourceIndexFormat.moduleRecord(projectPath.get(), projectDir.path)
        val seen = HashSet<File>()
        for (directory in mainSourceDirectories.files + testSourceDirectories.files) {
            if (!directory.isDirectory) continue
            directory.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                .sortedBy { it.path }
                .forEach { file ->
                    if (!seen.add(file)) return@forEach // nested source directories
                    val path = file.relativeToOrSelf(projectDir).invariantSeparatorsPath
                    lines += SourceIndexFormat.fileRecord(PackageScanner.packageOf(file), file.name, path)
                }
        }
        indexFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(lines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        }
    }
}
