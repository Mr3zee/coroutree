package kotlinx.coroutree.runtime

import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.ContextChange
import kotlinx.coroutree.model.ContextElement
import kotlinx.coroutree.model.ContextElementKind
import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.ExceptionInfo
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.HandledBy
import kotlinx.coroutree.model.JvmInfo
import kotlinx.coroutree.model.NodeInfo
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.PropagationDirection
import kotlinx.coroutree.model.SourceFile
import kotlinx.coroutree.model.SourceIndex
import kotlinx.coroutree.model.SourceModule
import kotlinx.coroutree.model.StackFrameDef
import kotlinx.coroutree.model.ThreadInfo
import kotlinx.coroutree.model.TraceHeader
import kotlinx.coroutree.model.TraceReader
import kotlinx.coroutree.runtime.TraceEvent.ContextChangeDef
import kotlinx.coroutree.runtime.TraceEvent.DiagnosticDef
import kotlinx.coroutree.runtime.TraceEvent.ExceptionDef
import kotlinx.coroutree.runtime.TraceEvent.NodeDef
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

/**
 * The trace format has two implementations that share nothing: the agent's encoder, written by hand, and the model.
 * Here the first writes and the second reads, field for field. docs/TRACE_FORMAT.md is the contract of both.
 */
internal class WireCompatibilityTest {
    private val bytes = ByteArrayOutputStream()
    private val encoder = TraceEncoder(bytes)

    private fun decoded(): List<Frame> = TraceReader(ByteArrayInputStream(bytes.toByteArray())).use { reader ->
        generateSequence { reader.next() }.toList()
    }

    // ------------------------------------------------------------------ messages, field for field

    @Test
    fun anEventWithEveryFieldSetIsReadBackFieldForField() {
        val event = TraceEvent(41, Wire.LAUNCHED, 2)
        event.seq = 1000000007L
        event.timeNanos = 123456789012L
        event.stack = arrayOf(INLINE, MAIN)
        event.otherNodeId = 40
        event.blockReason = Wire.BLOCK_PARK
        event.handledBy = Wire.BY_SUPERVISOR
        event.direction = Wire.CHILD_TO_PARENT
        event.finalState = Wire.STATE_CANCELLED
        event.heldNanos = 5_000_000
        event.sameStep = true

        val node = NodeDef()
        node.id = 41
        node.kind = Wire.KIND_COROUTINE
        node.construct = "launch"
        node.name = "worker №1"
        node.parentId = 40
        node.creatorId = 39
        node.siteFrame = MAIN
        node.origin = Wire.ORIGIN_PROJECT
        node.context = arrayOf(
            ContextEntry(Any(), Wire.CTX_JOB, "Job", "", false),
            ContextEntry(Any(), Wire.CTX_OTHER, "com.acme.Mdc", "MDC{user=7}", true),
        )
        node.implClass = "kotlinx.coroutines.StandaloneCoroutine"
        node.isThread = true
        node.tid = 77
        node.virtual = true
        node.daemon = true
        event.node = node

        val exception = ExceptionDef()
        exception.className = "java.lang.IllegalStateException"
        exception.message = "boom"
        exception.stack = arrayOf(REPO, MAIN)
        exception.identity = 0x7ABCDEF0
        exception.cancellation = true
        event.exception = exception

        val added = ContextChangeDef()
        added.kind = Wire.CTX_NAME
        added.key = "CoroutineName"
        added.newValue = "worker"
        added.added = true
        val removed = ContextChangeDef()
        removed.kind = Wire.CTX_EXCEPTION_HANDLER
        removed.key = "CoroutineExceptionHandler"
        removed.oldValue = "Handler"
        removed.removed = true
        val changed = ContextChangeDef()
        changed.kind = Wire.CTX_DISPATCHER
        changed.key = "Dispatcher"
        changed.oldValue = "Dispatchers.Default"
        changed.newValue = "Dispatchers.IO"
        event.contextDiff = arrayOf(added, removed, changed)

        encoder.writeMagic()
        encoder.writeEvent(event)

        val expected = Event(
            1000000007L, 123456789012L, 41, EventKind.LAUNCHED, 2, listOf(1, 2),
            NodeInfo(
                41, NodeKind.COROUTINE, "launch", "worker №1", 40, 39, 2, Origin.PROJECT,
                listOf(
                    ContextElement(ContextElementKind.JOB, "Job", "", false),
                    ContextElement(ContextElementKind.OTHER, "com.acme.Mdc", "MDC{user=7}", true)
                ),
                "kotlinx.coroutines.StandaloneCoroutine", ThreadInfo(77, true, true)
            ),
            40,
            ExceptionInfo("java.lang.IllegalStateException", "boom", listOf(3, 2), 0x7ABCDEF0, true),
            listOf(
                ContextChange(ContextElementKind.NAME, "CoroutineName", "", "worker", true, false),
                ContextChange(ContextElementKind.EXCEPTION_HANDLER, "CoroutineExceptionHandler", "Handler", "", false, true),
                ContextChange(ContextElementKind.DISPATCHER, "Dispatcher", "Dispatchers.Default", "Dispatchers.IO", false, false)
            ),
            BlockReason.PARK, HandledBy.SUPERVISOR, PropagationDirection.CHILD_TO_PARENT, NodeState.CANCELLED, 5_000_000, true
        )
        assertEquals(
            listOf(
                frame(StackFrameDef(1, "com.acme.util.UtilKt", "retry", "Util.kt", 30, true)),
                frame(StackFrameDef(2, "com.acme.MainKt", "main", "Main.kt", 12, false)),
                frame(StackFrameDef(3, "com.acme.Repo", "load", "Repo.java", 77, false)),
                frame(expected)
            ),
            decoded()
        )
    }

