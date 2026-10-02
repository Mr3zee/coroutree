package kotlinx.coroutree.model

import kotlinx.coroutree.model.tree.TraceStore
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The bytes of a trace, against docs/TRACE_FORMAT.md. The agent writes them with an encoder of its own, so what the
 * model reads and writes is pinned here to the numbers of the document, not to whatever the serializer makes of
 * `Trace.kt` today.
 */
class TraceWireTest {
    private val headerModel = TraceHeader(
        formatVersion = 1,
        agentVersion = "0.1",
        buildId = "build-7",
        taskPath = ":app:run",
        jvm = JvmInfo(pid = 4242, javaVersion = "26", vmName = "OpenJDK", vmVersion = "26+35", command = "demo.MainKt arg"),
        sourceIndex = SourceIndex(listOf(SourceModule(":app", "/work/app", listOf(SourceFile("demo", "Main.kt", "src/main/kotlin/demo/Main.kt"))))),
        includePackages = listOf("demo", "lib"),
        excludePackages = listOf("demo.generated"),
        startedAtEpochMillis = 1789672321000,
        projectDir = "/work",
        paceable = true,
    )

    private val headerBytes = proto {
        message(1) {
            varint(1, 1); string(2, "0.1"); string(3, "build-7"); string(4, ":app:run")
            message(5) { varint(1, 4242); string(2, "26"); string(3, "OpenJDK"); string(4, "26+35"); string(5, "demo.MainKt arg") }
            message(6) {
                message(1) {
                    string(1, ":app"); string(2, "/work/app")
                    message(3) { string(1, "demo"); string(2, "Main.kt"); string(3, "src/main/kotlin/demo/Main.kt") }
                }
            }
            string(7, "demo"); string(7, "lib"); string(8, "demo.generated")
            varint(9, 1789672321000); string(10, "/work"); varint(11, 1)
        }
    }

    private val eventModel = Event(
        seq = 300,
        timeNanos = 123_456_789_012,
        nodeId = 7,
        kind = EventKind.LAUNCHED,
        threadId = 2,
        stack = listOf(1, 300, 2),
        node = NodeInfo(
            id = 7,
            kind = NodeKind.COROUTINE,
            construct = "launch",
            name = "worker",
            parentId = 3,
            creatorId = 4,
            siteFrame = 9,
            origin = Origin.PROJECT,
            context = listOf(
                ContextElement(ContextElementKind.DISPATCHER, "Dispatcher", "Dispatchers.IO", threadContextElement = true),
                ContextElement(ContextElementKind.JOB, "Job"),
            ),
            implClass = "kotlinx.coroutines.StandaloneCoroutine",
            thread = ThreadInfo(tid = 33, virtual = true, daemon = true),
        ),
        otherNodeId = 5,
        exception = ExceptionInfo("java.lang.IllegalStateException", "boom", stack = listOf(4, 5), identity = 99887766, cancellation = true),
        contextDiff = listOf(
            ContextChange(ContextElementKind.NAME, "CoroutineName", newValue = "worker", added = true),
            ContextChange(ContextElementKind.OTHER, "demo.MyElement", oldValue = "x", removed = true),
        ),
        blockReason = BlockReason.SLEEP,
        handledBy = HandledBy.SUPERVISOR,
        direction = PropagationDirection.PARENT_TO_CHILD,
        finalState = NodeState.CANCELLED,
        heldNanos = 1_500_000_000,
        sameStep = true,
    )

    private val eventBytes = proto {
        message(2) {
            varint(1, 300); varint(2, 123_456_789_012); varint(3, 7); varint(4, 1); varint(5, 2)
            packed(6, 1, 300, 2)
            message(7) {
                varint(1, 7); varint(2, 2); string(3, "launch"); string(4, "worker"); varint(5, 3); varint(6, 4); varint(7, 9); varint(8, 1)
                message(9) { varint(1, 2); string(2, "Dispatcher"); string(3, "Dispatchers.IO"); varint(4, 1) }
                message(9) { varint(1, 1); string(2, "Job") }
                string(10, "kotlinx.coroutines.StandaloneCoroutine")
                message(11) { varint(1, 33); varint(2, 1); varint(3, 1) }
            }
            varint(8, 5)
            message(9) { string(1, "java.lang.IllegalStateException"); string(2, "boom"); packed(3, 4, 5); varint(4, 99887766); varint(5, 1) }
            message(10) { varint(1, 3); string(2, "CoroutineName"); string(4, "worker"); varint(5, 1) }
            message(10) { varint(1, 5); string(2, "demo.MyElement"); string(3, "x"); varint(6, 1) }
            varint(11, 5); varint(12, 3); varint(13, 1); varint(14, 7); varint(15, 1_500_000_000); varint(16, 1)
        }
    }

