package kotlinx.coroutree.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Function;
import java.util.stream.Stream;

/** Captures the current JVM stack and reads the source site and the source construct off it. */
final class StackCapture {
    private static final String RUNTIME_PACKAGE = "kotlinx.coroutree.runtime.";
    private static final StackWalker WALKER = StackWalker.getInstance();

    private StackCapture() {}

    /** The current stack without the agent's own frames, innermost first, at most {@code limit} frames. */
    static StackFrameRef[] capture(final int limit) {
        return WALKER.walk(new Function<Stream<StackWalker.StackFrame>, StackFrameRef[]>() {
            @Override
            public StackFrameRef[] apply(Stream<StackWalker.StackFrame> frames) {
                ArrayList<StackFrameRef> result = new ArrayList<>(Math.min(limit, 64));
                Iterator<StackWalker.StackFrame> iterator = frames.iterator();
                while (iterator.hasNext() && result.size() < limit) {
                    StackWalker.StackFrame frame = iterator.next();
                    String className = frame.getClassName();
                    if (result.isEmpty() && className.startsWith(RUNTIME_PACKAGE)) continue;
                    result.add(new StackFrameRef(className, frame.getMethodName(), frame.getFileName(), frame.getLineNumber()));
                }
                return result.toArray(new StackFrameRef[0]);
            }
        });
    }

    /**
     * A stack for finding the source site of a new node. The site is a fact about the node, not a detail of the
     * recorded stack, so how far down it may be looked for does not depend on the configured stack depth.
     */
    static StackFrameRef[] captureForSite() {
        return capture(Math.max(Tracer.config.stackDepth, SITE_SEARCH_DEPTH));
    }

    private static final int SITE_SEARCH_DEPTH = 64;

    static StackFrameRef[] limit(StackFrameRef[] stack, int limit) {
        return stack.length <= limit ? stack : java.util.Arrays.copyOf(stack, limit);
    }

    /**
     * Index of the source site in {@code stack}: the innermost frame that is not part of a concurrency runtime
     * (the JDK, the Kotlin standard library, kotlinx.coroutines). -1 if the whole stack is runtime.
     */
    static int siteIndex(StackFrameRef[] stack) {
        for (int i = 0; i < stack.length; i++) {
            if (!isConcurrencyRuntime(stack[i].className)) return i;
        }
        return -1;
    }

    /**
     * Name of the construct that created a node: the API function the site frame called, i.e. the frame just below
     * the site. {@code launch}, {@code withContext}, {@code Job()}, {@code Thread.start}.
     */
    static String construct(StackFrameRef[] stack, int siteIndex, String fallback) {
        int api = siteIndex < 0 ? stack.length - 1 : siteIndex - 1;
        if (api < 0) return fallback;
        StackFrameRef frame = stack[api];
        String method = frame.methodName;
        if (method.endsWith("$default")) method = method.substring(0, method.length() - "$default".length());
        if (method.equals("<init>")) return fallback;
        if (frame.className.startsWith("kotlin")) {
            // The name in the source, where @JvmName gave the function another one in bytecode.
            if (method.equals("runBlockingK")) method = "runBlocking";
            // Kotlin names factory functions like the type they return: Job(), SupervisorJob(), CoroutineScope().
            return Character.isUpperCase(method.charAt(0)) ? method + "()" : method;
        }
        // Thread.ofVirtual().start(…) and friends, named the way they are written rather than after the builder's class.
        if (frame.className.startsWith("java.lang.ThreadBuilders$Virtual")) return "Thread.ofVirtual()." + method;
        if (frame.className.startsWith("java.lang.ThreadBuilders$Platform")) return "Thread.ofPlatform()." + method;
        if (frame.className.equals("java.lang.VirtualThread")) return "Thread." + method; // an override; the call was Thread.start()
        String owner = frame.className.substring(frame.className.lastIndexOf('.') + 1);
        return owner + "." + method;
    }

    /**
     * Whether the site started the thread on purpose: everything between it and {@code Thread.start} is a thread-starting
     * API. Anything else in between — a static initializer, an executor, a timer — and the thread is somebody's
     * side effect: the first virtual thread of a JVM, for one, also starts the JDK's "VirtualThread-unblocker".
     */
    static boolean startedDirectlyBySite(StackFrameRef[] stack, int siteIndex) {
        if (siteIndex <= 0) return false;
        for (int i = 0; i < siteIndex; i++) {
            if (!isThreadStartFrame(stack[i])) return false;
        }
        return true;
    }

    private static boolean isThreadStartFrame(StackFrameRef frame) {
        String owner = frame.className;
        boolean api = owner.equals("java.lang.Thread") || owner.equals("java.lang.VirtualThread")
            || owner.startsWith("java.lang.ThreadBuilders") || owner.equals("kotlin.concurrent.ThreadsKt");
        if (!api) return false;
        String method = frame.methodName;
        return method.equals("start") || method.equals("startVirtualThread") || method.equals("thread") || method.equals("thread$default");
    }

    static boolean isConcurrencyRuntime(String className) {
        return className.startsWith("java.")
            || className.startsWith("kotlin.")
            || className.startsWith("kotlinx.coroutines.")
            || className.startsWith("jdk.")
            || className.startsWith("sun.")
            || className.startsWith("javax.")
            || className.startsWith(RUNTIME_PACKAGE);
    }
}
