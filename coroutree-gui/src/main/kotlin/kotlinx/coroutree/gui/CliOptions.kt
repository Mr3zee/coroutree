package kotlinx.coroutree.gui

import java.io.File

/** What the command line asked for. Anything not given stays `null`/`false`; with nothing given the start screen opens. */
data class CliOptions(
    val trace: File? = null,
    val session: File? = null,
    /** The `build/coroutree` directory of a build, with its `traces` and `sessions` subdirectories. */
    val dir: File? = null,
    val openLatest: Boolean = false,
    /** Command template with `{path}` and `{line}`; replaces the built-in ways of reaching the IDE. */
    val ideCommand: String? = null,
    val demo: DemoMode? = null,
) {
    enum class DemoMode { INSTANT, LIVE }

    class UsageException(message: String) : IllegalArgumentException(message)

    companion object {
        val USAGE: String = """
            Usage: coroutree-gui [options]
              --trace <file.ctrace>   open a recorded trace
              --session <file.json>   attach to a live session (falls back to its trace file)
              --dir <dir>             a build's coroutree directory (build/coroutree) to pick traces and sessions from
              --open-latest           with --dir: open the newest live session, or else the newest trace
              --ide <command>         command that opens a file in the IDE; {path} and {line} are substituted
              --demo[=live]           show a synthetic trace (=live streams it slowly)
        """.trimIndent()

        fun parse(args: List<String>): CliOptions {
            var options = CliOptions()
            val iterator = args.iterator()
            fun value(option: String): String {
                if (!iterator.hasNext()) throw UsageException("$option needs a value")
                return iterator.next()
            }
            while (iterator.hasNext()) {
                when (val arg = iterator.next()) {
                    "--trace" -> options = options.copy(trace = File(value(arg)))
                    "--session" -> options = options.copy(session = File(value(arg)))
                    "--dir" -> options = options.copy(dir = File(value(arg)))
                    "--open-latest" -> options = options.copy(openLatest = true)
                    "--ide" -> options = options.copy(ideCommand = value(arg))
                    "--demo" -> options = options.copy(demo = DemoMode.INSTANT)
                    "--demo=live" -> options = options.copy(demo = DemoMode.LIVE)
                    else ->
                        // A bare path is what a file association or a drag onto the jar passes.
                        if (!arg.startsWith("--") && options.trace == null) options = options.copy(trace = File(arg))
                        else throw UsageException("Unknown option: $arg")
                }
            }
            if (options.openLatest && options.dir == null) throw UsageException("--open-latest needs --dir")
            return options
        }
    }
}
