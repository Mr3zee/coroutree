package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.demo.DemoTrace
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.NodeInfo
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.TraceWriter
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.TraceStore
import java.io.ByteArrayOutputStream
import kotlin.random.Random

fun snapshotOf(frames: List<Frame>): TraceSnapshot = TraceStore().apply {
    frames.forEach(::accept)
    endOfStream()
}.snapshot()

fun demoSnapshot(): TraceSnapshot = snapshotOf(DemoTrace.frames())

fun TraceSnapshot.named(name: String): NodeSnapshot = nodes.values.single { it.info.name == name }

fun TraceSnapshot.constructed(construct: String): NodeSnapshot = nodes.values.first { it.info.construct == construct }

fun traceBytes(frames: List<Frame>): ByteArray =
    ByteArrayOutputStream().also { out -> TraceWriter(out).use { writer -> frames.forEach(writer::write) } }.toByteArray()

/** A node definition as the agent would send it, for traces made by hand. */
class TraceScript {
    val frames = ArrayList<Frame>()
    private var seq = 0L

    fun node(id: Long, parent: Long, origin: Origin = Origin.PROJECT, kind: NodeKind = NodeKind.COROUTINE, creator: Long = 0, name: String = "", construct: String = "launch") {
        val info = NodeInfo(id = id, kind = kind, construct = construct, name = name, parentId = parent, creatorId = creator, origin = origin)
        frames += Frame(event = Event(seq = ++seq, timeNanos = seq * 1000, nodeId = id, kind = EventKind.LAUNCHED, node = info))
    }

    fun event(id: Long, kind: EventKind, other: Long = 0, thread: Long = 0, state: NodeState = NodeState.UNSPECIFIED) {
        frames += Frame(event = Event(seq = ++seq, timeNanos = seq * 1000, nodeId = id, kind = kind, otherNodeId = other, threadId = thread, finalState = state))
    }
}

fun trace(script: TraceScript.() -> Unit): List<Frame> = TraceScript().apply(script).frames

/** A seeded random trace: a bushy forest in which some nodes were launched from elsewhere, cancelled by others, or have finished. */
fun largeTrace(nodes: Int, links: Int, seed: Int): List<Frame> = trace {
    val random = Random(seed)
    node(1, 0, kind = NodeKind.THREAD, name = "main", construct = "Thread")
    for (id in 2L..nodes) {
        val parent = if (random.nextInt(30) == 0) 0L else 1L + random.nextInt((id - 1).toInt().coerceAtMost(1 + id.toInt() / 3))
        val creator = if (parent == 0L) 1L + random.nextInt((id - 1).toInt()) else 0L
        node(id, parent, creator = creator, name = if (random.nextInt(3) == 0) "job-$id" else "", kind = if (random.nextInt(5) == 0) NodeKind.SCOPE else NodeKind.COROUTINE, construct = if (random.nextBoolean()) "launch" else "async")
    }
    repeat(links) {
        val target = 2L + random.nextInt(nodes - 1)
        event(target, EventKind.CANCELLATION_REQUESTED, other = 1L + random.nextInt(nodes))
    }
    for (id in 2L..nodes) when (random.nextInt(6)) {
        0 -> event(id, EventKind.FINISHED, state = NodeState.COMPLETED)
        1 -> event(id, EventKind.FINISHED, state = NodeState.FAILED)
        2 -> event(id, EventKind.SUSPENDED)
        3 -> event(id, EventKind.FINISHED, state = NodeState.CANCELLED)
    }
}
