package kotlinx.coroutree.runtime

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * What a job's node remembers about exceptions, so that one exception on its way through the tree is told once per
 * place it passes — and remembers weakly: a node must not keep application objects alive longer than the job does.
 */
@Timeout(60)
internal class JobNodeTest {
    @Test
    fun anExceptionIsPropagatedToTheParentOnceThoughTheLibraryTellsTheParentTwice() {
        // kotlinx.coroutines tells a parent about a failing child when the child starts cancelling and when it is final.
        val child = node()
        val failure = IllegalStateException("boom")
        val another = IllegalStateException("boom")
        assertFalse(child.hasPropagated(failure))
        assertTrue(child.markPropagated(failure))
        assertTrue(child.hasPropagated(failure))
        assertFalse(child.markPropagated(failure), "the second report of the same exception")
        assertFalse(child.hasPropagated(another), "equal is not the same: exceptions are followed by identity")
        assertTrue(child.markPropagated(another))

        // Stopping at a supervisor is a fact of its own, told once as well.
        assertFalse(child.hasStopped(another))
        assertTrue(child.markStopped(another))
        assertFalse(child.markStopped(another))
        assertTrue(child.hasStopped(another))
    }

    @Test
    fun anExceptionAScopeThrewAtItsCallerIsRecognisedWhenTheCallerEndsWithIt() {
        // The caller's rethrow is not reported a second time as thrown. kotlinx.coroutines rethrows either the same
        // instance or, with stack trace recovery, a copy whose cause is the original.
        val caller = node()
        val original = IllegalStateException("from the scope")
        assertFalse(caller.isRethrowOfReceived(original), "nothing was received yet")
        caller.markReceived(original)
        assertTrue(caller.isRethrowOfReceived(original))
        assertTrue(caller.isRethrowOfReceived(IllegalStateException("recovered", original)))
        assertTrue(caller.isRethrowOfReceived(RuntimeException(IllegalStateException("recovered twice", original))))
        assertFalse(caller.isRethrowOfReceived(IllegalStateException("from the scope")), "the caller's own exception that happens to read the same")
        assertFalse(caller.isRethrowOfReceived(null))

        // A chain of causes that leads back into itself is application data like any other.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(caller.isRethrowOfReceived(a))

        // The latest one counts.
        val later = IllegalArgumentException("from another scope")
        caller.markReceived(later)
        assertTrue(caller.isRethrowOfReceived(later))
        assertFalse(caller.isRethrowOfReceived(original))
    }

    @Test
    fun aNodeDoesNotKeepTheExceptionsItRemembersAlive() {
        val node = node()
        val failure = remember(node)
        var i = 0
        while (i < 200 && failure.get() != null) {
            System.gc()
            Thread.sleep(10)
            i++
        }
        assertNull(failure.get(), "an exception may hold a whole object graph of the application's")
        assertFalse(node.isRethrowOfReceived(IllegalStateException("another")))
        assertTrue(node.markPropagated(IllegalStateException("another")))
    }

    companion object {
        init {
            if (Tracer.config == null) Tracer.config = AgentConfig.parse(TracedJvm.GATE_OPTIONS)
        }

        private val IDS = AtomicLong(8_000_000)

        private fun node() = JobNode(IDS.incrementAndGet(), false, false)

        private fun remember(node: JobNode): WeakReference<Throwable> {
            val failure = IllegalStateException("boom")
            node.markPropagated(failure)
            node.markStopped(failure)
            node.markReceived(failure)
            return WeakReference(failure)
        }
    }
}
