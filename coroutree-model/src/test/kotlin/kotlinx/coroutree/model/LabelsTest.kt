package kotlinx.coroutree.model

import kotlinx.coroutree.model.tree.describe
import kotlinx.coroutree.model.tree.isRuntimeFrame
import kotlinx.coroutree.model.tree.label
import kotlinx.coroutree.model.tree.qualified
import kotlinx.coroutree.model.tree.shortLocation
import kotlinx.coroutree.model.tree.siteOf
import kotlinx.coroutree.model.tree.title
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The wording of nodes and events. The GUI and every golden tree say these words, so a change here is a change of
 * what users read and of the whole corpus: it should be made on purpose.
 */
class LabelsTest {
    private val snapshot = snapshotOf {
        frame(1, "kotlinx.coroutines.DelayKt", "delay", "Delay.kt", 100)
        frame(2, "demo.MainKt", "main", "Main.kt", 12)
        frame(3, "kotlinx.coroutines.sync.MutexKt", "withLock", "Mutex.kt", 40, inlined = true)
        frame(4, "demo.UtilKt", "retry", "Util.kt", 7, inlined = true)
        frame(5, "demo.Generated", "invoke")
        frame(6, "kotlin.coroutines.jvm.internal.BaseContinuationImpl", "resumeWith", "ContinuationImpl.kt", 33)
        thread(1, "main")
        launched(2, parent = 1, name = "parent")
        launched(3, parent = 2, name = "child")
        launched(4, parent = 2, construct = "supervisorScope", kind = NodeKind.SCOPE)
        thread(5, "worker")
    }

    private val failure = ExceptionInfo("java.lang.IllegalStateException", "boom")

