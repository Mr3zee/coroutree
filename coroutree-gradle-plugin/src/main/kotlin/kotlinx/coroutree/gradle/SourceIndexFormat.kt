package kotlinx.coroutree.gradle

import java.io.File

/**
 * The text form of the source index handed to the agent, which embeds it into the trace header.
 *
 * UTF-8, one tab-separated record per line:
 * ```
 * M <gradle project path> <absolute project dir>                      starts a module
 * F <package, may be empty> <file name> <path relative to the dir>    a source file of the last module
 * ```
 * Indexes of several modules merge by concatenation.
 */
internal object SourceIndexFormat {
    fun moduleRecord(projectPath: String, projectDir: String): String = "M\t${clean(projectPath)}\t${clean(projectDir)}"

    fun fileRecord(packageName: String, fileName: String, relativePath: String): String =
        "F\t${clean(packageName)}\t${clean(fileName)}\t${clean(relativePath)}"

    /** Concatenates [parts] in order; a module that occurs again (reached through two dependency paths) is dropped. */
    fun merge(parts: Iterable<File>): List<String> {
        val result = ArrayList<String>()
        val modules = HashSet<String>()
        var keep = false
        for (part in parts) {
            if (!part.isFile) continue
            part.forEachLine(Charsets.UTF_8) { line ->
                when {
                    line.startsWith("M\t") -> {
                        // The whole record, path and directory: two builds of a composite may both have an ":app".
                        keep = modules.add(line)
                        if (keep) result.add(line)
                    }
                    line.startsWith("F\t") -> if (keep) result.add(line)
                }
            }
        }
        return result
    }

    fun packages(index: List<String>): Set<String> =
        index.asSequence().filter { it.startsWith("F\t") }.map { it.split('\t').getOrElse(1) { "" } }.toSet()

    /**
     * The shortest list of prefixes that covers [packages]: `a.b` goes when `a` is listed too.
     * The root package is never listed — as a prefix it would claim every class of every library.
     */
    fun minimalPrefixes(packages: Collection<String>): List<String> {
        val sorted = packages.filter { it.isNotEmpty() }.toSortedSet()
        return sorted.filter { candidate -> sorted.none { it != candidate && candidate.startsWith("$it.") } }
    }

    // Tabs and line breaks cannot occur in package names and are absurd in paths; a record must stay one line.
    private fun clean(value: String): String = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
