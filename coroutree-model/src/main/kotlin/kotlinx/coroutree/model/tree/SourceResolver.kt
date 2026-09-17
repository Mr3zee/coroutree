package kotlinx.coroutree.model.tree

import kotlinx.coroutree.model.SourceIndex
import kotlinx.coroutree.model.StackFrameDef
import java.io.File

public data class SourceLocation(
    /** Gradle project path of the owning module. */
    val module: String,
    /** Path relative to the module directory. */
    val path: String,
    val absolutePath: String,
    /** 1-based, 0 when unknown. */
    val line: Int,
)

/**
 * Maps stack frames to source files with the index embedded in the trace header.
 *
 * A frame knows its class and the bare name of its source file. Kotlin and Java both put a class into the package
 * its file declares, so `(package of the class, file name)` identifies the file.
 */
public class SourceResolver(index: SourceIndex) {
    private class Entry(val module: String, val rootDir: String, val path: String)

    private val entries: Map<String, List<Entry>> = buildMap<String, MutableList<Entry>> {
        for (module in index.modules) {
            for (file in module.files) {
                getOrPut(key(file.packageName, file.fileName)) { mutableListOf() }
                    .add(Entry(module.path, module.rootDir, file.path))
            }
        }
    }

    public fun resolve(frame: StackFrameDef): SourceLocation? {
        if (frame.fileName.isEmpty()) return null
        val candidates = entries[key(frame.className.substringBeforeLast('.', ""), frame.fileName)] ?: return null
        // Several source sets may hold a file with the same package and name (main and test, or two modules).
        // The frame cannot tell them apart; take the first in index order, which lists main before test.
        val entry = candidates.first()
        return SourceLocation(entry.module, entry.path, File(entry.rootDir, entry.path).path, frame.line)
    }

    public fun isKnown(frame: StackFrameDef): Boolean = resolve(frame) != null

    public companion object {
        public val EMPTY: SourceResolver = SourceResolver(SourceIndex())

        private fun key(packageName: String, fileName: String) = "$packageName/$fileName"
    }
}