    @Test
    fun anEventReadsFromTheSideOfTheNodeItIsShownOn() {
        // "An event belongs to the node it happens to, otherNodeId is where it came from"; describe(): "as seen from
        // the node viewpoint (events naming another node appear on both). Pass 0 for the neutral wording."
        class Case(val event: Event, val own: String, val fromOther: String? = null)

        fun on(node: Long, kind: EventKind, other: Long = 0, configure: Event.() -> Event = { this }) =
            Event(seq = 1, nodeId = node, kind = kind, otherNodeId = other).configure()

        val cases = listOf(
            Case(on(3, EventKind.LAUNCHED), "launched"),
            Case(on(1, EventKind.DISCOVERED), "first seen"),
            Case(on(3, EventKind.RESUMED) { copy(threadId = 1) }, "resumed on Thread \"main\""),
            Case(on(3, EventKind.RESUMED) { copy(threadId = 77) }, "resumed on node #77"),
            Case(on(3, EventKind.EXCEPTION_THROWN) { copy(exception = failure) }, "threw IllegalStateException: boom"),
            Case(on(3, EventKind.EXCEPTION_THROWN) { copy(exception = ExceptionInfo("demo.Outer\$Oops")) }, "threw Outer\$Oops"),
            Case(
                on(2, EventKind.EXCEPTION_PROPAGATED, other = 3) { copy(exception = failure) },
                own = "IllegalStateException propagated from launch \"child\"",
                fromOther = "IllegalStateException propagated to launch \"parent\"",
            ),
            Case(on(3, EventKind.EXCEPTION_HANDLED) { copy(exception = failure, handledBy = HandledBy.CATCH) }, "IllegalStateException caught by catch"),
            Case(
                on(3, EventKind.EXCEPTION_HANDLED) { copy(exception = failure, handledBy = HandledBy.COROUTINE_EXCEPTION_HANDLER) },
                "IllegalStateException handled by CoroutineExceptionHandler",
            ),
            Case(
                on(4, EventKind.EXCEPTION_HANDLED, other = 3) { copy(exception = failure, handledBy = HandledBy.SUPERVISOR) },
                own = "IllegalStateException of launch \"child\" stopped here (supervisor)",
                fromOther = "IllegalStateException stopped at supervisor supervisorScope",
            ),
            Case(
                on(3, EventKind.EXCEPTION_HANDLED) { copy(exception = failure, handledBy = HandledBy.DEFERRED_HELD) },
                "IllegalStateException held in Deferred until awaited",
            ),
            Case(
                on(1, EventKind.EXCEPTION_HANDLED) { copy(exception = failure, handledBy = HandledBy.UNCAUGHT_EXCEPTION_HANDLER) },
                "IllegalStateException reached the uncaught exception handler",
            ),
            Case(
                on(3, EventKind.CANCELLATION_REQUESTED, other = 2),
                own = "cancellation requested by launch \"parent\"",
                fromOther = "requested cancellation of launch \"child\"",
            ),
            Case(on(3, EventKind.CANCELLATION_REQUESTED), "cancellation requested"),
            Case(
                on(3, EventKind.CANCELLATION_PROPAGATED, other = 2) { copy(direction = PropagationDirection.PARENT_TO_CHILD) },
                own = "cancelled by parent launch \"parent\"",
                fromOther = "cancellation propagated to child launch \"child\"",
            ),
            Case(
                on(2, EventKind.CANCELLATION_PROPAGATED, other = 3) { copy(direction = PropagationDirection.CHILD_TO_PARENT) },
                own = "cancelled by child launch \"child\"",
                fromOther = "cancellation propagated to parent launch \"parent\"",
            ),
            Case(on(3, EventKind.CANCELLING), "cancelling"),
            Case(
                on(3, EventKind.CANCELLING) { copy(exception = ExceptionInfo("kotlinx.coroutines.JobCancellationException", "Job was cancelled", cancellation = true)) },
                "cancelling: JobCancellationException: Job was cancelled",
            ),
            Case(
                on(1, EventKind.THREAD_BLOCKED, other = 3) { copy(blockReason = BlockReason.SLEEP) },
                own = "blocked (sleep) in launch \"child\"",
                fromOther = "blocked Thread \"main\" (sleep)",
            ),
            Case(on(1, EventKind.THREAD_BLOCKED) { copy(blockReason = BlockReason.RUN_BLOCKING) }, "blocked (runBlocking)"),
            Case(on(1, EventKind.THREAD_UNBLOCKED, other = 3), own = "unblocked", fromOther = "unblocked Thread \"main\""),
            Case(on(1, EventKind.THREAD_UNBLOCKED), "unblocked"),
            Case(
                on(5, EventKind.THREAD_INTERRUPTED, other = 1),
                own = "interrupted by Thread \"main\"",
                fromOther = "interrupted Thread \"worker\"",
            ),
            Case(on(5, EventKind.THREAD_INTERRUPTED), "interrupted"),
            Case(on(3, EventKind.FINISHED) { copy(finalState = NodeState.COMPLETED) }, "completed"),
            Case(on(3, EventKind.FINISHED) { copy(finalState = NodeState.FAILED) }, "failed"),
            Case(on(3, EventKind.FINISHED) { copy(finalState = NodeState.CANCELLED) }, "cancelled"),
            Case(on(3, EventKind.UNSPECIFIED), "unknown event"),
        )
        for (case in cases) {
            val event = case.event
            assertEquals(case.own, snapshot.describe(event, viewpoint = event.nodeId), "${event.kind} on its own node")
            if (event.otherNodeId == 0L) {
                assertEquals(case.own, snapshot.describe(event), "${event.kind} in the log of the whole trace")
            } else {
                assertEquals(case.fromOther ?: case.own, snapshot.describe(event, viewpoint = event.otherNodeId), "${event.kind} on the other node")
            }
        }
    }

