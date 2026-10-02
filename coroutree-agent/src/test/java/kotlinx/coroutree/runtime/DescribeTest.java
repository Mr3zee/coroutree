package kotlinx.coroutree.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application objects become trace strings by running application code, inside a hook, on the application's thread:
 * whatever that code does, a description comes out, nothing is thrown, and nothing that differs from run to run
 * (identity hashes) or has no bound (a toString of megabytes) gets into the trace.
 */
class DescribeTest {
    private static final class Plain {
    }

    private static final class Named {
        private final String text;

        Named(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private static final class LikeAJob {
        @Override
        public String toString() {
            return "StandaloneCoroutine{Active}@" + Integer.toHexString(System.identityHashCode(this));
        }
    }

    private static final class Throwing {
        private final Throwable failure;

        Throwing(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public String toString() {
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
        }
    }

    private static final class Recursive {
        @Override
        public String toString() {
            return "<" + this + ">";
        }
    }

    private static final class HashThrows {
        @Override
        public int hashCode() {
            throw new UnsupportedOperationException("hashCode of an application object");
        }

        @Override
        public String toString() {
            return "fine";
        }
    }

    @Test
    void aValueIsItsToStringWithoutWhatDiffersFromRunToRun() {
        assertEquals("Dispatchers.Default", Describe.value(new Named("Dispatchers.Default")));
        assertEquals("", Describe.value(new Named("")));
        assertEquals("DescribeTest$Plain", Describe.value(new Plain()), "the default toString says nothing beyond the class; outer classes are kept");
        assertEquals("StandaloneCoroutine{Active}", Describe.value(new LikeAJob()), "Name@identity, as kotlinx.coroutines prints its objects");
        assertEquals("user@example.com", Describe.value(new Named("user@example.com")), "an @ is not an identity");
    }

    @Test
    void aValueHasABound() {
        String described = Describe.value(new Named("x".repeat(1_000_000)));
        assertEquals("x".repeat(200) + "…", described);
        assertEquals("y".repeat(200), Describe.value(new Named("y".repeat(200))), "what fits is not marked as cut");
    }

    @Test
    void applicationCodeThatMisbehavesStillGetsADescriptionAndNothingIsThrown() {
        assertEquals("DescribeTest$Throwing (toString threw java.lang.IllegalStateException)", Describe.value(new Throwing(new IllegalStateException("no"))));
        // Errors too: a hook must not let an AssertionError of the application's toString out into the application.
        assertEquals("DescribeTest$Throwing (toString threw java.lang.AssertionError)", Describe.value(new Throwing(new AssertionError("no"))));
        String recursive = Describe.value(new Recursive());
        assertTrue(recursive.startsWith("DescribeTest$Recursive (toString threw java.lang.StackOverflowError"), recursive);
        String ofNull = Describe.value(new Named(null));
        assertNotNull(ofNull);
        assertTrue(ofNull.contains("DescribeTest$Named"), ofNull);
        String hashless = Describe.value(new HashThrows());
        assertNotNull(hashless);
        assertTrue(hashless.contains("DescribeTest$HashThrows") || hashless.equals("fine"), hashless);
    }

    @Test
    void anExceptionIsDescribedByClassMessageIdentityAndWhereItWasThrown() {
        IllegalStateException failure = new IllegalStateException("boom");
        TraceEvent.ExceptionDef def = Describe.exception(failure, true, 3);
        assertEquals("java.lang.IllegalStateException", def.className);
        assertEquals("boom", def.message);
        assertEquals(System.identityHashCode(failure), def.identity);
        assertFalse(def.cancellation);
        assertEquals(3, def.stack.length, "cut to the configured depth");
        assertEquals("kotlinx.coroutree.runtime.DescribeTest", def.stack[0].className, "the throwable's own stack, not the hook's");

        assertNull(Describe.exception(failure, false, 3).stack, "only where the format asks for it");
        assertEquals("", Describe.exception(new RuntimeException(), false, 0).message, "no message is an empty one");
        assertTrue(Describe.exception(new CancellationException("cancelled"), false, 0).cancellation);
        assertTrue(Describe.exception(new CancellationException() { }, false, 0).cancellation, "any CancellationException, kotlinx.coroutines' own are subclasses");

        TraceEvent.ExceptionDef huge = Describe.exception(new RuntimeException("m".repeat(100_000)), false, 0);
        assertEquals("m".repeat(1000) + "…", huge.message);
    }

    @Test
    void anExceptionWhoseOwnMethodsThrowIsStillAnException() {
        RuntimeException hostile = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("getMessage of an application exception");
            }

            @Override
            public StackTraceElement[] getStackTrace() {
                throw new StackOverflowError();
            }
        };
        TraceEvent.ExceptionDef def = Describe.exception(hostile, true, 16);
        assertEquals(hostile.getClass().getName(), def.className);
        assertEquals("", def.message);
        assertNull(def.stack);
        assertEquals(System.identityHashCode(hostile), def.identity);
    }
}
