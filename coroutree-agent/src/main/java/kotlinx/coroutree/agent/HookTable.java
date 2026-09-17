package kotlinx.coroutree.agent;

import kotlinx.coroutree.agent.MethodPatch.HookCall;
import kotlinx.coroutree.runtime.Wire;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

import static kotlinx.coroutree.agent.Args.arg;
import static kotlinx.coroutree.agent.Args.call;
import static kotlinx.coroutree.agent.Args.constant;
import static kotlinx.coroutree.agent.Args.field;
import static kotlinx.coroutree.agent.Args.returnValue;
import static kotlinx.coroutree.agent.Args.self;
import static kotlinx.coroutree.agent.MethodPatch.around;
import static kotlinx.coroutree.agent.MethodPatch.enter;
import static kotlinx.coroutree.agent.MethodPatch.enterAndExit;
import static kotlinx.coroutree.agent.MethodPatch.exit;

/**
 * Which method of which class calls which hook. This is the whole coupling of the agent to the internals of the JDK
 * and of kotlinx.coroutines, in one place.
 *
 * kotlinx.coroutines entries name private and internal members. They were checked against the versions listed in
 * {@link #TESTED_COROUTINES}; with any version, a required method that is not found is reported as an error in the
 * trace and on stderr rather than silently producing a wrong picture. JDK entries are mostly optional: the set of
 * blocking entry points differs between JDK releases and missing one only loses a THREAD_BLOCKED reason.
 */
final class HookTable {
    /** Inclusive range of kotlinx.coroutines major.minor versions the integration tests run against. */
    static final int[] TESTED_COROUTINES = {1, 9, 1, 11};

    static final String JOB_SUPPORT = "kotlinx/coroutines/JobSupport";

    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String THROWABLE = "Ljava/lang/Throwable;";
    private static final String THREAD = "Ljava/lang/Thread;";
    private static final String CONTEXT = "Lkotlin/coroutines/CoroutineContext;";

    private final Map<String, ClassPatch> patches = new HashMap<>();

    ClassPatch get(String internalName) {
        return patches.get(internalName);
    }

    Iterable<String> classNames() {
        return patches.keySet();
    }

    private ClassPatch on(String className) {
        return patches.computeIfAbsent(className, ClassPatch::new);
    }

    HookTable() {
        coroutines();
        threads();
        blocking();
    }

    private void coroutines() {
        // Suspend and resume: the probes the Kotlin compiler's coroutine machinery calls, empty in the standard library.
        HookCall resumed = new HookCall("coroutineResumed", "(" + OBJECT + OBJECT + ")V", arg(0), contextOf(arg(0)));
        HookCall suspended = new HookCall("coroutineSuspended", "(" + OBJECT + OBJECT + ")V", arg(0), contextOf(arg(0)));
        on("kotlin/coroutines/jvm/internal/DebugProbesKt")
            .method(enter("probeCoroutineResumed", "(Lkotlin/coroutines/Continuation;)V", resumed))
            .method(enter("probeCoroutineSuspended", "(Lkotlin/coroutines/Continuation;)V", suspended));

        // Structure: every coroutine, scope and withContext is an AbstractCoroutine; Job() and friends are JobImpl.
        on("kotlinx/coroutines/AbstractCoroutine")
            .method(exit("<init>", "(" + CONTEXT + "ZZ)V", new HookCall("coroutineCreated", "(" + OBJECT + OBJECT + OBJECT + ")V",
                self(), arg(0), call(self(), Opcodes.INVOKEVIRTUAL, "kotlinx/coroutines/AbstractCoroutine", "getContext", "()" + CONTEXT))));
        on("kotlinx/coroutines/JobImpl")
            .method(exit("<init>", "(Lkotlinx/coroutines/Job;)V", new HookCall("jobCreated", "(" + OBJECT + OBJECT + ")V", self(), arg(0))));

        // Life of a job: cancellation, exceptions, completion.
        on(JOB_SUPPORT)
            .tagged()
            .method(enter("cancel", "(Ljava/util/concurrent/CancellationException;)V",
                new HookCall("cancelRequested", "(" + OBJECT + THROWABLE + ")V", self(), arg(0))))
            .method(enter("parentCancelled", "(Lkotlinx/coroutines/ParentJob;)V",
                new HookCall("parentCancelled", "(" + OBJECT + OBJECT + ")V", self(), arg(0))))
            .method(enter("notifyCancelling", "(Lkotlinx/coroutines/NodeList;" + THROWABLE + ")V",
                new HookCall("cancelling", "(" + OBJECT + THROWABLE + ")V", self(), arg(1))))
            .method(enter("makeCompletingOnce$kotlinx_coroutines_core", "(" + OBJECT + ")" + OBJECT,
                new HookCall("completing", "(" + OBJECT + OBJECT + ")V", self(), arg(0))))
            .method(enter("completeStateFinalization", "(Lkotlinx/coroutines/Incomplete;" + OBJECT + ")V",
                new HookCall("completed", "(" + OBJECT + OBJECT + ")V", self(), arg(1))));
        // A channel coroutine (produce, actor) overrides cancel without calling up.
        on("kotlinx/coroutines/channels/ChannelCoroutine")
            .method(enter("cancel", "(Ljava/util/concurrent/CancellationException;)V",
                new HookCall("cancelRequested", "(" + OBJECT + THROWABLE + ")V", self(), arg(0))).optional());

        MethodPatch.Arg child = field("kotlinx/coroutines/ChildHandleNode", "childJob", "Lkotlinx/coroutines/ChildJob;");
        MethodPatch.Arg parent = field("kotlinx/coroutines/JobNode", "job", "Lkotlinx/coroutines/JobSupport;");
        on("kotlinx/coroutines/ChildHandleNode")
            .method(enterAndExit("childCancelled", "(" + THROWABLE + ")Z",
                new HookCall("childCancelled", "(" + OBJECT + OBJECT + THROWABLE + ")V", child, parent, arg(0)),
                new HookCall("childCancelledResult", "(Z" + OBJECT + OBJECT + THROWABLE + ")V", returnValue(), child, parent, arg(0))));

        on("kotlinx/coroutines/CoroutineExceptionHandlerKt")
            .method(enter("handleCoroutineException", "(" + CONTEXT + THROWABLE + ")V",
                new HookCall("exceptionReachedHandler", "(" + OBJECT + THROWABLE + ")V", arg(0), arg(1))));

        on("kotlinx/coroutines/BlockingCoroutine")
            .method(blocking("joinBlocking", "()" + OBJECT, Wire.BLOCK_RUN_BLOCKING));
    }

