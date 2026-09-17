package kotlinx.coroutree.runtime;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Entry points called by instrumented code. The agent's transformers (kotlinx.coroutree.agent.HookTable) say which
 * method of which class calls which hook; this class says what the call means.
 *
 * Every hook has the same shape: bail out if tracing is off or if this thread is already inside a hook, do the
 * work, and swallow whatever goes wrong. Arguments are typed {@code Object} because this class is loaded by the
 * bootstrap loader and cannot name Kotlin types.
 */
public final class Hooks {
    private Hooks() {}

    private static final WeakIdentityMap<Thread, ThreadNode> THREADS = new WeakIdentityMap<>();
    private static final WeakIdentityMap<Object, Long> POOLS = new WeakIdentityMap<>();
    private static final Object DISCOVERY_LOCK = new Object();

    // ------------------------------------------------------------------ coroutines: structure

    /** End of the {@code AbstractCoroutine} constructor: a coroutine, scope or context change came to be. */
    public static void coroutineCreated(Object coroutine, Object parentContext, Object context) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.getOrCreate(coroutine);
            if (access == null || !(coroutine instanceof Tagged tagged)) return;
            Tagged parent = access.job(parentContext);
            createJobNode(ts, access, tagged, parent, context);
        } catch (Throwable e) {
            Tracer.reportInternalError("coroutineCreated", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** End of the {@code JobImpl} constructor: {@code Job()}, {@code SupervisorJob()}, the job of a {@code CoroutineScope()}. */
    public static void jobCreated(Object job, Object parent) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.getOrCreate(job);
            if (access == null || !(job instanceof Tagged tagged)) return;
            createJobNode(ts, access, tagged, parent instanceof Tagged p ? p : null, null);
        } catch (Throwable e) {
            Tracer.reportInternalError("jobCreated", e);
        } finally {
            ts.inHook = false;
        }
    }

    private static void createJobNode(ThreadState ts, KotlinAccess access, Tagged job, Tagged parent, Object context) throws Throwable {
        ClassInfo info = ClassInfo.of(job.getClass());
        // The node may be there already: a job attaches to its parent inside its constructor, and a parent that is
        // cancelled or done cancels it right then, before this hook at the end of the constructor. Whoever needed
        // the node first made it, without a definition; the definition is what this hook adds. The tag is published
        // before anything slow happens, for the same reason: the job is already reachable through its parent.
        JobNode node = obtainNode(job, info);
        if (node.defined) return;
        JobNode parentNode = parent == null ? null : nodeOf(parent, ts);
        ThreadNode thread = threadNode(ts);
        JobNode unit = ts.currentUnit();
        long creator = unit != null ? unit.id : thread.id;

        StackFrameRef[] stack = StackCapture.captureForSite();
        int site = StackCapture.siteIndex(stack);
        String construct = StackCapture.construct(stack, site, info.construct);

        TraceEvent.NodeDef def = new TraceEvent.NodeDef();
        def.id = node.id;
        // withContext that does not change the dispatcher runs in an ordinary scope coroutine; it is still a context change.
        def.kind = construct.equals("withContext") ? Wire.KIND_CONTEXT_CHANGE : info.kind;
        def.construct = construct;
        def.implClass = job.getClass().getName();
        // Without a parent job we know of, runBlocking and scopes still have a place in the structure: under whoever
        // called them and waits for them. (withContext(NonCancellable) is such a scope: its parent is not a real job.)
        def.parentId = parentNode != null ? parentNode.id : info.blocking || info.scoped ? creator : 0;
        def.creatorId = creator;
        setSite(def, stack, site);

        // What the node's context is compared with: its parent's, or for a scope without a parent node its caller's,
        // which is where a scope's context comes from.
        ContextEntry[] parentContext = parentNode != null ? parentNode.context : info.scoped && unit != null ? unit.context : null;
        if (context != null) {
            ArrayList<ContextEntry> entries = new ArrayList<>();
            for (Object element : access.elements(context)) {
                ContextEntry inherited = find(parentContext, element);
                ContextEntry entry = inherited != null ? inherited : access.describe(element);
                if (entry == null) continue;
                entries.add(entry);
                // Only a name the node was given itself. An inherited one is its parent's name, and a tree where every
                // descendant of "server" is titled "server" says less than one where they are anonymous.
                if (inherited == null && entry.kind == Wire.CTX_NAME) def.name = entry.value;
            }
            node.context = entries.toArray(new ContextEntry[0]);
            def.context = node.context;
        } else {
            node.context = parentContext; // a plain Job has no context of its own; children diff against the grandparent
        }

        if (node.startsUndispatched) {
            synchronized (node) {
                node.run = JobNode.RUNNING;
                node.runThread = ts.thread.threadId();
            }
        }
        if (info.scoped || info.blocking) {
            // Scopes (withContext to another dispatcher included) and runBlocking do not report a failure to their
            // parent job; they throw it at whoever called them.
            node.rethrowsToId = creator;
            node.rethrowsTo = unit;
        }
        node.defined = true;

        TraceEvent launched = new TraceEvent(node.id, Wire.LAUNCHED, thread.id);
        launched.node = def;
        launched.stack = StackCapture.limit(stack, Tracer.config.stackDepth);
        Tracer.emit(launched);
        if (context != null) emitContextDiff(node, thread, parentContext);
        if (node.startsUndispatched && !node.finished) ts.pushUnit(node);
    }

    private static ContextEntry find(ContextEntry[] entries, Object element) {
        if (entries != null) {
            for (ContextEntry entry : entries) if (entry.element == element) return entry; // never a Job entry: those hold no element
        }
        return null;
    }

    /** CONTEXT_CHANGED for everything but the dispatcher, DISPATCHER_CHANGED for the dispatcher; the Job is not news. */
    private static void emitContextDiff(JobNode node, ThreadNode thread, ContextEntry[] before) {
        ArrayList<TraceEvent.ContextChangeDef> general = new ArrayList<>();
        TraceEvent.ContextChangeDef dispatcher = null;
        for (ContextEntry now : node.context) {
            if (now.kind == Wire.CTX_JOB || find(before, now.element) != null) continue;
            ContextEntry old = byKey(before, now.key);
            if (old != null && old.value.equals(now.value)) continue; // a new instance that reads the same
            TraceEvent.ContextChangeDef change = change(now.kind, now.key, old, now);
            if (now.kind == Wire.CTX_DISPATCHER) dispatcher = change;
            else general.add(change);
        }
        if (before != null) {
            for (ContextEntry old : before) {
                if (old.kind != Wire.CTX_JOB && byKey(node.context, old.key) == null) general.add(change(old.kind, old.key, old, null));
            }
        }
        if (!general.isEmpty()) {
            TraceEvent event = new TraceEvent(node.id, Wire.CONTEXT_CHANGED, thread.id);
            event.contextDiff = general.toArray(new TraceEvent.ContextChangeDef[0]);
            Tracer.emit(event);
        }
        if (dispatcher != null) {
            TraceEvent event = new TraceEvent(node.id, Wire.DISPATCHER_CHANGED, thread.id);
            event.contextDiff = new TraceEvent.ContextChangeDef[] {dispatcher};
            Tracer.emit(event);
        }
    }

    private static ContextEntry byKey(ContextEntry[] entries, String key) {
        if (entries != null) {
            for (ContextEntry entry : entries) if (entry.key.equals(key)) return entry;
        }
        return null;
    }

    private static TraceEvent.ContextChangeDef change(int kind, String key, ContextEntry old, ContextEntry now) {
        TraceEvent.ContextChangeDef change = new TraceEvent.ContextChangeDef();
        change.kind = kind;
        change.key = key;
        change.oldValue = old == null ? "" : old.value;
        change.newValue = now == null ? "" : now.value;
        change.added = old == null;
        change.removed = now == null;
        return change;
    }

    /** The node of a job, made on first demand. Publishing the tag under a lock keeps it to one node per job. */
    private static JobNode obtainNode(Tagged job, ClassInfo info) {
        Object tag = job.coroutree$tag();
        if (tag != null) return (JobNode) tag;
        synchronized (DISCOVERY_LOCK) {
            tag = job.coroutree$tag();
            if (tag != null) return (JobNode) tag;
            JobNode node = new JobNode(Tracer.newNodeId(), info.startsUndispatched, info.deferred);
            job.coroutree$tag(node);
            return node;
        }
    }

    /**
     * The node of a job that something happened to. A job of a class whose constructor is hooked gets its definition
     * from that hook, even if this is called first (see {@link #createJobNode}); any other job is DISCOVERED here.
     */
    private static JobNode nodeOf(Tagged job, ThreadState ts) {
        ClassInfo info = ClassInfo.of(job.getClass());
        JobNode node = obtainNode(job, info);
        if (!node.defined && !info.constructionHooked) discover(job, node, info, ts);
        return node;
    }

    private static void discover(Tagged job, JobNode node, ClassInfo info, ThreadState ts) {
        synchronized (DISCOVERY_LOCK) {
            if (node.defined) return;
            node.defined = true;
            TraceEvent.NodeDef def = new TraceEvent.NodeDef();
            def.id = node.id;
            def.kind = info.kind;
            def.construct = info.construct;
            def.implClass = job.getClass().getName();
            def.origin = Wire.ORIGIN_LIBRARY;
            TraceEvent event = new TraceEvent(node.id, Wire.DISCOVERED, threadNode(ts).id);
            event.node = def;
            Tracer.emit(event);
        }
    }

    private static void setSite(TraceEvent.NodeDef def, StackFrameRef[] stack, int site) {
        if (site < 0) {
            def.origin = Wire.ORIGIN_LIBRARY;
        } else {
            def.siteFrame = stack[site];
            def.origin = Tracer.config.isProjectClass(stack[site].className) ? Wire.ORIGIN_PROJECT : Wire.ORIGIN_LIBRARY;
        }
    }

    // ------------------------------------------------------------------ coroutines: suspend and resume

    /** {@code probeCoroutineResumed}: a continuation frame of some coroutine is about to run on this thread. */
    public static void coroutineResumed(Object frame, Object context) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            JobNode node = nodeOfContext(context, ts);
            if (node == null || node.finished) return;
            long tid = ts.thread.threadId();
            // A peek without the lock, to do the expensive part outside of it; the decision is made under the lock.
            StackFrameRef[] suspendedAt = node.run == JobNode.RUNNING && node.runThread != tid ? suspensionStack(frame) : null;
            synchronized (node) {
                if (node.run != JobNode.RUNNING || node.runThread != tid) {
                    long thread = threadNode(ts).id;
                    if (node.run == JobNode.RUNNING) {
                        // Running on another thread by our books: it suspended there and was resumed here before
                        // that thread got to say so. Say it for that thread, and ignore its report when it comes.
                        // Where it was suspended is no secret either: it is the frame that is being resumed.
                        node.lateSuspends++;
                        TraceEvent suspended = new TraceEvent(node.id, Wire.SUSPENDED, thread);
                        suspended.stack = suspendedAt != null ? suspendedAt : suspensionStack(frame);
                        Tracer.emit(suspended);
                    }
                    node.run = JobNode.RUNNING;
                    node.runThread = tid;
                    Tracer.emit(new TraceEvent(node.id, Wire.RESUMED, thread));
                }
                // else: the probe fires for every frame as a suspend call chain unwinds; the coroutine is already running here
            }
            ts.pushUnit(node);
        } catch (Throwable e) {
            Tracer.reportInternalError("coroutineResumed", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** {@code probeCoroutineSuspended}: the coroutine that owns {@code frame} is leaving this thread. */
    public static void coroutineSuspended(Object frame, Object context) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            JobNode node = nodeOfContext(context, ts);
            if (node == null) return;
            ts.popUnit(node);
            if (node.finished) return;
            long tid = ts.thread.threadId();
            // Reading debug metadata runs library code and may load classes: not something to do holding a lock.
            StackFrameRef[] stack = suspensionStack(frame);
            synchronized (node) {
                if (node.lateSuspends > 0 && node.runThread != tid) {
                    node.lateSuspends--;
                } else if (node.run == JobNode.RUNNING) {
                    node.run = JobNode.SUSPENDED;
                    TraceEvent event = new TraceEvent(node.id, Wire.SUSPENDED, threadNode(ts).id);
                    event.stack = stack;
                    Tracer.emit(event);
                }
            }
        } catch (Throwable e) {
            Tracer.reportInternalError("coroutineSuspended", e);
        } finally {
            ts.inHook = false;
        }
    }

    private static StackFrameRef[] suspensionStack(Object frame) throws Throwable {
        return KotlinAccess.get().coroutineStack(frame, Tracer.config.stackDepth);
    }

    private static JobNode nodeOfContext(Object context, ThreadState ts) throws Throwable {
        KotlinAccess access = KotlinAccess.get();
        if (access == null) return null; // no job has been created yet, so this continuation has none
        Tagged job = access.job(context);
        return job == null ? null : nodeOf(job, ts);
    }

    // ------------------------------------------------------------------ coroutines: cancellation

    /** {@code Job.cancel(cause)}: somebody asked for cancellation explicitly. */
    public static void cancelRequested(Object job, Throwable cause) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (!(job instanceof Tagged tagged)) return;
            JobNode node = nodeOf(tagged, ts);
            if (node.finished) return;
            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            TraceEvent event = new TraceEvent(node.id, Wire.CANCELLATION_REQUESTED, thread.id);
            event.otherNodeId = unit != null ? unit.id : thread.id;
            event.stack = StackCapture.capture(Tracer.config.stackDepth);
            if (cause != null) event.exception = Describe.exception(cause, false, 0);
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("cancelRequested", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** {@code JobSupport.parentCancelled}: the parent is cancelling and takes this child along. */
    public static void parentCancelled(Object child, Object parent) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (!(child instanceof Tagged c) || !(parent instanceof Tagged p)) return;
            JobNode node = nodeOf(c, ts);
            if (node.finished || node.cancelling) return; // e.g. the failed child that made the parent cancel in the first place
            TraceEvent event = new TraceEvent(node.id, Wire.CANCELLATION_PROPAGATED, threadNode(ts).id);
            event.otherNodeId = nodeOf(p, ts).id;
            event.direction = Wire.PARENT_TO_CHILD;
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("parentCancelled", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** {@code JobSupport.notifyCancelling}: the job entered the cancelling state, for whatever reason. */
    public static void cancelling(Object job, Throwable cause) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (!(job instanceof Tagged tagged)) return;
            JobNode node = nodeOf(tagged, ts);
            if (node.cancelling || node.finished) return;
            node.cancelling = true;
            TraceEvent event = new TraceEvent(node.id, Wire.CANCELLING, threadNode(ts).id);
            if (cause != null) event.exception = Describe.exception(cause, false, 0);
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("cancelling", e);
        } finally {
            ts.inHook = false;
        }
    }

    // ------------------------------------------------------------------ coroutines: exceptions and completion

    /** {@code JobSupport.makeCompletingOnce}: the body of the coroutine is over, with a result or with an exception. */
    public static void completing(Object job, Object proposedUpdate) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.get();
            if (access == null || !(job instanceof Tagged tagged)) return;
            JobNode node = nodeOf(tagged, ts);
            // Its code is done with this thread even if the job now waits for children and completes elsewhere.
            ts.popUnit(node);
            Throwable failure = access.failureOf(proposedUpdate);
            if (failure == null || failure instanceof CancellationException) return;
            if (node.isRethrowOfReceived(failure)) return; // came out of a scope it called; already recorded as propagated
            TraceEvent event = new TraceEvent(node.id, Wire.EXCEPTION_THROWN, threadNode(ts).id);
            event.exception = Describe.exception(failure, true, Tracer.config.stackDepth);
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("completing", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** {@code JobSupport.completeStateFinalization}: the job reached its final state. Runs exactly once per job. */
    public static void completed(Object job, Object finalState) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.get();
            if (access == null || !(job instanceof Tagged tagged)) return;
            JobNode node = nodeOf(tagged, ts);
            if (node.finished) return;
            node.finished = true;
            ts.popUnit(node);
            long thread = threadNode(ts).id;
            Throwable failure = access.failureOf(finalState);
            boolean failed = failure != null && !(failure instanceof CancellationException);
            if (failed && node.deferred) {
                TraceEvent held = new TraceEvent(node.id, Wire.EXCEPTION_HANDLED, thread);
                held.handledBy = Wire.BY_DEFERRED_HELD;
                held.exception = Describe.exception(failure, false, 0);
                Tracer.emit(held);
            }
            if (failed && node.rethrowsToId != 0) {
                // The exception continues as a throw from coroutineScope/withContext/runBlocking/… in the caller's code.
                JobNode caller = node.rethrowsTo;
                if (caller != null) caller.markReceived(failure);
                TraceEvent propagated = new TraceEvent(node.rethrowsToId, Wire.EXCEPTION_PROPAGATED, thread);
                propagated.otherNodeId = node.id;
                propagated.direction = Wire.CHILD_TO_PARENT;
                propagated.exception = Describe.exception(failure, false, 0);
                Tracer.emit(propagated);
            }
            TraceEvent event = new TraceEvent(node.id, Wire.FINISHED, thread);
            event.finalState = failure == null ? Wire.STATE_COMPLETED : failed ? Wire.STATE_FAILED : Wire.STATE_CANCELLED;
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("completed", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** Start of {@code ChildHandleNode.childCancelled}: a failing child tells its parent. */
    public static void childCancelled(Object child, Object parent, Throwable cause) {
        if (!Tracer.active || cause instanceof CancellationException) return; // a cancelled child does not concern the parent
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (!(child instanceof Tagged c) || !(parent instanceof Tagged p)) return;
            JobNode childNode = nodeOf(c, ts);
            if (!childNode.markPropagated(cause)) return; // the library reports to the parent twice: when cancelling and when final
            TraceEvent event = new TraceEvent(nodeOf(p, ts).id, Wire.EXCEPTION_PROPAGATED, threadNode(ts).id);
            event.otherNodeId = childNode.id;
            event.direction = Wire.CHILD_TO_PARENT;
            event.exception = Describe.exception(cause, false, 0);
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("childCancelled", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** End of {@code ChildHandleNode.childCancelled}: {@code handled} is the parent's answer. */
    public static void childCancelledResult(boolean handled, Object child, Object parent, Throwable cause) {
        if (!Tracer.active || handled || cause instanceof CancellationException) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (!(child instanceof Tagged c) || !(parent instanceof Tagged p)) return;
            JobNode parentNode = nodeOf(p, ts);
            // "Not handled" has two meanings. A parent that went down with the child just has nobody to report the
            // exception to (the child's own handler is next). A parent that is unaffected is a supervisor.
            if (parentNode.cancelling || parentNode.finished) return;
            JobNode childNode = nodeOf(c, ts);
            if (!childNode.markStopped(cause)) return;
            TraceEvent event = new TraceEvent(parentNode.id, Wire.EXCEPTION_HANDLED, threadNode(ts).id);
            event.otherNodeId = childNode.id;
            event.handledBy = Wire.BY_SUPERVISOR;
            event.exception = Describe.exception(cause, false, 0);
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("childCancelledResult", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** {@code handleCoroutineException}: the exception reached the end of the line for coroutines. */
    public static void exceptionReachedHandler(Object context, Throwable exception) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.get();
            if (access == null) return;
            ThreadNode thread = threadNode(ts);
            JobNode node = nodeOfContext(context, ts);
            if (node == null) node = ts.currentUnit();
            TraceEvent event = new TraceEvent(node != null ? node.id : thread.id, Wire.EXCEPTION_HANDLED, thread.id);
            event.handledBy = access.hasExceptionHandler(context) ? Wire.BY_COROUTINE_EXCEPTION_HANDLER : Wire.BY_UNCAUGHT_EXCEPTION_HANDLER;
            event.exception = Describe.exception(exception, false, 0);
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("exceptionReachedHandler", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** Start of a typed catch block in project code. */
    public static void exceptionCaught(Throwable exception) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            TraceEvent event = new TraceEvent(unit != null ? unit.id : thread.id, Wire.EXCEPTION_HANDLED, thread.id);
            event.handledBy = Wire.BY_CATCH;
            event.exception = Describe.exception(exception, true, Tracer.config.stackDepth);
            event.stack = StackCapture.capture(Tracer.config.stackDepth);
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("exceptionCaught", e);
        } finally {
            ts.inHook = false;
        }
    }

    // ------------------------------------------------------------------ threads: lifecycle

    /** {@code Thread.start}, on the starting thread. */
    public static void threadStart(Thread thread) {
        if (!Tracer.active || thread instanceof AgentThread) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            ThreadNode starter = threadNode(ts);
            JobNode unit = ts.currentUnit();
            long creator = unit != null ? unit.id : starter.id;
            StackFrameRef[] stack = StackCapture.captureForSite();

            // The node may exist, undefined, if the thread was heard of before it was started (see threadNode).
            ThreadNode node = THREADS.get(thread);
            if (node == null) {
                node = new ThreadNode(Tracer.newNodeId());
                ThreadNode raced = THREADS.putIfAbsent(thread, node);
                if (raced != null) node = raced;
            }
            synchronized (node) {
                if (node.defined) return; // a virtual thread passes through two instrumented start methods
                node.defined = true;
            }
            TraceEvent.NodeDef def = threadDef(node, thread);
            def.creatorId = creator;
            long pool = poolOf(thread, creator, starter);
            if (pool != 0) {
                // Which thread happened to make the pool grow is an accident, and so is the line it was at.
                def.parentId = pool;
                def.construct = "worker";
                def.origin = Wire.ORIGIN_LIBRARY;
            } else {
                def.parentId = starter.id;
                int site = StackCapture.siteIndex(stack);
                setSite(def, stack, site);
                if (StackCapture.startedDirectlyBySite(stack, site)) {
                    def.construct = StackCapture.construct(stack, site, "Thread.start");
                } else {
                    // Started by a runtime as a side effect of whatever the site called (a timer thread behind delay,
                    // an executor growing): the site is worth showing, but this is not a thread the project made.
                    def.construct = "Thread.start";
                    def.origin = Wire.ORIGIN_LIBRARY;
                }
            }
            TraceEvent event = new TraceEvent(node.id, Wire.LAUNCHED, starter.id);
            event.node = def;
            event.stack = StackCapture.limit(stack, Tracer.config.stackDepth);
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("threadStart", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** {@code Thread.exit} / end of {@code VirtualThread.run}, on the thread that is ending. */
    public static void threadExit(Thread thread) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            ThreadNode node = THREADS.get(thread);
            if (node == null || node.finished) return; // never did anything we saw: not worth a node now
            node.finished = true;
            TraceEvent event = new TraceEvent(node.id, Wire.FINISHED, node.id);
            event.finalState = node.failed ? Wire.STATE_FAILED : Wire.STATE_COMPLETED;
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("threadExit", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** {@code Thread.interrupt}, on the interrupting thread. */
    public static void threadInterrupt(Thread target) {
        if (!Tracer.active || target instanceof AgentThread) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            TraceEvent event = new TraceEvent(threadNode(target, thread.id).id, Wire.THREAD_INTERRUPTED, thread.id);
            event.otherNodeId = unit != null ? unit.id : thread.id;
            event.stack = StackCapture.capture(Tracer.config.stackDepth);
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("threadInterrupt", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** {@code Thread.dispatchUncaughtException}, on the dying thread. */
    public static void threadUncaught(Thread thread, Throwable exception) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            ThreadNode node = threadNode(ts);
            node.failed = true;
            TraceEvent thrown = new TraceEvent(node.id, Wire.EXCEPTION_THROWN, node.id);
            thrown.exception = Describe.exception(exception, true, Tracer.config.stackDepth);
            Tracer.emit(thrown);
            TraceEvent handled = new TraceEvent(node.id, Wire.EXCEPTION_HANDLED, node.id);
            handled.handledBy = Wire.BY_UNCAUGHT_EXCEPTION_HANDLER;
            handled.exception = Describe.exception(exception, false, 0);
            Tracer.emit(handled);
        } catch (Throwable e) {
            Tracer.reportInternalError("threadUncaught", e);
        } finally {
            ts.inHook = false;
        }
    }

    private static ThreadNode threadNode(ThreadState ts) {
        ThreadNode node = ts.node;
        if (node == null) {
            node = threadNode(ts.thread, 0);
            ts.node = node;
        }
        return node;
    }

    /**
     * The node of a thread, DISCOVERED here if its start was not seen (main, JVM threads). A thread that has not been
     * started yet — interrupting one is legal — is left undefined: its start will be seen, and LAUNCHED defines it.
     */
    private static ThreadNode threadNode(Thread thread, long reportingThread) {
        ThreadNode node = THREADS.get(thread);
        if (node == null) {
            node = new ThreadNode(Tracer.newNodeId());
            ThreadNode raced = THREADS.putIfAbsent(thread, node);
            if (raced != null) node = raced;
        }
        if (node.defined || thread.getState() == Thread.State.NEW) return node;
        synchronized (node) {
            if (node.defined) return node;
            node.defined = true;
        }
        TraceEvent.NodeDef def = threadDef(node, thread);
        def.origin = Wire.ORIGIN_LIBRARY;
        TraceEvent event = new TraceEvent(node.id, Wire.DISCOVERED, reportingThread != 0 ? reportingThread : node.id);
        event.node = def;
        Tracer.emit(event);
        return node;
    }

    private static TraceEvent.NodeDef threadDef(ThreadNode node, Thread thread) {
        TraceEvent.NodeDef def = new TraceEvent.NodeDef();
        def.id = node.id;
        def.kind = Wire.KIND_THREAD;
        def.name = thread.getName();
        def.implClass = thread.getClass().getName();
        def.isThread = true;
        def.tid = thread.threadId();
        def.virtual = thread.isVirtual();
        def.daemon = thread.isDaemon();
        return def;
    }

    /** Node id of the pool a worker thread belongs to, 0 if it is not a pool worker this agent knows how to recognise. */
    private static long poolOf(Thread thread, long creator, ThreadNode starter) {
        Object pool = null;
        String name = null;
        try {
            if (thread instanceof ForkJoinWorkerThread worker) {
                pool = worker.getPool();
                name = thread.getClass().getName().equals("jdk.internal.misc.CarrierThread") ? "virtual thread carriers"
                    : pool == ForkJoinPool.commonPool() ? "ForkJoinPool.commonPool"
                    : "ForkJoinPool";
            } else if (thread.getClass().getName().equals("kotlinx.coroutines.scheduling.CoroutineScheduler$Worker")) {
                java.lang.reflect.Field outer = thread.getClass().getDeclaredField("this$0");
                outer.setAccessible(true);
                pool = outer.get(thread);
                name = String.valueOf(pool.getClass().getField("schedulerName").get(pool));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
        if (pool == null) return 0;
        Long known = POOLS.get(pool);
        if (known != null) return known;
        long id = Tracer.newNodeId();
        known = POOLS.putIfAbsent(pool, id);
        if (known != null) return known;
        TraceEvent.NodeDef def = new TraceEvent.NodeDef();
        def.id = id;
        def.kind = Wire.KIND_POOL;
        def.name = name;
        def.implClass = pool.getClass().getName();
        def.creatorId = creator;
        def.origin = Wire.ORIGIN_LIBRARY;
        TraceEvent event = new TraceEvent(id, Wire.LAUNCHED, starter.id);
        event.node = def;
        Tracer.emit(event);
        return id;
    }

    // ------------------------------------------------------------------ threads: blocking

    /**
     * Start of an instrumented blocking method; {@code reason} is a {@code Wire.BLOCK_*} constant. Blocking methods
     * call each other ({@code join} waits with {@code wait}, {@code sleep} on a virtual thread parks), so only the
     * outermost one of a unit is reported. The depth is kept even when nothing is reported: every enter has an exit.
     */
    public static void blockEnter(int reason) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            ts.blockDepth++;
            if (reason == NOT_REPORTED || ts.blockEmittedAt != 0) return;
            if (reason == Wire.BLOCK_PARK && isRuntimeHousekeeping(StackCapture.capture(4))) return;
            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            ts.blockEmittedAt = ts.blockDepth;
            ts.blockOtherNodeId = unit != null ? unit.id : 0;
            TraceEvent event = new TraceEvent(thread.id, Wire.THREAD_BLOCKED, thread.id);
            event.otherNodeId = ts.blockOtherNodeId;
            event.blockReason = reason;
            event.stack = StackCapture.capture(Tracer.config.stackDepth);
            Tracer.emit(event);
        } catch (Throwable e) {
            Tracer.reportInternalError("blockEnter", e);
        } finally {
            ts.inHook = false;
        }
    }

    /** Reading standard input blocks for as long as the user likes; reading a file does not count as blocking. */
    public static void blockEnterIfStdin(Object stream) {
        boolean stdin = false;
        try {
            stdin = stream instanceof FileInputStream in && in.getFD() == FileDescriptor.in;
        } catch (Throwable ignored) {
        }
        // Not blocking still enters, unreported: the exit hook cannot tell and will decrement.
        blockEnter(stdin ? Wire.BLOCK_IO : NOT_REPORTED);
    }

    private static final int NOT_REPORTED = -1;

    /** End of an instrumented blocking method, normal or exceptional. */
    public static void blockExit() {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (ts.blockDepth == 0) return; // entered before tracing was on
            if (ts.blockEmittedAt == ts.blockDepth) {
                ts.blockEmittedAt = 0;
                ThreadNode thread = threadNode(ts);
                TraceEvent event = new TraceEvent(thread.id, Wire.THREAD_UNBLOCKED, thread.id);
                event.otherNodeId = ts.blockOtherNodeId;
                Tracer.emit(event);
            }
            ts.blockDepth--;
        } catch (Throwable e) {
            Tracer.reportInternalError("blockExit", e);
        } finally {
            ts.inHook = false;
        }
    }

    /**
     * A park made by kotlinx.coroutines itself is a dispatcher worker or an event loop waiting for work. That is the
     * thread being idle, not something blocking it. ({@code runBlocking} is reported separately, as such.)
     */
    private static boolean isRuntimeHousekeeping(StackFrameRef[] top) {
        for (StackFrameRef frame : top) {
            if (frame.className.startsWith("java.util.concurrent.locks.")) continue;
            return frame.className.startsWith("kotlinx.coroutines.");
        }
        return false;
    }
}