    @Test
    fun contextChangesAreWrittenAsADiff() {
        // KDoc: `Dispatcher: Dispatchers.Default → Dispatchers.IO`, `+CoroutineName: worker`, `-MyElement: …`,
        // `+CoroutineExceptionHandler`. A custom element's key is its class name; its simple name is what one reads.
        val changed = ContextChange(ContextElementKind.DISPATCHER, "Dispatcher", "Dispatchers.Default", "Dispatchers.IO")
        val named = ContextChange(ContextElementKind.NAME, "CoroutineName", newValue = "worker", added = true)
        val removed = ContextChange(ContextElementKind.OTHER, "com.acme.MyElement", oldValue = "MyElement(1)", removed = true)
        val handler = ContextChange(ContextElementKind.EXCEPTION_HANDLER, "CoroutineExceptionHandler", added = true)
        val replaced = ContextChange(ContextElementKind.OTHER, "com.acme.MyElement", "MyElement(1)", "MyElement(2)")
        assertEquals(
            listOf("Dispatcher: Dispatchers.Default → Dispatchers.IO", "+CoroutineName: worker", "-MyElement: MyElement(1)", "+CoroutineExceptionHandler", "MyElement: MyElement(1) → MyElement(2)"),
            listOf(changed, named, removed, handler, replaced).map { it.describe() },
        )
        assertEquals(
            "context changed: +CoroutineName: worker, -MyElement: MyElement(1)",
            snapshot.describe(Event(nodeId = 3, kind = EventKind.CONTEXT_CHANGED, contextDiff = listOf(named, removed))),
        )
        assertEquals(
            "dispatcher changed: Dispatchers.Default → Dispatchers.IO",
            snapshot.describe(Event(nodeId = 3, kind = EventKind.DISPATCHER_CHANGED, contextDiff = listOf(changed))),
        )
        // A coroutine that had no dispatcher and got one, and the other way round.
        val gained = ContextChange(ContextElementKind.DISPATCHER, "Dispatcher", newValue = "Dispatchers.IO", added = true)
        val lost = ContextChange(ContextElementKind.DISPATCHER, "Dispatcher", oldValue = "Dispatchers.IO", removed = true)
        assertEquals("dispatcher changed: none → Dispatchers.IO", snapshot.describe(Event(kind = EventKind.DISPATCHER_CHANGED, contextDiff = listOf(gained))))
        assertEquals("dispatcher changed: Dispatchers.IO → none", snapshot.describe(Event(kind = EventKind.DISPATCHER_CHANGED, contextDiff = listOf(lost))))
    }

    @Test
    fun aSuspensionIsLocatedAtTheInnermostFrameOutsideTheRuntime() {
        // siteOf: "`delay` suspends in `Delay.kt`; what one wants to know is who called `delay`." Stacks are logical
        // (TRACE_FORMAT, "Inlined code"): "a construct inside a project's inline function has its site there, in a
        // library's inline function at the project's call."
        fun suspendedAt(vararg stack: Int) = snapshot.describe(Event(nodeId = 3, kind = EventKind.SUSPENDED, stack = stack.toList()))
        assertEquals("suspended at Main.kt:12", suspendedAt(1, 6, 2))
        assertEquals("suspended at Main.kt:12", suspendedAt(1, 3, 2), "the body of a library's inline function is the library's")
        assertEquals("suspended at Util.kt:7", suspendedAt(1, 4, 2), "the body of the project's inline function is the place")
        assertEquals("suspended at Main.kt:12", suspendedAt(99, 1, 2), "a frame id nobody defined is passed over")
        assertEquals("suspended", suspendedAt())
        assertEquals("suspended", suspendedAt(1, 5), "the site has no file name to show")

        assertEquals(snapshot.frame(4), snapshot.siteOf(listOf(1, 4, 2)))
        assertNull(snapshot.siteOf(emptyList()))
        assertNull(snapshot.siteOf(listOf(0, 99)))
    }

