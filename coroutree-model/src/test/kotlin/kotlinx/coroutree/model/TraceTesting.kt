package kotlinx.coroutree.model

import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.TraceStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

// What the tests of this module share: a protobuf encoder written from docs/TRACE_FORMAT.md alone (so that the bytes a
// test feeds the reader do not come from the code under test), and a way to write a trace down as a script.

/** Protobuf by hand: field numbers and wire types as the format document gives them. */
internal class Proto {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): Proto = apply {
        raw((field.toLong() shl 3) or 0)
        raw(value)
    }

    fun varint(field: Int, value: Int): Proto = varint(field, value.toLong())

    fun bytes(field: Int, value: ByteArray): Proto = apply {
        raw((field.toLong() shl 3) or 2)
        raw(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String): Proto = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, body: Proto.() -> Unit): Proto = bytes(field, proto(body))

    /** `repeated int32 … [packed = true]`. */
    fun packed(field: Int, vararg values: Int): Proto = bytes(field, Proto().apply { values.forEach { raw(it.toLong()) } }.toByteArray())

    fun fixed64(field: Int, value: Long): Proto = apply {
        raw((field.toLong() shl 3) or 1)
        for (i in 0 until 8) out.write((value ushr (8 * i)).toInt() and 0xFF)
    }

    fun fixed32(field: Int, value: Int): Proto = apply {
        raw((field.toLong() shl 3) or 5)
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF)
    }

    private fun raw(value: Long) {
        var rest = value
        while (rest and 0x7F.inv() != 0L) {
            out.write((rest and 0x7F).toInt() or 0x80)
            rest = rest ushr 7
        }
        out.write(rest.toInt())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal fun proto(body: Proto.() -> Unit): ByteArray = Proto().apply(body).toByteArray()

internal fun lengthVarint(length: Int): ByteArray {
    val out = ByteArrayOutputStream()
    var rest = length
    while (rest and 0x7F.inv() != 0) {
        out.write(rest and 0x7F or 0x80)
        rest = rest ushr 7
    }
    out.write(rest)
    return out.toByteArray()
}

/** A trace as the layout section describes it: the magic, then each message behind its length. */
internal fun traceBytes(vararg messages: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("COROTREE".toByteArray(Charsets.US_ASCII))
    for (message in messages) {
        out.write(lengthVarint(message.size))
        out.write(message)
    }
    return out.toByteArray()
}

internal fun written(frames: List<Frame>): ByteArray =
    ByteArrayOutputStream().also { out -> TraceWriter(out).use { writer -> frames.forEach(writer::write) } }.toByteArray()

internal fun framesOf(bytes: ByteArray): List<Frame> = TraceReader(ByteArrayInputStream(bytes)).use { it.frames().toList() }

/** A trace written down event by event; sequence numbers are handed out in the order of the script. */
internal class TraceScript {
    private var seq = 0L
    val frames = mutableListOf<Frame>()

    fun header(header: TraceHeader) {
        frames += Frame(header = header)
    }

    fun frame(id: Int, className: String, method: String = "run", file: String = "", line: Int = 0, inlined: Boolean = false) {
        frames += Frame(stackFrame = StackFrameDef(id, className, method, file, line, inlined))
    }

    fun event(node: Long, kind: EventKind, other: Long = 0, thread: Long = 0, configure: Event.() -> Event = { this }) {
        frames += Frame(event = Event(seq = ++seq, nodeId = node, kind = kind, otherNodeId = other, threadId = thread).configure())
    }

    fun launched(
        id: Long,
        parent: Long = 0,
        construct: String = "launch",
        name: String = "",
        kind: NodeKind = NodeKind.COROUTINE,
        creator: Long = 0,
        site: Int = 0,
    ) {
        event(id, EventKind.LAUNCHED) {
            copy(node = NodeInfo(id = id, kind = kind, construct = construct, name = name, parentId = parent, creatorId = creator, siteFrame = site))
        }
    }

    /** A thread that was there before anybody looked. */
    fun thread(id: Long, name: String, parent: Long = 0) {
        event(id, EventKind.DISCOVERED) { copy(node = NodeInfo(id = id, kind = NodeKind.THREAD, name = name, parentId = parent)) }
    }

    fun finished(node: Long, state: NodeState) {
        event(node, EventKind.FINISHED) { copy(finalState = state) }
    }

    /** A sequence number that was handed out and never reached the trace. */
    fun lost() {
        seq++
    }
}

internal fun script(body: TraceScript.() -> Unit): List<Frame> = TraceScript().apply(body).frames

internal fun storeOf(frames: List<Frame>): TraceStore = TraceStore().apply { frames.forEach(::accept) }

internal fun snapshotOf(body: TraceScript.() -> Unit): TraceSnapshot = storeOf(script(body)).snapshot()
