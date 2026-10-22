package kotlinx.coroutree.runtime

import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.TraceReader
import java.io.File
import java.io.InputStream

/** The trace the agent wrote, read with coroutree-model, the other implementation of the format. */
internal object TraceFiles {
    /** Every complete frame the file has now. A file that is still written ends in the middle of a frame, and the read ends there. */
    fun read(file: File): List<Frame> = file.inputStream().use { read(it) }

    /** Every frame up to the end of the stream. */
    fun read(input: InputStream): List<Frame> {
        val reader = TraceReader(input)
        return generateSequence { reader.next() }.toList()
    }
}
