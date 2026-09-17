package kotlinx.coroutree.gui.ide

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutree.model.tree.SourceLocation
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Opens a source location in the IDE. There is deliberately no source viewer in the GUI.
 *
 * Without configuration: ask a running IntelliJ-based IDE through its built-in web server, and if none answers,
 * run the `idea` command-line launcher. A [commandTemplate] replaces both.
 */
class IdeOpener(private val commandTemplate: String? = null) {
    sealed interface Outcome {
        data object Opened : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun open(location: SourceLocation): Outcome = withContext(Dispatchers.IO) {
        if (!File(location.absolutePath).isFile) return@withContext Outcome.Failed("No such file here: ${location.absolutePath}")
        try {
            if (commandTemplate != null) {
                run(buildCommand(commandTemplate, location.absolutePath, location.line))
            } else if (askBuiltInServer(location)) {
                Outcome.Opened
            } else {
                run(buildCommand(DEFAULT_COMMAND, location.absolutePath, location.line))
            }
        } catch (e: Exception) {
            Outcome.Failed("Could not open ${location.path}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun askBuiltInServer(location: SourceLocation): Boolean = try {
        val request = HttpRequest.newBuilder(builtInServerUri(location.absolutePath, location.line))
            .timeout(Duration.ofSeconds(2)).GET().build()
        httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
    } catch (_: Exception) {
        false
    }

    private fun run(command: List<String>): Outcome {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        // Launchers hand over to the running IDE and exit at once; one that is still running after a moment
        // is an IDE starting up, which is a success too.
        if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() != 0) {
            return Outcome.Failed("`${command.joinToString(" ")}` exited with code ${process.exitValue()}")
        }
        return Outcome.Opened
    }

    companion object {
        const val DEFAULT_COMMAND: String = "idea --line {line} {path}"
        const val BUILT_IN_SERVER_PORT: Int = 63342

        private val httpClient: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build() }

        /** IntelliJ's `OpenFileHttpService`. The query form, unlike `/api/file/<path>:<line>`, survives any path. */
        fun builtInServerUri(absolutePath: String, line: Int): URI {
            val file = URLEncoder.encode(absolutePath, Charsets.UTF_8).replace("+", "%20")
            val lineParameter = if (line > 0) "&line=$line" else ""
            return URI("http://localhost:$BUILT_IN_SERVER_PORT/api/file?file=$file$lineParameter")
        }

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
    }
}