    private val stackFrameModel = StackFrameDef(id = 9, className = "demo.UtilKt", methodName = "retry", fileName = "Util.kt", line = 7, inlined = true)
    private val stackFrameBytes = proto {
        message(3) { varint(1, 9); string(2, "demo.UtilKt"); string(3, "retry"); string(4, "Util.kt"); varint(5, 7); varint(6, 1) }
    }

    private val diagnosticModel = Diagnostic(Diagnostic.Severity.WARNING, "kotlinx.coroutines 1.8 is not tested")
    private val diagnosticBytes = proto { message(4) { varint(1, 2); string(2, "kotlinx.coroutines 1.8 is not tested") } }

    private val paceModel = PaceDef(
        timeNanos = 77, afterSeq = 41, scopeNodeId = 3, intervalNanos = 250_000_000, paused = true, steps = 2,
        reason = PaceDef.Reason.CONTROLLER, dropped = true,
    )
    private val paceBytes = proto {
        message(5) { varint(1, 77); varint(2, 41); varint(3, 3); varint(4, 250_000_000); varint(5, 1); varint(6, 2); varint(7, 2); varint(8, 1) }
    }

    private val models = listOf(
        Frame(header = headerModel), Frame(event = eventModel), Frame(stackFrame = stackFrameModel),
        Frame(diagnostic = diagnosticModel), Frame(pace = paceModel),
    )
    private val bytes get() = traceBytes(headerBytes, eventBytes, stackFrameBytes, diagnosticBytes, paceBytes)

    @Test
    fun everyFieldIsReadFromTheNumberTheFormatGivesIt() {
        assertEquals(models, framesOf(bytes))
    }

    @Test
    fun theWriterProducesTheBytesOfTheFormatDocument() {
        // Byte for byte, not merely something the reader of this module takes back: the other end is the agent.
        assertContentEquals(bytes, written(models))
    }

    @Test
    fun enumNumbersAreTheOnesOfTheFormatDocument() {
        fun event(body: Proto.() -> Unit) = framesOf(traceBytes(proto { message(2, body) })).single().event!!

        // Names in the order of their numbers in the document, from 1; 0 is UNSPECIFIED everywhere.
        fun <E : Enum<E>> check(all: List<E>, inDocumentOrder: String, read: (Int) -> E) {
            val names = inDocumentOrder.split(' ')
            assertEquals(listOf("UNSPECIFIED") + names, all.map { it.name }, "every value has a number in the document")
            names.forEachIndexed { index, name -> assertEquals(name, read(index + 1).name, "number ${index + 1}") }
            assertEquals("UNSPECIFIED", read(0).name)
        }

        check(
            EventKind.entries,
            "LAUNCHED DISCOVERED CONTEXT_CHANGED DISPATCHER_CHANGED SUSPENDED RESUMED EXCEPTION_THROWN EXCEPTION_PROPAGATED EXCEPTION_HANDLED " +
                "CANCELLATION_REQUESTED CANCELLATION_PROPAGATED CANCELLING THREAD_BLOCKED THREAD_UNBLOCKED THREAD_INTERRUPTED FINISHED",
        ) { n -> event { varint(4, n) }.kind }
        check(NodeState.entries, "ACTIVE SUSPENDED BLOCKED CANCELLING COMPLETED FAILED CANCELLED") { n -> event { varint(14, n) }.finalState }
        check(BlockReason.entries, "MONITOR WAIT JOIN PARK SLEEP IO RUN_BLOCKING") { n -> event { varint(11, n) }.blockReason }
        check(HandledBy.entries, "CATCH COROUTINE_EXCEPTION_HANDLER SUPERVISOR DEFERRED_HELD UNCAUGHT_EXCEPTION_HANDLER") { n ->
            event { varint(12, n) }.handledBy
        }
        check(PropagationDirection.entries, "PARENT_TO_CHILD CHILD_TO_PARENT") { n -> event { varint(13, n) }.direction }
        check(NodeKind.entries, "THREAD COROUTINE SCOPE CONTEXT_CHANGE TASK POOL") { n -> event { message(7) { varint(2, n) } }.node!!.kind }
        check(Origin.entries, "PROJECT LIBRARY") { n -> event { message(7) { varint(8, n) } }.node!!.origin }
        check(ContextElementKind.entries, "JOB DISPATCHER NAME EXCEPTION_HANDLER OTHER") { n ->
            event { message(10) { varint(1, n) } }.contextDiff.single().kind
        }
        check(Diagnostic.Severity.entries, "INFO WARNING ERROR") { n ->
            framesOf(traceBytes(proto { message(4) { varint(1, n) } })).single().diagnostic!!.severity
        }
        check(PaceDef.Reason.entries, "CONFIG CONTROLLER FAIL_OPEN SHUTDOWN NODE_FINISHED") { n ->
            framesOf(traceBytes(proto { message(5) { varint(7, n) } })).single().pace!!.reason
        }
    }

