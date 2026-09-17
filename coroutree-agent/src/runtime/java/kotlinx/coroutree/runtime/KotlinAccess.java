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
 * One instance per JVM, in two parts. The standard library's part is resolved from the loader of the first job or
 * continuation seen; the kotlinx.coroutines part from the loader of the first job, and a program that never makes
 * one (bare {@code suspend fun main}, {@code startCoroutine}) does without. An application that loads several copies
 * of kotlinx.coroutines in different loaders gets the first copy traced; the others are ignored rather than mixed up.
 */
final class KotlinAccess {
    private static volatile KotlinAccess instance;
    private static volatile boolean failed;

    /** {@code null} until a job or a continuation has been seen, or if the Kotlin classes do not look the way this agent expects. */
    static KotlinAccess get() {
        return instance;
    }

    /** For hooks that are handed a job: both parts, or {@code null}. */
    static KotlinAccess forJob(Object job) {
        KotlinAccess access = forContinuation(job);
        if (access == null) return null;
        if (access.coroutines == null && !access.coroutinesFailed) access.resolveCoroutines(job.getClass().getClassLoader());
        return access.coroutines == null ? null : access;
    }

    /** For hooks that are handed something of the standard library's coroutine machinery; kotlinx.coroutines may not even be there. */
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

    /** What is needed from kotlinx.coroutines. */
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
            // A second copy of kotlinx.coroutines over a second copy of the standard library would not fit the handles above.
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

    /** The cause if {@code state} is an exceptional completion state of a job, else {@code null}. */
    Throwable failureOf(Object state) throws Throwable {
        Coroutines kx = coroutines;
        return kx != null && kx.completedExceptionally.isInstance(state) ? (Throwable) kx.causeOfCompleted.invoke(state) : null;
    }

    /** The exception if {@code result} is what a failed {@code kotlin.Result} looks like to Java, else {@code null}. */
    Throwable failureOfResult(Object result) throws Throwable {
        return resultFailure.isInstance(result) ? (Throwable) exceptionOfFailure.invoke(result) : null;
    }

    /** A frame of compiled suspending code, as opposed to a continuation somebody wrote by hand. */
    boolean isCompiledFrame(Object continuation) {
        return baseContinuation.isInstance(continuation);
    }

    /**
     * A frame of a {@code @RestrictsSuspension} coroutine: {@code sequence}, {@code iterator}, {@code DeepRecursiveFunction}.
     * Those are generators. They suspend to hand a value to the very code that resumes them, synchronously.
     * (Not everything restricted is one: the standard library also starts a suspend function that is not a lambda,
     * {@code suspend fun main} for one, inside a restricted frame of its own when the context is empty.)
     */
    boolean isGeneratorFrame(Object continuation) {
        return restrictedContinuation.isInstance(continuation) && !continuation.getClass().getName().startsWith("kotlin.coroutines.intrinsics.");
    }

    /** What a compiled frame resumes when it is done: its caller's frame, or what the coroutine was started with. */
    Object completionOf(Object compiledFrame) throws Throwable {
        return completion.invoke(compiledFrame);
    }

    /** For a compiled frame this is a field read. For anything else it is application code. */
    Object contextOf(Object continuation) throws Throwable {
        return continuationContext.invoke(continuation);
    }

    /** {@code CoroutineStackFrame.callerFrame}, {@code null} for anything that is not a stack frame. */
    Object callerOf(Object continuation) throws Throwable {
        return stackFrame.isInstance(continuation) ? callerFrame.invoke(continuation) : null;
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
        Coroutines kx = coroutines;
        boolean threadElement = kx != null && kx.threadContextElement.isInstance(contextElement);
        if (kx != null && kx.jobClass.isInstance(contextElement)) {
            // No reference to the job: entries are inherited by descendants' nodes, which must not pin their ancestors' jobs.
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
            if (element != null) StackFrameRef.add(frames, element);
            frame = callerFrame.invoke(frame);
        }
        return StackCapture.limit(frames.toArray(new StackFrameRef[0]), limit);
    }
}
