package kotlinx.coroutree.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The bootstrap runtime can't link Kotlin types, so method handles use application class loaders.
 * One instance resolves kotlin-stdlib from the first job or continuation and kotlinx.coroutines from the first job.
 * Programs without jobs need only kotlin-stdlib. Only the first resolved copy of kotlinx.coroutines is traced.
 */
final class KotlinAccess {
    private static volatile KotlinAccess instance;
    private static volatile boolean failed;

    /** Null before the first job or continuation, or after Kotlin type resolution fails. */
    static KotlinAccess get() {
        return instance;
    }

    static KotlinAccess forJob(Object job) {
        KotlinAccess access = forContinuation(job);
        if (access == null) return null;
        if (access.coroutines == null && !access.coroutinesFailed) access.resolveCoroutines(job.getClass().getClassLoader());
        return access.coroutines == null ? null : access;
    }

    static KotlinAccess forContinuation(Object continuation) {
        KotlinAccess access = instance;
        if (access != null || failed) return access;
        synchronized (KotlinAccess.class) {
            if (instance == null && !failed) {
                try {
                    instance = new KotlinAccess(continuation.getClass().getClassLoader());
                } catch (Throwable e) {
                    failed = true;
                    Tracer.reportInternalError("cannot access the internals of Kotlin coroutines, coroutines will not be traced", e);
                }
            }
            return instance;
        }
    }

    private final Class<?> combinedContext;
    private final Class<?> element;
    private final Class<?> interceptor;
    private final Class<?> stackFrame;
    private final Class<?> baseContinuation;
    private final Class<?> restrictedContinuation;
    private final Class<?> resultFailure;

    private final MethodHandle contextGet;           // (CoroutineContext, Key) -> Element
    private final MethodHandle combinedLeft;         // (CombinedContext) -> CoroutineContext
    private final MethodHandle combinedElement;      // (CombinedContext) -> Element
    private final MethodHandle callerFrame;          // (CoroutineStackFrame) -> CoroutineStackFrame
    private final MethodHandle stackTraceElement;    // (CoroutineStackFrame) -> StackTraceElement
    private final MethodHandle continuationContext;  // (Continuation) -> CoroutineContext
    private final MethodHandle completion;           // (BaseContinuationImpl) -> Continuation
    private final MethodHandle exceptionOfFailure;   // (Result.Failure) -> Throwable

    private volatile Coroutines coroutines;
    private volatile boolean coroutinesFailed;

    private KotlinAccess(ClassLoader loader) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> context = load(loader, "kotlin.coroutines.CoroutineContext");
        Class<?> key = load(loader, "kotlin.coroutines.CoroutineContext$Key");
        Class<?> continuation = load(loader, "kotlin.coroutines.Continuation");
        element = load(loader, "kotlin.coroutines.CoroutineContext$Element");
        combinedContext = load(loader, "kotlin.coroutines.CombinedContext");
        interceptor = load(loader, "kotlin.coroutines.ContinuationInterceptor");
        stackFrame = load(loader, "kotlin.coroutines.jvm.internal.CoroutineStackFrame");
        baseContinuation = load(loader, "kotlin.coroutines.jvm.internal.BaseContinuationImpl");
        restrictedContinuation = load(loader, "kotlin.coroutines.jvm.internal.RestrictedContinuationImpl");
        resultFailure = load(loader, "kotlin.Result$Failure");

