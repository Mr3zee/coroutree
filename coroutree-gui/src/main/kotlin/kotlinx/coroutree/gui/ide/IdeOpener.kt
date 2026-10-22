package kotlinx.coroutree.gui.ide

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutree.model.tree.SourceLocation
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Opens a source location or a class in the IDE. The GUI has no source viewer.
 *
 * Without a [commandTemplate], the opener runs the launcher of a running JetBrains IDE ([RunningIdes]), which hands
 * the arguments to the running instance. A project file opens by path and line. A class outside the project sources
 * opens through the IDE's `jetbrains://` navigate command, which needs the traced project open in that IDE.
 * A [commandTemplate] replaces the launcher and opens project files only.
 */
class IdeOpener(
    private val commandTemplate: String? = null,
    private val runningIdes: () -> List<RunningIde> = RunningIdes::find,
    private val run: (List<String>) -> Run = ::runProcess,
) {
    sealed interface Target {
        data class Source(val location: SourceLocation) : Target

        /** A class with no file in the source index. [projectDir] is the root directory of the traced project. */
        data class Symbol(val className: String, val projectDir: String) : Target
    }

    sealed interface Outcome {
        /** [where] names the IDE, or the command when one is configured. */
        data class Opened(val where: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** What a command did: its exit code, or null when it still runs after [LAUNCH_WAIT_SECONDS], and its output. */
    data class Run(val exitCode: Int?, val output: String)

    suspend fun open(target: Target): Outcome = withContext(Dispatchers.IO) {
        try {
            when (target) {
                is Target.Source -> openSource(target.location)
                is Target.Symbol -> openSymbol(target)
            }
        } catch (e: Exception) {
            Outcome.Failed("Can't open ${target.label}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun openSource(location: SourceLocation): Outcome {
        val file = File(location.absolutePath)
        if (commandTemplate != null) {
            if (!file.isFile) return Outcome.Failed(noSuchFile(location))
            return launch(buildCommand(commandTemplate, location.absolutePath, location.line), commandTemplate.trim().substringBefore(' '))
        }
        // No IDE comes first: without one, a present file is no better than a missing one.
        val ide = RunningIdes.choose(runningIdes(), file) ?: return Outcome.Failed(NO_IDE)
        if (!file.isFile) return Outcome.Failed(noSuchFile(location))
        val command = buildList {
            add(ide.launcher.path)
            if (location.line > 0) {
                add("--line")
                add(location.line.toString())
            }
            add(location.absolutePath)
        }
        return launch(command, ide.title)
    }

    private fun openSymbol(target: Target.Symbol): Outcome {
        if (commandTemplate != null) {
            return Outcome.Failed("${target.className} is not in the project sources, and the configured IDE command opens files only")
        }
        val ides = runningIdes()
        if (ides.isEmpty()) return Outcome.Failed(NO_IDE)
        if (target.projectDir.isEmpty()) return Outcome.Failed("${target.className} is not in the project sources, and the trace names no project")
        val projectDir = File(target.projectDir)
        val ide = ides.firstOrNull { it.projectOf(projectDir) != null }
            ?: return Outcome.Failed(
                "${target.className} is not in the project sources. Open ${projectDir.path} in ${ides.joinToString(" or ") { it.title }} to go to it",
            )
        return launch(listOf(ide.launcher.path, navigateUrl(projectName(projectDir), target.className)), ide.title)
    }

    private fun launch(command: List<String>, where: String): Outcome {
        val result = run(command)
        val output = result.output.trim()
        if (result.exitCode != null && result.exitCode != 0) {
            return Outcome.Failed("`${command.joinToString(" ")}` exited with code ${result.exitCode}" + (if (output.isEmpty()) "" else ": $output"))
        }
        // The launcher reports a project it can't find on its output and exits with 0.
        if (PROJECT_NOT_FOUND in output) return Outcome.Failed("$output ($where)")
        return Outcome.Opened(where)
    }

    private fun noSuchFile(location: SourceLocation) = "No such file on this machine: ${location.absolutePath}"

    private val Target.label: String
        get() = when (this) {
            is Target.Source -> location.path
            is Target.Symbol -> className
        }

    companion object {
        const val NO_IDE: String = "No running JetBrains IDE found. Start one with the project open, or start the GUI with --ide <command>."
        const val LAUNCH_WAIT_SECONDS: Long = 5
        private const val PROJECT_NOT_FOUND = "Cannot find or open the specified project"

        /** The project name the IDE's navigate command matches: `.idea/.name` when set, the directory name otherwise. */
        fun projectName(projectDir: File): String =
            runCatching { File(projectDir, ".idea/.name").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: projectDir.name

        /**
         * [The navigate command](https://www.jetbrains.com/help/idea/protocol-handler.html) of the IDE's protocol
         * handler. The launcher takes the URL as an argument. The IDE opens the class in the open project named
         * [project], a library or JDK class included.
         */
        fun navigateUrl(project: String, className: String): String =
            "jetbrains://idea/navigate/reference?project=${encode(project)}&fqn=${encode(className)}"

        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        /**
         * Splits [template] into arguments the way a shell would (whitespace, single and double quotes) and then
         * substitutes `{path}` and `{line}`, so a path with spaces stays one argument whether or not it was quoted.
         */
        fun buildCommand(template: String, path: String, line: Int): List<String> {
            val arguments = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var inArgument = false
            for (c in template) {
                when {
                    quote != null -> if (c == quote) quote = null else current.append(c)
                    c == '"' || c == '\'' -> {
                        quote = c
                        inArgument = true
                    }
                    c.isWhitespace() -> {
                        if (inArgument) arguments += current.toString()
                        current.clear()
                        inArgument = false
                    }
                    else -> {
                        current.append(c)
                        inArgument = true
                    }
                }
            }
            require(quote == null) { "Unbalanced quote in IDE command: $template" }
            if (inArgument) arguments += current.toString()
            require(arguments.isNotEmpty()) { "Empty IDE command" }
            return arguments.map { it.replace("{path}", path).replace("{line}", line.coerceAtLeast(1).toString()) }
        }

        /**
         * Runs [command] and waits [LAUNCH_WAIT_SECONDS] at most. A launcher hands over to the running IDE and exits
         * at once. One that still runs after the wait is an IDE starting up, so its output isn't read: a child of
         * it can hold the pipe open.
         */
        fun runProcess(command: List<String>): Run {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val finished = process.waitFor(LAUNCH_WAIT_SECONDS, TimeUnit.SECONDS)
            val output = if (finished) process.inputStream.readNBytes(process.inputStream.available()).toString(Charsets.UTF_8) else ""
            return Run(if (finished) process.exitValue() else null, output)
        }
    }
}
