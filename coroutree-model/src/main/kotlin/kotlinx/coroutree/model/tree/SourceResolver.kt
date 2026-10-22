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
 * Maps stack frames to source files with the index in the trace header.
 * If a package and file name match several files, the first index entry wins. Main sources precede test sources.
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
        val entry = candidates.first()
        return SourceLocation(entry.module, entry.path, File(entry.rootDir, entry.path).path, frame.line)
    }

    public fun isKnown(frame: StackFrameDef): Boolean = resolve(frame) != null

    public companion object {
        public val EMPTY: SourceResolver = SourceResolver(SourceIndex())

        private fun key(packageName: String, fileName: String) = "$packageName/$fileName"
    }
}