    @Test
    fun zeroDefaultsTakeNoBytes() {
        // "every field has a zero default and is left out when it holds it": nested messages and enums included.
        assertContentEquals(traceBytes(proto { message(2) { varint(1, 1) } }), written(listOf(Frame(event = Event(seq = 1)))))
        assertContentEquals(traceBytes(proto { message(2) {} }), written(listOf(Frame(event = Event()))))
        assertContentEquals(traceBytes(proto { message(1) { varint(1, 1) } }), written(listOf(Frame(header = TraceHeader(formatVersion = 1)))))
        assertContentEquals(traceBytes(proto { message(5) {} }), written(listOf(Frame(pace = PaceDef()))))
        // And the other way round: what is absent reads as the default.
        assertEquals(listOf(Frame(event = Event()), Frame(header = TraceHeader())), framesOf(traceBytes(proto { message(2) {} }, proto { message(1) {} })))
    }

    @Test
    fun unknownFieldsOfEveryWireTypeAreSkipped() {
        // "Unknown fields must be skipped: that is how the format grows within a version."
        fun Proto.fromTheFuture() {
            varint(90, 7); string(91, "new"); fixed64(92, -1); fixed32(93, -1); message(94) { varint(1, 1); string(2, "x") }
        }
        val frames = framesOf(
            traceBytes(
                proto { message(1) { varint(1, 1); fromTheFuture(); message(5) { fromTheFuture(); varint(1, 4242) } }; fromTheFuture() },
                proto { message(2) { fromTheFuture(); varint(1, 1); varint(3, 3); message(7) { varint(1, 3); fromTheFuture() }; varint(4, 1) } },
                proto { message(9) { varint(1, 5) } }, // a kind of frame this reader has never heard of
                proto { message(3) { varint(1, 1); string(2, "A"); fromTheFuture() } },
            ),
        )
        assertEquals(
            listOf(
                Frame(header = TraceHeader(formatVersion = 1, jvm = JvmInfo(pid = 4242))),
                Frame(event = Event(seq = 1, nodeId = 3, kind = EventKind.LAUNCHED, node = NodeInfo(id = 3))),
                Frame(),
                Frame(stackFrame = StackFrameDef(id = 1, className = "A")),
            ),
            frames,
        )
        val snapshot = TraceStore().apply { frames.forEach(::accept) }.snapshot()
        assertEquals(listOf(3L), snapshot.roots, "a frame of an unknown kind is nothing, and what follows it is read")
        assertEquals("A", snapshot.frame(1)?.className)
    }

    @Test
    fun anEnumValueFromTheFutureDoesNotMakeTheTraceUnreadable() {
        // The format is proto3 ("Proto3 conventions", `syntax = "proto3"`), whose enums are open: a number this reader
        // does not know is data, not corruption. Every milestone adds event kinds; a reader that dies on the first one
        // cannot show the rest of the trace, which it understands.
        val bytes = traceBytes(
            proto { message(2) { varint(1, 1); varint(3, 1); varint(4, 1); message(7) { varint(1, 1); varint(2, 2); string(3, "launch") } } },
            proto { message(2) { varint(1, 2); varint(3, 1); varint(4, 99) } },
            proto { message(2) { varint(1, 3); varint(3, 1); varint(4, 16); varint(14, 5) } },
        )
        val snapshot = TraceStore.read(ByteArrayInputStream(bytes))
        assertEquals(listOf(1L, 2L, 3L), snapshot.events.map { it.seq })
        assertEquals(NodeState.COMPLETED, snapshot.node(1)!!.state)
    }

    @Test
    fun sourceIndexesConcatenatedByteWiseAreOneIndex() {
        // KDoc of SourceIndex: "Serialized indexes of several modules can be concatenated byte-wise".
        val app = proto { message(1) { string(1, ":app"); string(2, "/work/app"); message(3) { string(1, "demo"); string(2, "Main.kt"); string(3, "Main.kt") } } }
        val lib = proto { message(1) { string(1, ":lib"); string(2, "/work/lib") } }
        val header = framesOf(traceBytes(proto { message(1) { bytes(6, app + lib) } })).single().header!!
        assertEquals(listOf(":app", ":lib"), header.sourceIndex.modules.map { it.path })
        assertEquals("Main.kt", header.sourceIndex.modules[0].files.single().fileName)
    }