    @Test
    fun theHeaderIsReadBackFieldForField(@TempDir dir: Path) {
        val index = dir.resolve("index.tsv")
        Files.writeString(index, "M\t:app\t/work/app\nF\tcom.acme\tMain.kt\tsrc/main/kotlin/Main.kt\nF\t\tRoot.kt\tsrc/Root.kt\nM\t:lib\t/work/lib\n")
        val config = AgentConfig.parse(
            "build.id=b42,task.path=:app:run,project.dir=/work,include=com.acme;org.acme,exclude=com.acme.generated," +
                "source.index=$index,live=false,pace.events.per.second=4"
        )
        assertEquals(emptyList<String>(), config.problems)

        encoder.writeMagic()
        encoder.writeHeader(config, 1789672321000L)

        val frames = decoded()
        assertEquals(1, frames.size)
        val header = frames[0].header
        assertNotNull(header, "the first frame holds the header")
        val jvm = JvmInfo(
            ProcessHandle.current().pid(), System.getProperty("java.version"), System.getProperty("java.vm.name"),
            System.getProperty("java.vm.version"), System.getProperty("sun.java.command")
        )
        assertEquals(
            TraceHeader(
                TraceHeader.FORMAT_VERSION, BuildInfo.VERSION, "b42", ":app:run", jvm,
                SourceIndex(
                    listOf(
                        SourceModule(":app", "/work/app", listOf(SourceFile("com.acme", "Main.kt", "src/main/kotlin/Main.kt"), SourceFile("", "Root.kt", "src/Root.kt"))),
                        SourceModule(":lib", "/work/lib", emptyList())
                    )
                ),
                listOf("com.acme", "org.acme"), listOf("com.acme.generated"), 1789672321000L, "/work", true
            ),
            header
        )
        assertEquals(1, Wire.FORMAT_VERSION, "version 1 grows by fields that old readers skip, not by a new number")
    }

    @Test
    fun aJvmWithoutAGateSaysSoInItsHeader() {
        encoder.writeMagic()
        encoder.writeHeader(AgentConfig.parse("pace=false"), 1)
        val header = decoded()[0].header!!
        assertFalse(header.paceable)
        assertEquals(emptyList<SourceModule>(), header.sourceIndex.modules)
        assertEquals(emptyList<String>(), header.includePackages)
    }

