package kotlinx.coroutree.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The runtime lives in the bootstrap class loader and cannot link against Kotlin, so everything it needs from
 * kotlin-stdlib and kotlinx.coroutines goes through method handles resolved from the application's class loader.
 *
 * One instance per JVM, resolved from the loader of the first job seen. An application that loads several copies of
 * kotlinx.coroutines in different loaders gets the first copy traced; the others are ignored rather than mixed up.
 */
final class KotlinAccess {
    private static volatile KotlinAccess instance;
    private static volatile boolean failed;

    /** {@code null} until a job has been seen, or if the Kotlin classes do not look the way this agent expects. */
    static KotlinAccess get() {
        return instance;
    }

    static KotlinAccess getOrCreate(Object job) {
        KotlinAccess access = instance;
        if (access != null || failed) return access;
        synchronized (KotlinAccess.class) {
            if (instance == null && !failed) {
                try {
                    instance = new KotlinAccess(job.getClass().getClassLoader());
                } catch (Throwable e) {
                    failed = true;
                    Tracer.reportInternalError("cannot access kotlinx.coroutines internals, coroutines will not be traced", e);
                }
            }
            return instance;
        }
    }

    private final Class<?> combinedContext;
    private final Class<?> element;
    private final Class<?> interceptor;
    private final Class<?> coroutineName;
    private final Class<?> exceptionHandler;
    private final Class<?> threadContextElement;
    private final Class<?> jobClass;
    private final Class<?> completedExceptionally;
    private final Class<?> stackFrame;

    private final Object jobKey;
    private final Object exceptionHandlerKey;

    private final MethodHandle contextGet;           // (CoroutineContext, Key) -> Element
    private final MethodHandle combinedLeft;         // (CombinedContext) -> CoroutineContext
    private final MethodHandle combinedElement;      // (CombinedContext) -> Element
    private final MethodHandle nameOfCoroutineName;  // (CoroutineName) -> String
    private final MethodHandle causeOfCompleted;     // (CompletedExceptionally) -> Throwable
    private final MethodHandle callerFrame;          // (CoroutineStackFrame) -> CoroutineStackFrame
    private final MethodHandle stackTraceElement;    // (CoroutineStackFrame) -> StackTraceElement

    private KotlinAccess(ClassLoader loader) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> context = load(loader, "kotlin.coroutines.CoroutineContext");
        Class<?> key = load(loader, "kotlin.coroutines.CoroutineContext$Key");
        element = load(loader, "kotlin.coroutines.CoroutineContext$Element");
        combinedContext = load(loader, "kotlin.coroutines.CombinedContext");
        interceptor = load(loader, "kotlin.coroutines.ContinuationInterceptor");
        stackFrame = load(loader, "kotlin.coroutines.jvm.internal.CoroutineStackFrame");
        jobClass = load(loader, "kotlinx.coroutines.Job");
        coroutineName = load(loader, "kotlinx.coroutines.CoroutineName");
        exceptionHandler = load(loader, "kotlinx.coroutines.CoroutineExceptionHandler");
        threadContextElement = load(loader, "kotlinx.coroutines.ThreadContextElement");
        completedExceptionally = load(loader, "kotlinx.coroutines.CompletedExceptionally");

        jobKey = jobClass.getField("Key").get(null);
        exceptionHandlerKey = exceptionHandler.getField("Key").get(null);