    @Test
    fun frameLengthsAroundEveryVarintBoundaryAreWrittenAndRead() {
        // 127 | 128 and 16383 | 16384 are where the length grows a byte.
        val sizes = listOf(0, 1, 120, 121, 122, 123, 124, 125, 126, 127, 128, 16370, 16375, 16376, 16377, 16378, 16379, 16380, 16390, 70_000, 3_000_000)
        val models = sizes.map { Frame(diagnostic = Diagnostic(message = "x".repeat(it))) }
        val byHand = traceBytes(*sizes.map { size -> proto { message(4) { if (size > 0) string(2, "x".repeat(size)) } } }.toTypedArray())
        assertContentEquals(byHand, written(models))
        assertEquals(models, framesOf(byHand))
    }

    @Test
    fun aTraceCutAtAnyByteEndsWithItsLastWholeFrame() {
        // "A reader treats a frame that is cut short by the end of the stream as the end of the trace, not as an error."
        val messages = listOf(headerBytes, eventBytes, proto { message(4) { string(2, "y".repeat(200)) } }, stackFrameBytes, paceBytes)
        val whole = traceBytes(*messages.toTypedArray())
        val frames = framesOf(whole)
        val ends = messages.indices.map { traceBytes(*messages.take(it + 1).toTypedArray()).size }
        for (cut in 8..whole.size) {
            assertEquals(frames.take(ends.count { it <= cut }), framesOf(whole.copyOf(cut)), "cut at byte $cut of ${whole.size}")
        }
    }

    @Test
    fun aStreamThatIsNotATraceIsRefusedBeforeAnythingIsRead() {
        val whole = traceBytes(eventBytes)
        for (cut in 0 until 8) assertFailsWith<TraceFormatException>("the first $cut bytes of the signature") { framesOf(whole.copyOf(cut)) }
        assertFailsWith<TraceFormatException> { framesOf("COROTREF".toByteArray() + eventBytes) }
        assertFailsWith<TraceFormatException> { framesOf("corotree".toByteArray() + eventBytes) }
        assertEquals(emptyList(), framesOf(traceBytes()), "a writer that died right after the signature left an empty trace")
    }

    @Test
    fun anImpossibleFrameLengthIsCorruptionNotAnAllocation() {
        fun trace(vararg length: Int) = traceBytes() + length.map { it.toByte() }.toByteArray() + ByteArray(16)
        // One byte more than the largest frame; a length that is negative as an int; a varint that never ends.
        assertFailsWith<TraceFormatException> { framesOf(traceBytes() + lengthVarint(TraceFormat.MAX_FRAME_SIZE + 1)) }
        assertFailsWith<TraceFormatException> { framesOf(trace(0xFF, 0xFF, 0xFF, 0xFF, 0x0F)) }
        assertFailsWith<TraceFormatException> { framesOf(trace(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)) }
        assertFailsWith<TraceFormatException> { framesOf(trace(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01)) }

        // The frames before the damage were good and are delivered.
        val reader = TraceReader(ByteArrayInputStream(traceBytes(eventBytes) + lengthVarint(TraceFormat.MAX_FRAME_SIZE + 1)))
        assertEquals(eventModel, reader.next()?.event)
        assertFailsWith<TraceFormatException> { reader.next() }
    }

    @Test
    fun aStreamThatDeliversByteByByteReadsTheSame() {
        // The live socket hands over whatever has arrived; a frame is not a read.
        class Dribble(private val bytes: ByteArray) : InputStream() {
            private var at = 0
            override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val b = read()
                if (b < 0) return -1
                buffer[offset] = b.toByte()
                return 1
            }
        }
        assertEquals(models, TraceReader(Dribble(bytes)).use { it.frames().toList() })
    }

    @Test
    fun aRecordedTraceIsReadToItsEndInOneCall() {
        val snapshot = TraceStore.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 2)))
        assertEquals(headerModel, snapshot.header)
        assertEquals(listOf(diagnosticModel), snapshot.diagnostics)
        assertEquals(stackFrameModel, snapshot.frame(9))
        assertNull(snapshot.frame(8))
        // Sequence number 300 with nothing before it: held back while the stream might still bring the rest, let
        // through once it has ended.
        assertEquals(listOf(300L), snapshot.events.map { it.seq })
        assertEquals(true, snapshot.complete)
        assertEquals(emptyList(), snapshot.paceChanges, "the last frame was cut short")
        assertEquals(":app", snapshot.sources.resolve(StackFrameDef(className = "demo.MainKt", fileName = "Main.kt"))?.module)
    }
}
