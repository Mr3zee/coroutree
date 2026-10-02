package kotlinx.coroutree.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a job's node remembers about exceptions, so that one exception on its way through the tree is told once per
 * place it passes — and remembers weakly: a node must not keep application objects alive longer than the job does.
 */
@Timeout(60)
class JobNodeTest {
    static {
        if (Tracer.config == null) Tracer.config = AgentConfig.parse(TracedJvm.GATE_OPTIONS);
    }

    private static final AtomicLong IDS = new AtomicLong(8_000_000);

    private static JobNode node() {
        return new JobNode(IDS.incrementAndGet(), false, false);
    }

    @Test
    void anExceptionIsPropagatedToTheParentOnceThoughTheLibraryTellsTheParentTwice() {
        // kotlinx.coroutines tells a parent about a failing child when the child starts cancelling and when it is final.
        JobNode child = node();
        Throwable failure = new IllegalStateException("boom"), another = new IllegalStateException("boom");
        assertFalse(child.hasPropagated(failure));
        assertTrue(child.markPropagated(failure));
        assertTrue(child.hasPropagated(failure));
        assertFalse(child.markPropagated(failure), "the second report of the same exception");
        assertFalse(child.hasPropagated(another), "equal is not the same: exceptions are followed by identity");
        assertTrue(child.markPropagated(another));

        // Stopping at a supervisor is a fact of its own, told once as well.
        assertFalse(child.hasStopped(another));
        assertTrue(child.markStopped(another));
        assertFalse(child.markStopped(another));
        assertTrue(child.hasStopped(another));
    }

    @Test
    void anExceptionAScopeThrewAtItsCallerIsRecognisedWhenTheCallerEndsWithIt() {
        // The caller's rethrow is not reported a second time as thrown. kotlinx.coroutines rethrows either the same
        // instance or, with stack trace recovery, a copy whose cause is the original.
        JobNode caller = node();
        Throwable original = new IllegalStateException("from the scope");
        assertFalse(caller.isRethrowOfReceived(original), "nothing was received yet");
        caller.markReceived(original);
        assertTrue(caller.isRethrowOfReceived(original));
        assertTrue(caller.isRethrowOfReceived(new IllegalStateException("recovered", original)));
        assertTrue(caller.isRethrowOfReceived(new RuntimeException(new IllegalStateException("recovered twice", original))));
        assertFalse(caller.isRethrowOfReceived(new IllegalStateException("from the scope")), "the caller's own exception that happens to read the same");
        assertFalse(caller.isRethrowOfReceived(null));

        // A chain of causes that leads back into itself is application data like any other.
        RuntimeException a = new RuntimeException("a"), b = new RuntimeException("b", a);
        a.initCause(b);
        assertFalse(caller.isRethrowOfReceived(a));

        // The latest one counts.
        Throwable later = new IllegalArgumentException("from another scope");
        caller.markReceived(later);
        assertTrue(caller.isRethrowOfReceived(later));
        assertFalse(caller.isRethrowOfReceived(original));
    }

    @Test
    void aNodeDoesNotKeepTheExceptionsItRemembersAlive() throws Exception {
        JobNode node = node();
        WeakReference<Throwable> failure = remember(node);
        for (int i = 0; i < 200 && failure.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(failure.get(), "an exception may hold a whole object graph of the application's");
        assertFalse(node.isRethrowOfReceived(new IllegalStateException("another")));
        assertTrue(node.markPropagated(new IllegalStateException("another")));
    }

    private static WeakReference<Throwable> remember(JobNode node) {
        Throwable failure = new IllegalStateException("boom");
        node.markPropagated(failure);
        node.markStopped(failure);
        node.markReceived(failure);
        return new WeakReference<>(failure);
    }
}
