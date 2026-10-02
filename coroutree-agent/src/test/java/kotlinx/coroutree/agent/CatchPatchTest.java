package kotlinx.coroutree.agent;

import com.acme.app.Catching;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * "Handled by catch" comes from the catch blocks of project classes that handle something. A handler that only passes
 * the exception on (finally, synchronized, try-with-resources, clean up and rethrow) reports nothing, or every
 * exception would show as caught once per resource it passes on its way up. The class is rewritten the way the
 * agent rewrites it, verified by the JVM, and run next to the class as compiled.
 */
class CatchPatchTest {
    private static Class<?> patched;

    @BeforeAll
    static void rewrite() {
        byte[] rewritten = Lab.transformer("include=com.acme").transform(Catching.class.getClassLoader(), Lab.internalName(Catching.class), null, null, Lab.bytesOf(Catching.class));
        assertNotNull(rewritten, "a project class with catch blocks is instrumented");
        patched = new Lab.Loader().with(Catching.class, rewritten).verified(Catching.class);
    }

    @BeforeEach
    void forget() {
        HookLog.take();
    }

    private static final Runnable FINE = () -> {};

    private static Runnable throwing(RuntimeException failure) {
        return () -> {
            throw failure;
        };
    }

    /** Same outcome as the class as compiled; returns the exceptions reported as caught, in order. */
    private static List<Object> caught(String method, Object... arguments) {
        Lab.Outcome expected = Lab.call(Catching.class, method, arguments);
        HookLog.take();
        Lab.Outcome actual = Lab.call(patched, method, arguments);
        assertEquals(expected.toString(), actual.toString(), method);
        List<Object> reported = new ArrayList<>();
        for (Object[] call : HookLog.take()) {
            assertEquals("exceptionCaught", call[0]);
            reported.add(call[1]);
        }
        return reported;
    }

    @Test
    void aCatchBlockThatHandlesReportsTheExceptionItCaught() {
        IllegalStateException failure = new IllegalStateException("boom");
        assertEquals(List.of(failure), caught("handles", failure));
        assertEquals(List.of(), caught("handles", new IllegalArgumentException("not for this catch")), "an exception that passes by is not caught");
        assertEquals(List.of(failure), caught("ignores", throwing(failure)), "an empty catch block handles as well as any");
        assertEquals(List.of(), caught("ignores", FINE));
    }

    @Test
    void handlersThatOnlyPassTheExceptionOnReportNothing() {
        IllegalStateException failure = new IllegalStateException("boom");
        List<String> log = new ArrayList<>();
        assertEquals(List.of(), caught("finallyOnly", throwing(failure), log));
        assertEquals(List.of("finally", "finally"), log, "once per run: the original class and the rewritten one");
        assertEquals(List.of(), caught("locked", new Object(), throwing(failure)));
        assertSame(failure, Lab.call(patched, "locked", new Object(), throwing(failure)).thrown, "the exception passes through untouched");
        AutoCloseable resource = () -> log.add("closed");
        assertEquals(List.of(), caught("resource", resource, throwing(failure)));
        assertEquals(List.of(), caught("resource", resource, FINE));
        // Typed, and still cleanup in disguise: whatever it does on the way, every way out is `throw e` of what it caught.
        log.clear();
        assertEquals(List.of(), caught("cleansUpAndRethrows", throwing(failure), log));
        assertEquals(List.of("cleanup", "cleanup"), log);
    }

    @Test
    void aHandlerThatMayDoAnythingButRethrowTheSameExceptionReports() {
        IllegalStateException failure = new IllegalStateException("boom");
        assertEquals(List.of(failure), caught("wraps", throwing(failure)), "another exception leaves: this one was handled");
        assertEquals(List.of(failure), caught("rethrowsSometimes", throwing(failure), false));
        // Decided when the class is loaded, not when it runs: a handler with a way out that handles is one that handles.
        assertEquals(List.of(failure), caught("rethrowsSometimes", throwing(failure), true));
        assertEquals(List.of(failure), caught("throwsSomethingElseFromTheSameVariable", throwing(failure)), "the variable is another exception by then");
    }

    @Test
    void everyCatchReportsOncePerExceptionItCatches() {
        IllegalStateException state = new IllegalStateException("state");
        IllegalArgumentException argument = new IllegalArgumentException("argument");
        // One handler for two types is one catch block.
        assertEquals(List.of(state), caught("multi", throwing(state)));
        assertEquals(List.of(argument), caught("multi", throwing(argument)));
        assertEquals(List.of(), caught("multi", throwing(new UnsupportedOperationException("neither"))));

        List<Object> nested = caught("nested", state);
        assertEquals(2, nested.size(), "the inner catch, then the outer one with what the inner threw");
        assertSame(state, nested.get(0));
        assertSame(state, ((Throwable) nested.get(1)).getCause());

        assertEquals(List.of(state, argument), caught("inALoop", List.of(throwing(state), FINE, throwing(argument))));
    }
}
