package kotlinx.coroutree.model

import kotlinx.serialization.protobuf.ProtoBuf
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Byte-level layout shared by trace files and the live stream:
 * the 8 bytes of [MAGIC], then frames, each a base-128 varint length followed by that many bytes of a [Frame] message.
 */
public object TraceFormat {
    public val MAGIC: ByteArray = "COROTREE".toByteArray(Charsets.US_ASCII)

    public const val FILE_EXTENSION: String = "ctrace"

    /** Upper bound for a single frame; anything larger means the stream is corrupt. */
    public const val MAX_FRAME_SIZE: Int = 64 * 1024 * 1024

    internal val protoBuf: ProtoBuf = ProtoBuf
}

public class TraceFormatException(message: String) : IOException(message)

public class TraceWriter(private val out: OutputStream) : Closeable {
    init {
        out.write(TraceFormat.MAGIC)
    }

    public fun write(frame: Frame) {
        val bytes = TraceFormat.protoBuf.encodeToByteArray(Frame.serializer(), frame)
        var length = bytes.size
        while (length and 0x7F.inv() != 0) {
            out.write(length and 0x7F or 0x80)
            length = length ushr 7
        }
        out.write(length)
        out.write(bytes)
    }

    public fun flush(): Unit = out.flush()

    override fun close(): Unit = out.close()
}

/**
 * Reads frames until the stream ends. A trace is append-only and its producer may die at any moment, so a frame
 * that is cut short at the end of the stream ends the trace normally instead of failing it.
 */
public class TraceReader(input: InputStream) : Closeable {
    private val input = DataInputStream(input as? BufferedInputStream ?: BufferedInputStream(input))

    init {
        val magic = ByteArray(TraceFormat.MAGIC.size)
        try {
            this.input.readFully(magic)
        } catch (_: EOFException) {
            throw TraceFormatException("Not a coroutree trace: shorter than the file signature")
        }
        if (!magic.contentEquals(TraceFormat.MAGIC)) throw TraceFormatException("Not a coroutree trace: bad file signature")
    }

    /** The next frame, or `null` at the end of the stream. Blocks on a live stream. */
    public fun next(): Frame? {
        val length = readLength() ?: return null
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
        } catch (_: EOFException) {
            return null
        }
        return TraceFormat.protoBuf.decodeFromByteArray(Frame.serializer(), bytes)
    }

    public fun frames(): Sequence<Frame> = generateSequence { next() }

    private fun readLength(): Int? {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (shift > 28) throw TraceFormatException("Corrupt trace: frame length varint is too long")
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (result < 0 || result > TraceFormat.MAX_FRAME_SIZE) throw TraceFormatException("Corrupt trace: frame of $result bytes")
        return result
    }

    override fun close(): Unit = input.close()
}