    @Test
    fun aSettingOfTheGateAndADiagnosticAreReadBackFieldForField() {
        val pace = TraceEvent.PaceDef()
        pace.timeNanos = 9_000_000_000L
        pace.afterSeq = 512
        pace.scopeNodeId = 14
        pace.intervalNanos = Pace.MAX_INTERVAL_NANOS
        pace.paused = true
        pace.steps = Int.MAX_VALUE
        pace.reason = Wire.PACE_CONTROLLER
        pace.dropped = true

        encoder.writeMagic()
        encoder.writePace(pace)
        encoder.writeDiagnostic(DiagnosticDef(Wire.WARNING, "Ignored stack.depth=many"))
        encoder.writePace(TraceEvent.PaceDef()) // the global setting, unlimited, before the first event: all zeroes

        assertEquals(
            listOf(
                Frame(null, null, null, null, PaceDef(9_000_000_000L, 512, 14, Pace.MAX_INTERVAL_NANOS, true, Int.MAX_VALUE, PaceDef.Reason.CONTROLLER, true)),
                Frame(null, null, null, Diagnostic(Diagnostic.Severity.WARNING, "Ignored stack.depth=many"), null),
                Frame(null, null, null, null, PaceDef())
            ),
            decoded()
        )
    }

    // ------------------------------------------------------------------ numbers

    /**
     * Every enum number the agent writes is the model's value of the same name. The names differ by a prefix only,
     * because proto enums share a namespace and the Java constants share a class.
     */
    @Test
    fun everyEnumNumberOfTheAgentIsTheModelsValueOfTheSameName() {
        val seen = HashMap<Class<*>, Int>()
        for (field in Wire::class.java.declaredFields) {
            if (field.type != Int::class.javaPrimitiveType || !Modifier.isStatic(field.modifiers) || field.name == "FORMAT_VERSION") continue
            field.isAccessible = true
            val number = field.getInt(null)
            val name = field.name

            var event = TraceEvent(1, Wire.LAUNCHED, 1)
            event.seq = 1
            val node = NodeDef()
            event.node = node
            val change = ContextChangeDef()
            event.contextDiff = arrayOf(change)
            val pace = TraceEvent.PaceDef()
            var severity = 0

            val expected: String = when {
                name.startsWith("KIND_") -> name.substring(5).also { node.kind = number }
                name.startsWith("STATE_") -> name.substring(6).also { event.finalState = number }
                name.startsWith("ORIGIN_") -> name.substring(7).also { node.origin = number }
                name.startsWith("BLOCK_") -> name.substring(6).also { event.blockReason = number }
                name.startsWith("BY_") -> name.substring(3).also { event.handledBy = number }
                name.startsWith("CTX_") -> name.substring(4).also {
                    change.kind = number
                    node.context = arrayOf(ContextEntry(null, number, "k", "v", false))
                }
                name.startsWith("PACE_") -> name.substring(5).also { pace.reason = number }
                name == "PARENT_TO_CHILD" || name == "CHILD_TO_PARENT" -> name.also { event.direction = number }
                name == "INFO" || name == "WARNING" || name == "ERROR" -> name.also { severity = number }
                else -> name.also {
                    event = TraceEvent(1, number, 1)
                    event.seq = 1
                }
            }

            bytes.reset()
            encoder.writeMagic()
            encoder.writeEvent(event)
            encoder.writePace(pace)
            encoder.writeDiagnostic(DiagnosticDef(severity, "m"))
            val frames = decoded()
            val read = frames[0].event!!

            val actual: Enum<*> = when {
                name.startsWith("KIND_") -> read.node!!.kind
                name.startsWith("STATE_") -> read.finalState
                name.startsWith("ORIGIN_") -> read.node!!.origin
                name.startsWith("BLOCK_") -> read.blockReason
                name.startsWith("BY_") -> read.handledBy
                name.startsWith("CTX_") -> read.contextDiff[0].kind.also { assertEquals(it, read.node!!.context[0].kind, name) }
                name.startsWith("PACE_") -> frames[1].pace!!.reason
                name.endsWith("_TO_CHILD") || name.endsWith("_TO_PARENT") -> read.direction
                severity != 0 -> frames[2].diagnostic!!.severity
                else -> read.kind
            }

            assertEquals(expected, actual.name, "Wire.$name = $number")
            seen.merge(actual.declaringJavaClass, 1, Int::plus)
        }

        // And the other way round: the agent has a number for every value the model can read. NodeState is the
        // exception that the format states: the agent writes final states only, and a reader folds the others.
        val expected = HashMap<Class<*>, Int>()
        for (type in listOf(
            EventKind::class.java, NodeKind::class.java, Origin::class.java, BlockReason::class.java, HandledBy::class.java,
            ContextElementKind::class.java, PaceDef.Reason::class.java, PropagationDirection::class.java, Diagnostic.Severity::class.java
        )) {
            expected[type] = type.enumConstants.size - 1 // minus UNSPECIFIED
        }
        expected[NodeState::class.java] = NodeState.entries.count { it.isFinal }
        assertEquals(expected, seen, "values per enum that the agent has a number for")
    }

