package kotlinx.coroutree.runtime;

import kotlin.coroutines.AbstractCoroutineContextElement;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.jvm.internal.CoroutineStackFrame;
import kotlinx.coroutines.CoroutineExceptionHandler;
import kotlinx.coroutines.CoroutineName;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;
import kotlinx.coroutines.ThreadContextElement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the runtime, which cannot link against Kotlin, reads Kotlin's objects: against the real kotlin-stdlib and
 * kotlinx.coroutines, uninstrumented (so no job here is one "this agent instruments"). What a context element looks
 * like in the trace is TRACE_FORMAT's {@code ContextElement}.
 */
@Timeout(60)
class KotlinAccessTest {
    private static KotlinAccess access;
    private static Job job;

    @BeforeAll
    static void resolve() {
        job = JobKt.Job(null);
        access = KotlinAccess.forJob(job);
        assertNotNull(access, "kotlin-stdlib and kotlinx.coroutines of the version under test look the way the agent expects");
        assertSame(access, KotlinAccess.get());
        assertSame(access, KotlinAccess.forContinuation(new Object()), "one per JVM, from the first loader seen");
    }

    private static final class Mdc extends AbstractCoroutineContextElement implements ThreadContextElement<String> {
        static final CoroutineContext.Key<Mdc> KEY = new CoroutineContext.Key<>() {
        };

        Mdc() {
            super(KEY);
        }

        @Override
        public String updateThreadContext(CoroutineContext context) {
            return "";
        }

        @Override
        public void restoreThreadContext(CoroutineContext context, String oldState) {
        }

        @Override
        public String toString() {
            return "MDC{user=7}";
        }
    }

    private static final class Tenant extends AbstractCoroutineContextElement {
        static final CoroutineContext.Key<Tenant> KEY = new CoroutineContext.Key<>() {
        };

        Tenant() {
            super(KEY);
        }
    }

    private static class Handler extends AbstractCoroutineContextElement implements CoroutineExceptionHandler {
        Handler() {
            super(CoroutineExceptionHandler.Key);
        }

        @Override
        public void handleException(CoroutineContext context, Throwable exception) {
        }
    }

    private static final class NamedHandler extends Handler {
        @Override
        public String toString() {
            return "LoggingHandler";
        }
    }

    private static String entry(Object element) throws Throwable {
        ContextEntry entry = access.describe(element);
        if (entry == null) return "hidden";
        String kind = switch (entry.kind) {
            case Wire.CTX_JOB -> "JOB";
            case Wire.CTX_DISPATCHER -> "DISPATCHER";
            case Wire.CTX_NAME -> "NAME";
            case Wire.CTX_EXCEPTION_HANDLER -> "EXCEPTION_HANDLER";
            case Wire.CTX_OTHER -> "OTHER";
            default -> "kind " + entry.kind;
        };
        return kind + " " + entry.key + " = '" + entry.value + "'" + (entry.threadContextElement ? " (thread context element)" : "");
    }

    @Test
    void aContextElementIsDescribedByKindKeyAndValue() throws Throwable {
        assertEquals("JOB Job = ''", entry(job), "the node itself stands for its Job");
        assertEquals("NAME CoroutineName = 'server'", entry(new CoroutineName("server")));
        assertEquals("DISPATCHER Dispatcher = 'Dispatchers.Default'", entry(Dispatchers.getDefault()));
        assertEquals("DISPATCHER Dispatcher = 'Dispatchers.IO'", entry(Dispatchers.getIO()));
        assertEquals("EXCEPTION_HANDLER CoroutineExceptionHandler = 'LoggingHandler'", entry(new NamedHandler()));
        assertEquals("EXCEPTION_HANDLER CoroutineExceptionHandler = ''", entry(new Handler()), "usually a lambda, whose synthetic class name is noise, not a value");
        assertEquals("OTHER " + Mdc.class.getName() + " = 'MDC{user=7}' (thread context element)", entry(new Mdc()));
        assertEquals("OTHER " + Tenant.class.getName() + " = 'KotlinAccessTest$Tenant'", entry(new Tenant()), "the default toString without its hash");
    }

    @Test
    void anEntryKeepsItsElementForInheritanceByIdentityButNeverAJob() throws Throwable {
        CoroutineName name = new CoroutineName("server");
        assertSame(name, access.describe(name).element);
        assertNull(access.describe(job).element, "entries are inherited by descendants' nodes, which must not pin their ancestors' jobs");
    }

