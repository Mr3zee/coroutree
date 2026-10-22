package kotlinx.coroutree.runtime

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.jvm.internal.CoroutineStackFrame

/**
 * How the runtime, which cannot link against Kotlin, reads Kotlin's objects: against the real kotlin-stdlib and
 * kotlinx.coroutines, uninstrumented (so no job here is one "this agent instruments"). What a context element looks
 * like in the trace is TRACE_FORMAT's `ContextElement`.
 */
@Timeout(60)
internal class KotlinAccessTest {
    private class Mdc : AbstractCoroutineContextElement(Mdc), ThreadContextElement<String> {
        companion object Key : CoroutineContext.Key<Mdc>

        override fun updateThreadContext(context: CoroutineContext): String = ""

        override fun restoreThreadContext(context: CoroutineContext, oldState: String) {}

        override fun toString(): String = "MDC{user=7}"
    }

    /** An element with the default toString. */
    private class Tenant : AbstractCoroutineContextElement(Tenant) {
        companion object Key : CoroutineContext.Key<Tenant>
    }

    private open class Handler : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
        override fun handleException(context: CoroutineContext, exception: Throwable) {}
    }

    private class NamedHandler : Handler() {
        override fun toString(): String = "LoggingHandler"
    }

    @Test
    fun aContextElementIsDescribedByKindKeyAndValue() {
        assertEquals("JOB Job = ''", entry(job), "the node itself stands for its Job")
        assertEquals("NAME CoroutineName = 'server'", entry(CoroutineName("server")))
        assertEquals("DISPATCHER Dispatcher = 'Dispatchers.Default'", entry(Dispatchers.Default))
        assertEquals("DISPATCHER Dispatcher = 'Dispatchers.IO'", entry(Dispatchers.IO))
        assertEquals("EXCEPTION_HANDLER CoroutineExceptionHandler = 'LoggingHandler'", entry(NamedHandler()))
        assertEquals("EXCEPTION_HANDLER CoroutineExceptionHandler = ''", entry(Handler()), "usually a lambda, whose synthetic class name is noise, not a value")
        assertEquals("OTHER ${Mdc::class.java.name} = 'MDC{user=7}' (thread context element)", entry(Mdc()))
        assertEquals("OTHER ${Tenant::class.java.name} = 'KotlinAccessTest\$Tenant'", entry(Tenant()), "the default toString without its hash")
    }

    @Test
    fun anEntryKeepsItsElementForInheritanceByIdentityButNeverAJob() {
        val name = CoroutineName("server")
        assertSame(name, access.describe(name).element)
        assertNull(access.describe(job).element, "entries are inherited by descendants' nodes, which must not pin their ancestors' jobs")
    }

    @Test
    fun theLibrarysOwnBookkeepingIsNotInAContext() {
        // The numbering of coroutines in debug mode (every JVM with -ea is in it), and the marker withContext leaves.
        assertEquals("hidden", entry(internalInstance("kotlinx.coroutines.CoroutineId", 7L)))
        val marker = Class.forName("kotlinx.coroutines.UndispatchedMarker").getDeclaredField("INSTANCE")
        marker.isAccessible = true
        assertEquals("hidden", entry(marker.get(null)))
    }

    @Test
    fun theElementsOfAContextAreAllOfThemLeftmostFirst() {
        val name = CoroutineName("server")
        val mdc = Mdc()
        val tenant = Tenant()
        assertEquals(emptyList<Any>(), access.elements(EmptyCoroutineContext))
        assertEquals(listOf(name), access.elements(name))
        assertEquals(listOf(job, name, mdc, tenant), access.elements(job + name + mdc + tenant))
        // The interceptor is kept last by the standard library itself, wherever it was added.
        val withDispatcher = access.elements(job + Dispatchers.Default + name)
        assertEquals(3, withDispatcher.size)
        assertTrue(withDispatcher.containsAll(listOf(job, name, Dispatchers.Default)), withDispatcher.toString())
    }

    @Test
    fun aJobThisAgentDidNotInstrumentIsAJobButNotANode() {
        val context = job + CoroutineName("server")
        assertTrue(access.hasJob(context))
        assertNull(access.job(context), "no tag on it: its class was loaded without the agent")
        assertFalse(access.hasJob(CoroutineName("alone")))
        assertFalse(access.hasJob(EmptyCoroutineContext))
        assertFalse(access.hasJob(null))
        assertNull(access.job(null))

        assertTrue(access.hasExceptionHandler(context + Handler()))
        assertFalse(access.hasExceptionHandler(context))
        assertFalse(access.hasExceptionHandler(null))
    }

    @Test
    fun aFailureIsReadOffAJobsFinalStateAndOffAResult() {
        val failure = IllegalStateException("boom")
        assertSame(failure, access.failureOf(internalInstance("kotlinx.coroutines.CompletedExceptionally", failure, false)))
        assertNull(access.failureOf("a result"))
        assertNull(access.failureOf(null))
        assertNull(access.failureOf(failure), "an exception that is the value a coroutine returned is a value")

        assertSame(failure, access.failureOfResult(rawFailure(failure)))
        assertNull(access.failureOfResult("a result"))
        assertNull(access.failureOfResult(null))
        assertNull(access.failureOfResult(failure))
    }

    /** A frame somebody wrote by hand, as a flow's SafeCollector or a library's own continuation is. */
    private class HandWritten(method: String?, line: Int, override var callerFrame: CoroutineStackFrame?) : CoroutineStackFrame {
        private val element = method?.let { StackTraceElement("com.acme.Flow", it, "Flow.kt", line) }

        override fun getStackTraceElement(): StackTraceElement? = element
    }

    @Test
    fun theStackOfASuspendedCoroutineIsItsChainOfFramesInnermostFirst() {
        val outer = HandWritten("collect", 30, null)
        val anonymous = HandWritten(null, 0, outer) // a frame that has no line to show
        val inner = HandWritten("emit", 12, anonymous)
        assertEquals(listOf("emit:12", "collect:30"), methods(access.coroutineStack(inner, 16)))
        assertEquals(listOf("emit:12"), methods(access.coroutineStack(inner, 1)), "cut to the configured depth")
        assertEquals(emptyList<String>(), methods(access.coroutineStack("not a frame", 16)))
        assertEquals(emptyList<String>(), methods(access.coroutineStack(null, 16)))

        // Application code again: a chain that leads back into itself must not hang the thread that suspends.
        outer.callerFrame = inner
        assertEquals(16, access.coroutineStack(inner, 16).size)

        assertSame(anonymous, access.callerOf(inner))
        assertNull(access.callerOf("not a frame"))
        assertFalse(access.isCompiledFrame(inner), "a continuation somebody wrote by hand")
        assertFalse(access.isGeneratorFrame(inner))
    }

    /**
     * What Ktor's pipeline bridge (`SuspendFunctionGun`) does: the getter hands out the next saved caller and moves
     * on, so a read of it is a step, not a lookup. Ktor documents concurrent inspection of it as best effort.
     */
    private class Cursor(private vararg val saved: CoroutineStackFrame) : CoroutineStackFrame {
        var next = 0

        override val callerFrame: CoroutineStackFrame?
            get() = if (next < saved.size) saved[next++] else null

        override fun getStackTraceElement(): StackTraceElement =
            StackTraceElement("io.ktor.util.pipeline.SuspendFunctionGun", "loop", "SuspendFunctionGun.kt", 1)
    }

    @Test
    fun aWalkCutAtTheStatefulBridgeDoesNotAdvanceItsCursor() {
        val bridge = Cursor(HandWritten("a", 1, null), HandWritten("b", 2, null))

        assertEquals(listOf("loop:1"), methods(access.coroutineStack(bridge, 1)))
        assertEquals(0, bridge.next, "the limit was reached on the bridge itself: nobody asked for its caller")
    }

    @Test
    fun repeatedWalksOfAStatefulBridgeEachTakeOneStepPerReportedFrame() {
        val bridge = Cursor(HandWritten("a", 1, null), HandWritten("b", 2, null))

        // The bridge is read once to get to "a", which ends the chain. Limit 2 is met by "a" without a further read.
        assertEquals(listOf("loop:1", "a:1"), methods(access.coroutineStack(bridge, 2)))
        assertEquals(1, bridge.next)
        // The next walk sees what the bridge's cursor gives it now: that is the bridge's contract.
        assertEquals(listOf("loop:1", "b:2"), methods(access.coroutineStack(bridge, 16)))
        assertEquals(2, bridge.next)
        assertEquals(listOf("loop:1"), methods(access.coroutineStack(bridge, 16)), "an exhausted cursor ends the chain")
    }

    companion object {
        private lateinit var access: KotlinAccess
        private lateinit var job: Job

        @JvmStatic
        @BeforeAll
        fun resolve() {
            job = Job()
            val found = KotlinAccess.forJob(job)
            assertNotNull(found, "kotlin-stdlib and kotlinx.coroutines of the version under test look the way the agent expects")
            access = found
            assertSame(access, KotlinAccess.get())
            assertSame(access, KotlinAccess.forContinuation(Any()), "one per JVM, from the first loader seen")
        }

        private fun entry(element: Any?): String {
            val entry = access.describe(element) ?: return "hidden"
            val kind = when (entry.kind) {
                Wire.CTX_JOB -> "JOB"
                Wire.CTX_DISPATCHER -> "DISPATCHER"
                Wire.CTX_NAME -> "NAME"
                Wire.CTX_EXCEPTION_HANDLER -> "EXCEPTION_HANDLER"
                Wire.CTX_OTHER -> "OTHER"
                else -> "kind ${entry.kind}"
            }
            return "$kind ${entry.key} = '${entry.value}'" + (if (entry.threadContextElement) " (thread context element)" else "")
        }

        private fun methods(stack: Array<StackFrameRef>): List<String> = stack.map { "${it.methodName}:${it.line}" }

        /** An instance of a class that is `internal` to its library: public to Java and to the agent, not to Kotlin. */
        private fun internalInstance(className: String, vararg arguments: Any): Any {
            val type = Class.forName(className)
            val constructor = type.declaredConstructors.single { it.parameterCount == arguments.size }
            return constructor.newInstance(*arguments)
        }

        /** What `resumeWith` is handed for a failed `Result`: the unboxed `Result.Failure`, not the value class. */
        private fun rawFailure(failure: Throwable): Any = internalInstance("kotlin.Result\$Failure", failure)
    }
}
