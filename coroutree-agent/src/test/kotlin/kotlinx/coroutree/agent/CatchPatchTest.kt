package kotlinx.coroutree.agent

import com.acme.app.Catching
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `exceptionCaught` is reported from the catch blocks of project classes that handle the exception. A handler that
 * only rethrows it (`finally`, `synchronized`, try-with-resources, clean up and rethrow) reports nothing, or the
 * trace shows one exception as caught at each such handler on its path. The JVM verifies the rewritten class, and
 * each test runs it next to the class as compiled.
 */
internal class CatchPatchTest {
    @BeforeEach
    fun forget() {
        HookLog.take()
    }

    @Test
    fun aCatchBlockThatHandlesReportsTheExceptionItCaught() {
        val failure = IllegalStateException("boom")
        assertEquals(listOf(failure), caught("handles", failure))
        assertEquals(emptyList<Any>(), caught("handles", IllegalArgumentException("not for this catch")), "an exception that passes by is not caught")
        assertEquals(listOf(failure), caught("ignores", throwing(failure)), "an empty catch block handles as well as any")
        assertEquals(emptyList<Any>(), caught("ignores", FINE))
    }

    @Test
    fun handlersThatOnlyPassTheExceptionOnReportNothing() {
        val failure = IllegalStateException("boom")
        val log = ArrayList<String>()
        assertEquals(emptyList<Any>(), caught("finallyOnly", throwing(failure), log))
        assertEquals(listOf("finally", "finally"), log, "once per run: the original class and the rewritten one")
        assertEquals(emptyList<Any>(), caught("locked", Any(), throwing(failure)))
        assertSame(failure, Lab.call(patched, "locked", Any(), throwing(failure)).thrown, "the exception passes through untouched")
        val resource = AutoCloseable { log.add("closed") }
        assertEquals(emptyList<Any>(), caught("resource", resource, throwing(failure)))
        assertEquals(emptyList<Any>(), caught("resource", resource, FINE))
        // A typed handler is a cleanup handler too when every exit is `throw e` of the exception it caught.
        log.clear()
        assertEquals(emptyList<Any>(), caught("cleansUpAndRethrows", throwing(failure), log))
        assertEquals(listOf("cleanup", "cleanup"), log)
    }

    @Test
    fun aHandlerThatMayDoAnythingButRethrowTheSameExceptionReports() {
        val failure = IllegalStateException("boom")
        assertEquals(listOf(failure), caught("wraps", throwing(failure)), "another exception leaves: this one was handled")
        assertEquals(listOf(failure), caught("rethrowsSometimes", throwing(failure), false))
        // The decision is made from the bytecode: a handler with one exit that doesn't rethrow reports on every run.
        assertEquals(listOf(failure), caught("rethrowsSometimes", throwing(failure), true))
        assertEquals(listOf(failure), caught("throwsSomethingElseFromTheSameVariable", throwing(failure)), "the variable is another exception by then")
    }

    @Test
    fun everyCatchReportsOncePerExceptionItCatches() {
        val state = IllegalStateException("state")
        val argument = IllegalArgumentException("argument")
        // A multi-catch is two try-catch blocks with one handler, so it reports once.
        assertEquals(listOf(state), caught("multi", throwing(state)))
        assertEquals(listOf(argument), caught("multi", throwing(argument)))
        assertEquals(emptyList<Any>(), caught("multi", throwing(UnsupportedOperationException("neither"))))

        val nested = caught("nested", state)
        assertEquals(2, nested.size, "the inner catch, then the outer one with what the inner threw")
        assertSame(state, nested[0])
        assertSame(state, (nested[1] as Throwable).cause)

        assertEquals(listOf(state, argument), caught("inALoop", listOf(throwing(state), FINE, throwing(argument))))
    }

    companion object {
        private lateinit var patched: Class<*>

        @JvmStatic
        @BeforeAll
        fun rewrite() {
            val rewritten = Lab.transformer("include=com.acme")
                .transform(Catching::class.java.classLoader, Lab.internalName(Catching::class.java), null, null, Lab.bytesOf(Catching::class.java))
            assertNotNull(rewritten, "a project class with catch blocks is instrumented")
            patched = Lab.Loader().with(Catching::class.java, rewritten).verified(Catching::class.java)
        }

        private val FINE = Runnable {}

        private fun throwing(failure: RuntimeException) = Runnable { throw failure }

        /** Checks that the patched class gives the same outcome as the class as compiled. Returns the exceptions it reported as caught, in order. */
        private fun caught(method: String, vararg arguments: Any?): List<Any?> {
            val expected = Lab.call(Catching::class.java, method, *arguments)
            HookLog.take()
            val actual = Lab.call(patched, method, *arguments)
            assertEquals(expected.toString(), actual.toString(), method)
            return HookLog.take().map { call ->
                assertEquals("exceptionCaught", call[0])
                call[1]
            }
        }
    }
}