    @Test
    fun runtimeFramesAreThoseOfTheJdkTheKotlinStandardLibraryAndKotlinxCoroutines() {
        // "the innermost frame of the creating stack that is outside the JDK, the Kotlin standard library and
        // kotlinx.coroutines". A package that merely starts with the same letters is somebody's code.
        val runtime = listOf(
            "java.lang.Thread", "java.util.concurrent.ForkJoinPool", "javax.swing.SwingUtilities", "jdk.internal.misc.Unsafe", "sun.nio.ch.NioSocketImpl",
            "kotlin.coroutines.jvm.internal.BaseContinuationImpl", "kotlin.SynchronizedKt", "kotlinx.coroutines.BuildersKt", "kotlinx.coroutines.flow.FlowKt",
        )
        val application = listOf(
            "demo.MainKt", "MainKt", "javafx.application.Platform", "kotlinx.coroutree.samples.Launch", "kotlinx.serialization.json.Json",
            "kotlinx.coroutinesx.Mine", "kotlinlang.Sample", "sunny.Day", "com.acme.java.Util", "io.ktor.server.Engine",
        )
        assertEquals(emptyList(), runtime.filterNot { StackFrameDef(className = it).isRuntimeFrame })
        assertEquals(emptyList(), application.filter { StackFrameDef(className = it).isRuntimeFrame })
    }

    @Test
    fun framesArePrintedTheWayTheJvmPrintsThemAndInlinedBodiesSaySo() {
        // KDoc: `com.acme.MainKt.main(Main.kt:12)`; `inline com.acme.UtilKt.retry(Util.kt:7)`; method_name is "empty
        // where the class file did not tell"; 0 is "unknown" for line numbers.
        val frame = StackFrameDef(1, "com.acme.MainKt", "main", "Main.kt", 12)
        assertEquals("com.acme.MainKt.main(Main.kt:12)", frame.qualified)
        assertEquals("com.acme.MainKt.main(Main.kt)", frame.copy(line = 0).qualified)
        assertEquals("com.acme.MainKt.main(Unknown Source)", frame.copy(fileName = "", line = 12).qualified)
        val inlined = StackFrameDef(2, "com.acme.UtilKt", "retry", "Util.kt", 7, inlined = true)
        assertEquals("inline com.acme.UtilKt.retry(Util.kt:7)", inlined.qualified)
        assertEquals("inline com.acme.UtilKt(Util.kt:7)", inlined.copy(methodName = "").qualified)
        assertEquals(listOf("Main.kt:12", "Main.kt", ""), listOf(frame, frame.copy(line = 0), frame.copy(fileName = "")).map { it.shortLocation })
    }

    @Test
    fun aNodeIsTitledByItsConstructAndTheNameItWasGiven() {
        // KDoc: `launch "worker"`, `Thread "main"`, `withContext`.
        fun title(info: NodeInfo) = storeOf(listOf(Frame(event = Event(seq = 1, nodeId = info.id, kind = EventKind.LAUNCHED, node = info)))).snapshot().node(info.id)!!.title
        assertEquals("launch \"worker\"", title(NodeInfo(1, NodeKind.COROUTINE, "launch", "worker")))
        assertEquals("withContext", title(NodeInfo(1, NodeKind.CONTEXT_CHANGE, "withContext")))
        assertEquals("Thread \"main\"", title(NodeInfo(1, NodeKind.THREAD, name = "main")))
        assertEquals("Thread.start \"worker-1\"", title(NodeInfo(1, NodeKind.THREAD, "Thread.start", "worker-1")))
        // No construct: the kind stands in, in words.
        assertEquals("Pool \"DefaultDispatcher\"", title(NodeInfo(1, NodeKind.POOL, name = "DefaultDispatcher")))
        assertEquals("context change", title(NodeInfo(1, NodeKind.CONTEXT_CHANGE)))
        assertEquals("coroutine", title(NodeInfo(1, NodeKind.COROUTINE)))
        assertEquals("node #7", title(NodeInfo(7)))
    }

    @Test
    fun blockingReasonsAreSpelledAsInCode() {
        // DESIGN §2.2: ThreadBlocked(reason: monitor | wait | join | park | sleep | io | runBlocking).
        assertEquals(
            listOf("monitor", "wait", "join", "park", "sleep", "io", "runBlocking"),
            (BlockReason.entries - BlockReason.UNSPECIFIED).map { it.label },
        )
    }
}
