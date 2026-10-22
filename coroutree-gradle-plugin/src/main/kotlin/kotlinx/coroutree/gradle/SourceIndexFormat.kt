package kotlinx.coroutree.gradle

import java.io.File

/**
 * The text form of the source index. The agent's `SourceIndexFile` parses it into the trace header. UTF-8, one
 * tab-separated record on each line:
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

    /** Concatenates [parts] in order. A module reached through two dependency paths is included once. */
    fun merge(parts: Iterable<File>): List<String> {
        val result = ArrayList<String>()
        val modules = HashSet<String>()
        var keep = false
        for (part in parts) {
            if (!part.isFile) continue
            part.forEachLine(Charsets.UTF_8) { line ->
                when {
                    line.startsWith("M\t") -> {
                        // The directory is part of the key: two builds of a composite can both have an `:app`.
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
     * The shortest list of prefixes that covers [packages]: `a.b` goes when `a` is listed. The root package is never
     * listed, because as a prefix it matches every library class.
     */
    fun minimalPrefixes(packages: Collection<String>): List<String> {
        val sorted = packages.filter { it.isNotEmpty() }.toSortedSet()
        return sorted.filter { candidate -> sorted.none { it != candidate && candidate.startsWith("$it.") } }
    }

    private fun clean(value: String): String = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