    @Test
    fun theSignatureAndTheVersionAreTheModels() {
        encoder.writeMagic()
        assertArrayEquals("COROTREE".toByteArray(Charsets.US_ASCII), bytes.toByteArray())
        assertEquals(TraceHeader.FORMAT_VERSION, Wire.FORMAT_VERSION)
        assertEquals(emptyList<Frame>(), decoded(), "a trace of nothing but its signature is an empty trace")
    }

    // ------------------------------------------------------------------ proto3 conventions

    @Test
    fun aFieldThatHoldsItsZeroDefaultIsNotWritten() {
        val event = TraceEvent(0, 0, 0)
        event.seq = 1
        encoder.writeEvent(event)
        // length 4 | Frame.event (field 2, length-delimited) of 2 bytes | Event.seq (field 1, varint) = 1
        assertArrayEquals(
            byteArrayOf(4, 0x12, 2, 0x08, 1), bytes.toByteArray(),
            "an event of a JVM without a gate is byte for byte what it was before held_nanos and same_step existed"
        )
    }

    @Test
    fun anEmptyMessageIsStillThere() {
        // Presence is information: an exception without a description is still an exception, and a thread with nothing set is still a thread.
        val event = TraceEvent(1, Wire.EXCEPTION_THROWN, 1)
        event.seq = 1
        event.exception = ExceptionDef()
        event.node = NodeDef()
        event.node.isThread = true
        event.stack = emptyArray()
        encoder.writeMagic()
        encoder.writeEvent(event)

        var read = decoded()[0].event!!
        assertEquals(ExceptionInfo(), read.exception)
        assertEquals(ThreadInfo(), read.node!!.thread)
        assertEquals(emptyList<Int>(), read.stack)

        bytes.reset()
        val plain = TraceEvent(1, Wire.RESUMED, 1)
        plain.seq = 2
        plain.node = NodeDef() // not a thread
        encoder.writeMagic()
        encoder.writeEvent(plain)
        read = decoded()[0].event!!
        assertNull(read.exception)
        assertNull(read.node!!.thread, "thread info is for threads only")
    }

    @Test
    fun numbersAndTextSurviveAtTheirExtremes() {
        // A message of megabytes (a frame length of several varint bytes), text outside ASCII and outside the BMP,
        // the largest and the negative numbers a field can hold.
        val huge = "∑🧵 ".repeat(200_000)
        val event = TraceEvent(Long.MAX_VALUE, Wire.EXCEPTION_THROWN, Long.MAX_VALUE)
        event.seq = Long.MAX_VALUE
        event.timeNanos = Long.MAX_VALUE
        event.heldNanos = -1 // the agent never writes a negative value, but the field is an int64
        event.exception = ExceptionDef()
        event.exception.message = huge
        event.exception.identity = Int.MIN_VALUE
        event.stack = Array(300) { i -> StackFrameRef("com.acme.Deep", "level$i", "Deep.kt", if (i == 0) Int.MAX_VALUE else i) }

        encoder.writeMagic()
        encoder.writeEvent(event)
        val small = TraceEvent(1, Wire.RESUMED, 1)
        small.seq = 2
        encoder.writeEvent(small)

        val frames = decoded()
        assertEquals(302, frames.size, "300 frame definitions and two events: the reader found every frame's end")
        assertEquals(Int.MAX_VALUE, frames[0].stackFrame!!.line)
        val read = frames[300].event!!
        assertEquals(Long.MAX_VALUE, read.seq)
        assertEquals(Long.MAX_VALUE, read.timeNanos)
        assertEquals(Long.MAX_VALUE, read.nodeId)
        assertEquals(-1, read.heldNanos)
        assertEquals(huge, read.exception!!.message)
        assertEquals(Int.MIN_VALUE, read.exception!!.identity)
        assertEquals(300, read.stack.size)
        assertEquals(300, read.stack[299], "frame ids above 127 take two bytes in a packed field")
        assertEquals(2, frames[301].event!!.seq)
    }

