package kotlinx.coroutree.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

/**
 * `Describe` runs application code (`toString`, `getMessage`, `getStackTrace`) inside a hook, on the application's
 * thread. Whatever that code does, a description comes out and nothing is thrown. Nothing that differs from run to
 * run (an identity hash) or has no bound (a `toString` of megabytes) gets into the trace.
 */
internal class DescribeTest {
    private class Plain

    private class Named(private val text: String?) {
        override fun toString(): String = text!!
    }

    private class LikeAJob {
        override fun toString(): String = "StandaloneCoroutine{Active}@" + Integer.toHexString(System.identityHashCode(this))
    }

    private class Throwing(private val failure: Throwable) {
        override fun toString(): String = throw failure
    }

    private class Recursive {
        override fun toString(): String = "<$this>"
    }

    private class HashThrows {
        override fun hashCode(): Int = throw UnsupportedOperationException("hashCode of an application object")

        override fun toString(): String = "fine"
    }

    @Test
    fun aValueIsItsToStringWithoutWhatDiffersFromRunToRun() {
        assertEquals("Dispatchers.Default", Describe.value(Named("Dispatchers.Default")))
        assertEquals("", Describe.value(Named("")))
        assertEquals("DescribeTest\$Plain", Describe.value(Plain()), "the default toString says nothing beyond the class; outer classes are kept")
        assertEquals("StandaloneCoroutine{Active}", Describe.value(LikeAJob()), "Name@identity, as kotlinx.coroutines prints its objects")
        assertEquals("user@example.com", Describe.value(Named("user@example.com")), "an @ is not an identity")
    }

    @Test
    fun aValueHasABound() {
        val described = Describe.value(Named("x".repeat(1_000_000)))
        assertEquals("x".repeat(200) + "…", described)
        assertEquals("y".repeat(200), Describe.value(Named("y".repeat(200))), "what fits is not marked as cut")
    }

    @Test
    fun applicationCodeThatMisbehavesStillGetsADescriptionAndNothingIsThrown() {
        assertEquals("DescribeTest\$Throwing (toString threw java.lang.IllegalStateException)", Describe.value(Throwing(IllegalStateException("no"))))
        // Errors are caught too, not only exceptions: a hook never throws into the application.
        assertEquals("DescribeTest\$Throwing (toString threw java.lang.AssertionError)", Describe.value(Throwing(AssertionError("no"))))
        val recursive = Describe.value(Recursive())
        assertTrue(recursive.startsWith("DescribeTest\$Recursive (toString threw java.lang.StackOverflowError"), recursive)
        val ofNull = Describe.value(Named(null))
        assertNotNull(ofNull)
        assertTrue("DescribeTest\$Named" in ofNull, ofNull)
        val hashless = Describe.value(HashThrows())
        assertNotNull(hashless)
        assertTrue("DescribeTest\$HashThrows" in hashless || hashless == "fine", hashless)
    }

    @Test
    fun anExceptionIsDescribedByClassMessageIdentityAndWhereItWasThrown() {
        val failure = IllegalStateException("boom")
        val def = Describe.exception(failure, true, 3)
        assertEquals("java.lang.IllegalStateException", def.className)
        assertEquals("boom", def.message)
        assertEquals(System.identityHashCode(failure), def.identity)
        assertFalse(def.cancellation)
        assertEquals(3, def.stack.size, "cut to the configured depth")
        assertEquals("kotlinx.coroutree.runtime.DescribeTest", def.stack[0].className, "the throwable's own stack, not the hook's")

        assertNull(Describe.exception(failure, false, 3).stack, "only where the format asks for it")
        assertEquals("", Describe.exception(RuntimeException(), false, 0).message, "no message is an empty one")
        assertTrue(Describe.exception(CancellationException("cancelled"), false, 0).cancellation)
        assertTrue(Describe.exception(object : CancellationException() {}, false, 0).cancellation, "any CancellationException, kotlinx.coroutines' own are subclasses")

        val huge = Describe.exception(RuntimeException("m".repeat(100_000)), false, 0)
        assertEquals("m".repeat(1000) + "…", huge.message)
    }

    @Test
    fun anExceptionWhoseOwnMethodsThrowIsStillAnException() {
        val hostile = object : RuntimeException() {
            override val message: String
                get() = throw IllegalStateException("getMessage of an application exception")

            override fun getStackTrace(): Array<StackTraceElement> = throw StackOverflowError()
        }
        val def = Describe.exception(hostile, true, 16)
        assertEquals(hostile.javaClass.name, def.className)
        assertEquals("", def.message)
        assertNull(def.stack)
        assertEquals(System.identityHashCode(hostile), def.identity)
    }
}