    @Test
    void theLibrarysOwnBookkeepingIsNotInAContext() throws Throwable {
        // The numbering of coroutines in debug mode (every JVM with -ea is in it), and the marker withContext leaves.
        assertEquals("hidden", entry(new kotlinx.coroutines.CoroutineId(7)));
        Field marker = Class.forName("kotlinx.coroutines.UndispatchedMarker").getDeclaredField("INSTANCE");
        marker.setAccessible(true);
        assertEquals("hidden", entry(marker.get(null)));
    }

    @Test
    void theElementsOfAContextAreAllOfThemLeftmostFirst() throws Throwable {
        CoroutineName name = new CoroutineName("server");
        Mdc mdc = new Mdc();
        Tenant tenant = new Tenant();
        assertEquals(List.of(), access.elements(EmptyCoroutineContext.INSTANCE));
        assertEquals(List.of(name), access.elements(name));
        assertEquals(List.of(job, name, mdc, tenant), access.elements(job.plus(name).plus(mdc).plus(tenant)));
        // The interceptor is kept last by the standard library itself, wherever it was added.
        List<Object> withDispatcher = access.elements(job.plus(Dispatchers.getDefault()).plus(name));
        assertEquals(3, withDispatcher.size());
        assertTrue(withDispatcher.containsAll(List.of(job, name, Dispatchers.getDefault())), withDispatcher.toString());
    }

    @Test
    void aJobThisAgentDidNotInstrumentIsAJobButNotANode() throws Throwable {
        CoroutineContext context = job.plus(new CoroutineName("server"));
        assertTrue(access.hasJob(context));
        assertNull(access.job(context), "no tag on it: its class was loaded without the agent");
        assertFalse(access.hasJob(new CoroutineName("alone")));
        assertFalse(access.hasJob(EmptyCoroutineContext.INSTANCE));
        assertFalse(access.hasJob(null));
        assertNull(access.job(null));

        assertTrue(access.hasExceptionHandler(context.plus(new Handler())));
        assertFalse(access.hasExceptionHandler(context));
        assertFalse(access.hasExceptionHandler(null));
    }

    @Test
    void aFailureIsReadOffAJobsFinalStateAndOffAResult() throws Throwable {
        IllegalStateException failure = new IllegalStateException("boom");
        assertSame(failure, access.failureOf(new kotlinx.coroutines.CompletedExceptionally(failure, false)));
        assertNull(access.failureOf("a result"));
        assertNull(access.failureOf(null));
        assertNull(access.failureOf(failure), "an exception that is the value a coroutine returned is a value");

        assertSame(failure, access.failureOfResult(kotlin.ResultKt.createFailure(failure)));
        assertNull(access.failureOfResult("a result"));
        assertNull(access.failureOfResult(null));
        assertNull(access.failureOfResult(failure));
    }

    /** A frame somebody wrote by hand, as a flow's SafeCollector or a library's own continuation is. */
    private static final class HandWritten implements CoroutineStackFrame {
        final StackTraceElement element;
        CoroutineStackFrame caller;

        HandWritten(String method, int line, CoroutineStackFrame caller) {
            this.element = method == null ? null : new StackTraceElement("com.acme.Flow", method, "Flow.kt", line);
            this.caller = caller;
        }

        @Override
        public CoroutineStackFrame getCallerFrame() {
            return caller;
        }

        @Override
        public StackTraceElement getStackTraceElement() {
            return element;
        }
    }

    private static List<String> methods(StackFrameRef[] stack) {
        List<String> result = new ArrayList<>();
        for (StackFrameRef frame : stack) result.add(frame.methodName + ":" + frame.line);
        return result;
    }

    @Test
    void theStackOfASuspendedCoroutineIsItsChainOfFramesInnermostFirst() throws Throwable {
        HandWritten outer = new HandWritten("collect", 30, null);
        HandWritten anonymous = new HandWritten(null, 0, outer); // a frame that has no line to show
        HandWritten inner = new HandWritten("emit", 12, anonymous);
        assertEquals(List.of("emit:12", "collect:30"), methods(access.coroutineStack(inner, 16)));
        assertEquals(List.of("emit:12"), methods(access.coroutineStack(inner, 1)), "cut to the configured depth");
        assertEquals(List.of(), methods(access.coroutineStack("not a frame", 16)));
        assertEquals(List.of(), methods(access.coroutineStack(null, 16)));

        // Application code again: a chain that leads back into itself must not hang the thread that suspends.
        outer.caller = inner;
        assertEquals(16, access.coroutineStack(inner, 16).length);

        assertSame(anonymous, access.callerOf(inner));
        assertNull(access.callerOf("not a frame"));
        assertFalse(access.isCompiledFrame(inner), "a continuation somebody wrote by hand");
        assertFalse(access.isGeneratorFrame(inner));
    }
}
