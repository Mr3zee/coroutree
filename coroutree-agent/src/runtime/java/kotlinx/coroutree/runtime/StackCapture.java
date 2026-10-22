package kotlinx.coroutree.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Function;
import java.util.stream.Stream;

final class StackCapture {
    private static final String RUNTIME_PACKAGE = "kotlinx.coroutree.runtime.";
    private static final StackWalker WALKER = StackWalker.getInstance();

    private StackCapture() {}

    /**
     * The current stack without the agent's own frames, innermost first, at most {@code limit} frames. Inlined code
     * appears where it was written, in a frame of its own, see {@link SourceMaps}.
     */
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
                    SourceMaps.add(result, className, frame.getMethodName(), frame.getFileName(), frame.getLineNumber());
                }
                return limit(result.toArray(new StackFrameRef[0]), limit);
            }
        });
    }

    /**
     * Source-site lookup must not depend on the configured event stack depth.
     */
    static StackFrameRef[] captureForSite() {
        return capture(Math.max(Tracer.config.stackDepth, SITE_SEARCH_DEPTH));
    }

    private static final int SITE_SEARCH_DEPTH = 64;

    static final String SUSPEND_MAIN = "suspend fun main";

    static StackFrameRef[] limit(StackFrameRef[] stack, int limit) {
        return stack.length <= limit ? stack : java.util.Arrays.copyOf(stack, limit);
    }

    /**
     * Index of the innermost frame outside the JDK, Kotlin standard library, and kotlinx.coroutines.
     * Returns -1 if all frames belong to those runtimes.
     */
    static int siteIndex(StackFrameRef[] stack) {
        for (int i = 0; i < stack.length; i++) {
            if (!isConcurrencyRuntime(stack[i].className)) return i;
        }
        return -1;
    }

    /**
     * The API frame just below the source site identifies the construct, such as launch or withContext.
     */
    static String construct(StackFrameRef[] stack, int siteIndex, String fallback) {
        int api = siteIndex < 0 ? stack.length - 1 : siteIndex - 1;
        // Skip unnamed inline frames between the source site and the API.
        while (api >= 0 && stack[api].methodName.isEmpty()) api--;
        if (api < 0) return fallback;
        StackFrameRef frame = stack[api];
        String method = frame.methodName;
        if (method.endsWith("$default")) method = method.substring(0, method.length() - "$default".length());
        if (method.equals("<init>")) return fallback;
        if (frame.className.startsWith("kotlin")) {
            // What the compiler turns `suspend fun main` into: a plain main that hands the real one to runSuspend.
            if (method.equals("runSuspend") && frame.className.equals("kotlin.coroutines.jvm.internal.RunSuspendKt")) return SUSPEND_MAIN;
            // The name in the source, where @JvmName gave the function another one in bytecode.
            if (method.equals("runBlockingK")) method = "runBlocking";
            // Kotlin names factory functions like the type they return: Job(), SupervisorJob(), CoroutineScope().
            return Character.isUpperCase(method.charAt(0)) ? method + "()" : method;
        }
        // Thread.ofVirtual().start(…) and friends, named the way they are written rather than after the builder's class.
        if (frame.className.startsWith("java.lang.ThreadBuilders$Virtual")) return "Thread.ofVirtual()." + method;
        if (frame.className.startsWith("java.lang.ThreadBuilders$Platform")) return "Thread.ofPlatform()." + method;
        if (frame.className.equals("java.lang.VirtualThread")) return "Thread." + method; // source calls Thread.start(), which VirtualThread overrides
        String owner = frame.className.substring(frame.className.lastIndexOf('.') + 1);
        return owner + "." + method;
    }

    /**
     * A project-created thread has only thread-starting APIs between its source site and Thread.start.
     * Other intervening frames identify runtime side effects, such as the JDK's VirtualThread-unblocker.
     */
    static boolean startedDirectlyBySite(StackFrameRef[] stack, int siteIndex) {
        if (siteIndex <= 0) return false;
        for (int i = 0; i < siteIndex; i++) {
            if (!stack[i].inlined && !isThreadStartFrame(stack[i])) return false; // inlined into a thread-starting API: part of it
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