        contextGet = lookup.findVirtual(context, "get", MethodType.methodType(element, key));
        combinedLeft = getter(lookup, combinedContext, "left");
        combinedElement = getter(lookup, combinedContext, "element");
        callerFrame = lookup.findVirtual(stackFrame, "getCallerFrame", MethodType.methodType(stackFrame));
        stackTraceElement = lookup.findVirtual(stackFrame, "getStackTraceElement", MethodType.methodType(StackTraceElement.class));
        continuationContext = lookup.findVirtual(continuation, "getContext", MethodType.methodType(context));
        completion = lookup.findVirtual(baseContinuation, "getCompletion", MethodType.methodType(continuation));
        exceptionOfFailure = getter(lookup, resultFailure, "exception");
    }

    private synchronized void resolveCoroutines(ClassLoader loader) {
        if (coroutines != null || coroutinesFailed) return;
        try {
            coroutines = new Coroutines(loader, element);
        } catch (Throwable e) {
            coroutinesFailed = true;
            Tracer.reportInternalError("cannot access kotlinx.coroutines internals, jobs will not be traced", e);
        }
    }

    private static final class Coroutines {
        final Class<?> jobClass;
        final Class<?> coroutineName;
        final Class<?> exceptionHandler;
        final Class<?> threadContextElement;
        final Class<?> completedExceptionally;

        final Object jobKey;
        final Object exceptionHandlerKey;

        final MethodHandle nameOfCoroutineName;  // (CoroutineName) -> String
        final MethodHandle causeOfCompleted;     // (CompletedExceptionally) -> Throwable

        Coroutines(ClassLoader loader, Class<?> element) throws ReflectiveOperationException {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            jobClass = load(loader, "kotlinx.coroutines.Job");
            coroutineName = load(loader, "kotlinx.coroutines.CoroutineName");
            exceptionHandler = load(loader, "kotlinx.coroutines.CoroutineExceptionHandler");
            threadContextElement = load(loader, "kotlinx.coroutines.ThreadContextElement");
            completedExceptionally = load(loader, "kotlinx.coroutines.CompletedExceptionally");
            // Method handles require kotlinx.coroutines to use the same kotlin-stdlib types.
            if (!element.isAssignableFrom(jobClass)) throw new ClassNotFoundException("kotlinx.coroutines.Job of another kotlin-stdlib");

            jobKey = jobClass.getField("Key").get(null);
            exceptionHandlerKey = exceptionHandler.getField("Key").get(null);
            nameOfCoroutineName = lookup.findVirtual(coroutineName, "getName", MethodType.methodType(String.class));
            causeOfCompleted = getter(lookup, completedExceptionally, "cause");
        }
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
        Coroutines kx = coroutines;
        if (context == null || kx == null) return null; // no job has been created yet, so this context has none
        Object job = contextGet.invoke(context, kx.jobKey);
        return job instanceof Tagged tagged ? tagged : null;
    }

    /** Whether the context has a Job of any kind, this agent's or not. */
    boolean hasJob(Object context) throws Throwable {
        Coroutines kx = coroutines;
        return context != null && kx != null && contextGet.invoke(context, kx.jobKey) != null;
    }

    boolean hasExceptionHandler(Object context) throws Throwable {
        Coroutines kx = coroutines;
        return context != null && kx != null && contextGet.invoke(context, kx.exceptionHandlerKey) != null;
    }

    Throwable failureOf(Object state) throws Throwable {
        Coroutines kx = coroutines;
        return kx != null && kx.completedExceptionally.isInstance(state) ? (Throwable) kx.causeOfCompleted.invoke(state) : null;
    }

    Throwable failureOfResult(Object result) throws Throwable {
        return resultFailure.isInstance(result) ? (Throwable) exceptionOfFailure.invoke(result) : null;
    }

    boolean isCompiledFrame(Object continuation) {
        return baseContinuation.isInstance(continuation);
    }

    /**
     * Restricted frames usually identify generators. Intrinsics frames are an exception: they also start ordinary suspend functions,
     * including suspend main, when the context is empty.
     */
    boolean isGeneratorFrame(Object continuation) {
        return restrictedContinuation.isInstance(continuation) && !continuation.getClass().getName().startsWith("kotlin.coroutines.intrinsics.");
    }

    Object completionOf(Object compiledFrame) throws Throwable {
        return completion.invoke(compiledFrame);
    }

    /** Compiled frames use a field read. Custom continuations can run application code here. */
    Object contextOf(Object continuation) throws Throwable {
        return continuationContext.invoke(continuation);
    }

    /** CoroutineStackFrame.callerFrame, or null if the object isn't a stack frame. */
    Object callerOf(Object continuation) throws Throwable {
        return stackFrame.isInstance(continuation) ? callerFrame.invoke(continuation) : null;
    }

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
        Coroutines kx = coroutines;
        boolean threadElement = kx != null && kx.threadContextElement.isInstance(contextElement);
        if (kx != null && kx.jobClass.isInstance(contextElement)) {
            // Descendants inherit these entries, so they must not reference ancestor jobs.
            return new ContextEntry(null, Wire.CTX_JOB, "Job", "", threadElement);
        }
        if (kx != null && kx.coroutineName.isInstance(contextElement)) {
            String name = (String) kx.nameOfCoroutineName.invoke(contextElement);
            return new ContextEntry(contextElement, Wire.CTX_NAME, "CoroutineName", name, threadElement);
        }
        if (interceptor.isInstance(contextElement)) {
            return new ContextEntry(contextElement, Wire.CTX_DISPATCHER, "Dispatcher", Describe.value(contextElement), threadElement);
        }
        if (kx != null && kx.exceptionHandler.isInstance(contextElement)) {
            // Synthetic lambda class names don't describe handler values.
            String value = Describe.value(contextElement);
            if (value.equals(Describe.simpleName(contextElement.getClass()))) value = "";
            return new ContextEntry(contextElement, Wire.CTX_EXCEPTION_HANDLER, "CoroutineExceptionHandler", value, threadElement);
        }
        String type = contextElement.getClass().getName();
        if (type.equals("kotlinx.coroutines.UndispatchedMarker") || type.equals("kotlinx.coroutines.CoroutineId")) {
            // Exclude library bookkeeping: the withContext marker and debug-mode coroutine ids.
            return null;
        }
        return new ContextEntry(contextElement, Wire.CTX_OTHER, contextElement.getClass().getName(), Describe.value(contextElement), threadElement);
    }

    /**
     * Reads the logical stack from compiler debug metadata, innermost first, without JVM stack walking.
     */
    StackFrameRef[] coroutineStack(Object continuation, int limit) throws Throwable {
        ArrayList<StackFrameRef> frames = new ArrayList<>();
        Object frame = continuation;
        // The chain is as long as the suspend call chain, but guard against a cyclic custom CoroutineStackFrame.
        for (int steps = 0; stackFrame.isInstance(frame) && frames.size() < limit && steps < 10_000; steps++) {
            StackTraceElement element = (StackTraceElement) stackTraceElement.invoke(frame);
            if (element != null) StackFrameRef.add(frames, element);
            // Reading the caller can be a step of a stateful frame (Ktor's pipeline bridge), so not one more than the cut needs.
            if (frames.size() >= limit) break;
            frame = callerFrame.invoke(frame);
        }
        return StackCapture.limit(frames.toArray(new StackFrameRef[0]), limit);
    }
}