    private static MethodPatch.Arg contextOf(MethodPatch.Arg continuation) {
        return call(continuation, Opcodes.INVOKEINTERFACE, "kotlin/coroutines/Continuation", "getContext", "()" + CONTEXT);
    }

    private void threads() {
        HookCall start = new HookCall("threadStart", "(" + THREAD + ")V", self());
        HookCall interrupt = new HookCall("threadInterrupt", "(" + THREAD + ")V", self());
        HookCall exit = new HookCall("threadExit", "(" + THREAD + ")V", self());
        on("java/lang/Thread")
            .method(enter("start", "()V", start))
            .method(enter("start", "(Ljdk/internal/vm/ThreadContainer;)V", start).optional())
            .method(enter("exit", "()V", exit))
            .method(enter("interrupt", "()V", interrupt))
            .method(enter("dispatchUncaughtException", "(" + THROWABLE + ")V",
                new HookCall("threadUncaught", "(" + THREAD + THROWABLE + ")V", self(), arg(0))).optional());
        // A virtual thread overrides start and interrupt without calling up, and does not end in Thread.exit.
        on("java/lang/VirtualThread")
            .method(enter("start", "(Ljdk/internal/vm/ThreadContainer;)V", start).optional())
            .method(enter("interrupt", "()V", interrupt).optional())
            .method(around("run", "(Ljava/lang/Runnable;)V", null, exit).optional());
    }

    private void blocking() {
        on("java/lang/Thread")
            .method(blocking("sleep", "(J)V", Wire.BLOCK_SLEEP))
            .method(blocking("sleep", "(JI)V", Wire.BLOCK_SLEEP).optional())
            .method(blocking("sleep", "(Ljava/time/Duration;)V", Wire.BLOCK_SLEEP).optional())
            .method(blocking("join", "(J)V", Wire.BLOCK_JOIN))
            .method(blocking("join", "(JI)V", Wire.BLOCK_JOIN).optional())
            .method(blocking("join", "()V", Wire.BLOCK_JOIN).optional())
            .method(blocking("join", "(Ljava/time/Duration;)Z", Wire.BLOCK_JOIN).optional());
        on("java/lang/Object")
            .method(blocking("wait", "(J)V", Wire.BLOCK_WAIT).optional());
        on("java/util/concurrent/locks/LockSupport")
            .method(blocking("park", "()V", Wire.BLOCK_PARK))
            .method(blocking("park", "(" + OBJECT + ")V", Wire.BLOCK_PARK))
            .method(blocking("parkNanos", "(J)V", Wire.BLOCK_PARK))
            .method(blocking("parkNanos", "(" + OBJECT + "J)V", Wire.BLOCK_PARK))
            .method(blocking("parkUntil", "(J)V", Wire.BLOCK_PARK))
            .method(blocking("parkUntil", "(" + OBJECT + "J)V", Wire.BLOCK_PARK));

        // Blocking I/O, M1 selection: sockets, child processes and standard input. File I/O is deliberately left out —
        // it blocks in the OS sense but reporting every read of every file would bury the blocking that matters.
        on("sun/nio/ch/NioSocketImpl")
            .method(blocking("read", "([BII)I", Wire.BLOCK_IO).optional())
            .method(blocking("accept", "(Ljava/net/SocketImpl;)V", Wire.BLOCK_IO).optional())
            .method(blocking("connect", "(Ljava/net/SocketAddress;I)V", Wire.BLOCK_IO).optional());
        on("java/lang/ProcessImpl")
            .method(blocking("waitFor", "()I", Wire.BLOCK_IO).optional())
            .method(blocking("waitFor", "(JLjava/util/concurrent/TimeUnit;)Z", Wire.BLOCK_IO).optional());
        HookCall enterIfStdin = new HookCall("blockEnterIfStdin", "(" + OBJECT + ")V", self());
        HookCall blockExit = new HookCall("blockExit", "()V");
        on("java/io/FileInputStream")
            .method(around("read", "()I", enterIfStdin, blockExit).optional())
            .method(around("read", "([B)I", enterIfStdin, blockExit).optional())
            .method(around("read", "([BII)I", enterIfStdin, blockExit).optional());
    }

    private static MethodPatch blocking(String name, String descriptor, int reason) {
        return around(name, descriptor,
            new HookCall("blockEnter", "(I)V", constant(reason)),
            new HookCall("blockExit", "()V"));
    }
}