        contextGet = lookup.findVirtual(context, "get", MethodType.methodType(element, key));
        combinedLeft = getter(lookup, combinedContext, "left");
        combinedElement = getter(lookup, combinedContext, "element");
        nameOfCoroutineName = lookup.findVirtual(coroutineName, "getName", MethodType.methodType(String.class));
        causeOfCompleted = getter(lookup, completedExceptionally, "cause");
        callerFrame = lookup.findVirtual(stackFrame, "getCallerFrame", MethodType.methodType(stackFrame));
        stackTraceElement = lookup.findVirtual(stackFrame, "getStackTraceElement", MethodType.methodType(StackTraceElement.class));
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return lookup.unreflectGetter(field);
    }

    /** The Job of a context, if it is one this agent instruments. */
    Tagged job(Object context) throws Throwable {
        if (context == null) return null;
        Object job = contextGet.invoke(context, jobKey);
        return job instanceof Tagged tagged ? tagged : null;
    }

    boolean hasExceptionHandler(Object context) throws Throwable {
        return context != null && contextGet.invoke(context, exceptionHandlerKey) != null;
    }

    /** The cause if {@code state} is an exceptional completion state, else {@code null}. */
    Throwable failureOf(Object state) throws Throwable {
        return completedExceptionally.isInstance(state) ? (Throwable) causeOfCompleted.invoke(state) : null;
    }

    /**
     * Elements of a context, leftmost first. A context is either a single element or a left-leaning list of
     * {@code CombinedContext(left, element)} cells; the empty context has no elements.
     */
    List<Object> elements(Object context) throws Throwable {
        ArrayList<Object> result = new ArrayList<>(6);
        Object rest = context;
        while (combinedContext.isInstance(rest)) {
            result.add(combinedElement.invoke(rest));
            rest = combinedLeft.invoke(rest);
        }
        if (element.isInstance(rest)) result.add(rest);
        java.util.Collections.reverse(result);
        return result;
    }

    ContextEntry describe(Object contextElement) throws Throwable {
        boolean threadElement = threadContextElement.isInstance(contextElement);
        if (jobClass.isInstance(contextElement)) {
            // No reference to the job: entries are inherited by descendants' nodes, which must not pin their ancestors' jobs.
            return new ContextEntry(null, Wire.CTX_JOB, "Job", "", threadElement);
        }
        if (coroutineName.isInstance(contextElement)) {
            String name = (String) nameOfCoroutineName.invoke(contextElement);
            return new ContextEntry(contextElement, Wire.CTX_NAME, "CoroutineName", name, threadElement);
        }
        if (interceptor.isInstance(contextElement)) {
            return new ContextEntry(contextElement, Wire.CTX_DISPATCHER, "Dispatcher", Describe.value(contextElement), threadElement);
        }
        if (exceptionHandler.isInstance(contextElement)) {
            // Usually a lambda; the synthetic class name of a lambda is noise, not a value.
            String value = Describe.value(contextElement);
            if (value.equals(Describe.simpleName(contextElement.getClass()))) value = "";
            return new ContextEntry(contextElement, Wire.CTX_EXCEPTION_HANDLER, "CoroutineExceptionHandler", value, threadElement);
        }
        String type = contextElement.getClass().getName();
        if (type.equals("kotlinx.coroutines.UndispatchedMarker") || type.equals("kotlinx.coroutines.CoroutineId")) {
            // The library's own bookkeeping, not something anybody put into the context: a marker withContext leaves,
            // and the numbering of coroutines in debug mode (which every JVM with -ea is in, e.g. a Gradle test worker).
            return null;
        }
        return new ContextEntry(contextElement, Wire.CTX_OTHER, contextElement.getClass().getName(), Describe.value(contextElement), threadElement);
    }

    /**
     * The coroutine's logical call stack at a suspension point, innermost first, from the debug metadata the Kotlin
     * compiler attaches to every suspend function. No stack walking involved.
     */
    StackFrameRef[] coroutineStack(Object continuation, int limit) throws Throwable {
        ArrayList<StackFrameRef> frames = new ArrayList<>();
        Object frame = continuation;
        // The chain is as long as the suspend call chain, but guard against a cyclic custom CoroutineStackFrame.
        for (int steps = 0; stackFrame.isInstance(frame) && frames.size() < limit && steps < 10_000; steps++) {
            StackTraceElement element = (StackTraceElement) stackTraceElement.invoke(frame);
            if (element != null) frames.add(StackFrameRef.of(element));
            frame = callerFrame.invoke(frame);
        }
        return frames.toArray(new StackFrameRef[0]);
    }
}