    // ------------------------------------------------------------------ interned stack frames

    @Test
    fun aStackFrameIsDefinedOnceBeforeTheFirstEventThatRefersToItAndIdsAreDense() {
        encoder.writeMagic()
        val first = TraceEvent(1, Wire.THREAD_BLOCKED, 1)
        first.seq = 1
        first.stack = arrayOf(REPO, MAIN)
        encoder.writeEvent(first)

        // Equal by value, another instance, and the three parts of an event that can refer to a frame.
        val second = TraceEvent(2, Wire.LAUNCHED, 1)
        second.seq = 2
        second.stack = arrayOf(StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12), INLINE)
        second.node = NodeDef()
        second.node.siteFrame = StackFrameRef("com.acme.Repo", "load", "Repo.java", 77)
        second.exception = ExceptionDef()
        second.exception.stack = arrayOf(
            // The same method and line as INLINE, but the method's own code, not an inline body.
            StackFrameRef("com.acme.util.UtilKt", "retry", "Util.kt", 30, false),
            // The same as MAIN but the file: a class of the same name from another source file.
            StackFrameRef("com.acme.MainKt", "main", "Other.kt", 12),
        )
        encoder.writeEvent(second)

        val shape = decoded().mapNotNull { frame ->
            frame.stackFrame?.let { def ->
                "frame ${def.id} ${def.className}:${def.line}" + (if (def.inlined) " inlined" else "") + " ${def.fileName}"
            } ?: frame.event?.let { event ->
                "event ${event.seq} stack ${event.stack}" +
                    (event.node?.let { " site ${it.siteFrame}" } ?: "") +
                    (event.exception?.let { " thrown at ${it.stack}" } ?: "")
            }
        }
        assertEquals(
            listOf(
                "frame 1 com.acme.Repo:77 Repo.java",
                "frame 2 com.acme.MainKt:12 Main.kt",
                "event 1 stack [1, 2]",
                "frame 3 com.acme.util.UtilKt:30 inlined Util.kt",
                "frame 4 com.acme.util.UtilKt:30 Util.kt",
                "frame 5 com.acme.MainKt:12 Other.kt",
                "event 2 stack [2, 3] site 1 thrown at [4, 5]"
            ),
            shape
        )
    }

    @Test
    fun aFrameOfNativeOrUnknownCodeHasLineZeroAndNoFile() {
        // The JVM gives -2 for a native method and -1 for no line information. The format writes 0 for an unknown line.
        val event = TraceEvent(1, Wire.THREAD_BLOCKED, 1)
        event.seq = 1
        event.stack = StackFrameRef.of(
            arrayOf(
                StackTraceElement("jdk.internal.misc.Unsafe", "park", "Unsafe.java", -2),
                StackTraceElement("com.acme.Generated", "run", null, -1),
            ),
            10
        )
        encoder.writeMagic()
        encoder.writeEvent(event)
        val frames = decoded()
        assertEquals(StackFrameDef(1, "jdk.internal.misc.Unsafe", "park", "Unsafe.java", 0, false), frames[0].stackFrame)
        assertEquals(StackFrameDef(2, "com.acme.Generated", "run", "", 0, false), frames[1].stackFrame)
    }

    companion object {
        private val INLINE = StackFrameRef("com.acme.util.UtilKt", "retry", "Util.kt", 30, true)
        private val MAIN = StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12)
        private val REPO = StackFrameRef("com.acme.Repo", "load", "Repo.java", 77)

        private fun frame(event: Event) = Frame(null, event, null, null, null)

        private fun frame(def: StackFrameDef) = Frame(null, null, def, null, null)
    }
}
