package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.demo.DemoTrace
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.TraceWriter
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.TraceStore
import java.io.ByteArrayOutputStream

fun snapshotOf(frames: List<Frame>): TraceSnapshot = TraceStore().apply {
    frames.forEach(::accept)
    endOfStream()
}.snapshot()

fun demoSnapshot(): TraceSnapshot = snapshotOf(DemoTrace.frames())

fun TraceSnapshot.named(name: String): NodeSnapshot = nodes.values.single { it.info.name == name }

fun TraceSnapshot.constructed(construct: String): NodeSnapshot = nodes.values.first { it.info.construct == construct }

fun traceBytes(frames: List<Frame>): ByteArray =
    ByteArrayOutputStream().also { out -> TraceWriter(out).use { writer -> frames.forEach(writer::write) } }.toByteArray()
