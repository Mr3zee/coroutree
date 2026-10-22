package kotlinx.coroutree.runtime;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Hooks must suppress recursive entry and catch every failure. The bootstrap loader can't resolve Kotlin types,
 * so arguments use {@code Object}.
 * A hook must follow this order because execution control can hold the thread ({@link Pace}, DESIGN §3.1):
 * <ol>
 * <li>Find all nodes involved. {@link #discover}, {@link #threadNode}, and {@link #poolOf} can emit separate steps.</li>
 * <li>Call {@code Pace.await}.</li>
 * <li>Read shared state, decide, lock, and emit.</li>
 * </ol>
 * Before await, read only arguments, thread-confined state, and preliminary checks that can avoid an empty step.
 * Repeat preliminary checks after the hold. {@link Tracer#emit} reports events that bypass await as internal errors.
 */
public final class Hooks {
    private Hooks() {}

    private static final WeakIdentityMap<Thread, ThreadNode> THREADS = new WeakIdentityMap<>();
    private static final WeakIdentityMap<Object, PoolNode> POOLS = new WeakIdentityMap<>();
    private static final Object DISCOVERY_LOCK = new Object();

    /** The execution unit whose code is running on this thread: the innermost coroutine, or the thread itself. */
    private static PaceNode unitOn(ThreadState ts) {
        JobNode unit = ts.currentUnit();
        return unit != null ? unit : threadNode(ts);
    }

    public static void coroutineCreated(Object coroutine, Object parentContext, Object context) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.forJob(coroutine);
            if (access == null || !(coroutine instanceof Tagged tagged)) return;
            Tagged parent = access.job(parentContext);
            createJobNode(ts, access, tagged, parent, context);
        } catch (Throwable e) {
            Tracer.reportInternalError("coroutineCreated", e);
        } finally {
            ts.exitHook();
        }
    }

    public static void jobCreated(Object job, Object parent) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.forJob(job);
            if (access == null || !(job instanceof Tagged tagged)) return;
            createJobNode(ts, access, tagged, parent instanceof Tagged p ? p : null, null);
        } catch (Throwable e) {
            Tracer.reportInternalError("jobCreated", e);
        } finally {
            ts.exitHook();
        }
    }

    private static void createJobNode(ThreadState ts, KotlinAccess access, Tagged job, Tagged parent, Object context) throws Throwable {
        ClassInfo info = ClassInfo.of(job.getClass());
        // A cancelled parent can emit cancellation events from the child's constructor before this hook defines the child.
        // Publish the tag before slow work because the parent already exposes the job to other threads.
        JobNode node = obtainNode(job, info);
        if (node.defined) return;
        JobNode parentNode = parent == null ? null : nodeOf(parent, ts);
        ThreadNode thread = threadNode(ts);
        JobNode unit = ts.currentUnit();
        PaceNode creatorNode = unit != null ? unit : thread;
        long creator = creatorNode.id;
        // Scopes (withContext to another dispatcher included) and runBlocking run in their caller's place.
        boolean calledInPlace = info.scoped || info.blocking;
        if (Pace.GATE != null) {
            // Set ancestry before await so a paused subtree also holds a new child's launch.
            node.paceParent = parentNode != null ? parentNode : calledInPlace ? creatorNode : null;
            if (calledInPlace) node.caller = creatorNode;
        }
        Pace.await(ts, creatorNode, node, null);

        StackFrameRef[] stack = StackCapture.captureForSite();
        int site = StackCapture.siteIndex(stack);
        String construct = StackCapture.construct(stack, site, info.construct);

        TraceEvent.NodeDef def = new TraceEvent.NodeDef();
        def.id = node.id;
        // withContext is a context change even when an unchanged dispatcher uses an ordinary scope coroutine.
        def.kind = construct.equals("withContext") ? Wire.KIND_CONTEXT_CHANGE : info.kind;
        def.construct = construct;
        def.implClass = job.getClass().getName();
        // A scope without a known parent job belongs under its waiting caller, including withContext(NonCancellable).
        def.parentId = parentNode != null ? parentNode.id : calledInPlace ? creator : 0;
        def.creatorId = creator;
        setSite(def, stack, site);

        ContextEntry[] parentContext = parentNode != null ? parentNode.context : info.scoped && unit != null ? unit.context : null;
        if (context != null) {
            ArrayList<ContextEntry> entries = new ArrayList<>();
            for (Object element : access.elements(context)) {
                ContextEntry inherited = find(parentContext, element);
                ContextEntry entry = inherited != null ? inherited : access.describe(element);
                if (entry == null) continue;
                entries.add(entry);
                // An inherited CoroutineName names the parent, not this node.
                if (inherited == null && entry.kind == Wire.CTX_NAME) def.name = entry.value;
            }
            node.context = entries.toArray(new ContextEntry[0]);
            def.context = node.context;
        } else {
            node.context = parentContext; // a plain Job has no context, so children compare against the grandparent
        }

        if (node.startsUndispatched) {
            synchronized (node) {
                node.run = JobNode.RUNNING;
                node.runThread = ts.thread.threadId();
            }
        }
        if (calledInPlace) {
            node.rethrowsToId = creator;
            node.rethrowsTo = unit;
        }
        node.defined = true;

        TraceEvent launched = new TraceEvent(node.id, Wire.LAUNCHED, thread.id);
        launched.node = def;
        launched.stack = StackCapture.limit(stack, Tracer.config.stackDepth);
        Tracer.emit(ts, launched);
        if (context != null) emitContextDiff(ts, node, thread, parentContext);
        if (node.startsUndispatched && !node.finished) ts.pushUnit(node);
    }

    private static ContextEntry find(ContextEntry[] entries, Object element) {
        if (entries != null) {
            for (ContextEntry entry : entries) if (entry.element == element) return entry; // never a Job entry: those hold no element
        }
        return null;
    }

    private static void emitContextDiff(ThreadState ts, JobNode node, ThreadNode thread, ContextEntry[] before) {
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
            Tracer.emit(ts, event);
        }
        if (dispatcher != null) {
            TraceEvent event = new TraceEvent(node.id, Wire.DISPATCHER_CHANGED, thread.id);
            event.contextDiff = new TraceEvent.ContextChangeDef[] {dispatcher};
            Tracer.emit(ts, event);
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
     * Hooked constructors define their nodes through {@link #createJobNode}, even if this method runs first.
     * Other jobs are discovered here.
     */
    private static JobNode nodeOf(Tagged job, ThreadState ts) {
        ClassInfo info = ClassInfo.of(job.getClass());
        JobNode node = obtainNode(job, info);
        if (!node.defined && !info.constructionHooked) discover(job, node, info, ts);
        return node;
    }

    private static void discover(Tagged job, JobNode node, ClassInfo info, ThreadState ts) {
        ThreadNode thread = threadNode(ts);
        JobNode unit = ts.currentUnit();
        Pace.await(ts, unit != null ? unit : thread, node, null);
        synchronized (DISCOVERY_LOCK) {
            if (node.defined) return;
            node.defined = true;
            TraceEvent.NodeDef def = new TraceEvent.NodeDef();
            def.id = node.id;
            def.kind = info.kind;
            def.construct = info.construct;
            def.implClass = job.getClass().getName();
            def.origin = Wire.ORIGIN_LIBRARY;
            TraceEvent event = new TraceEvent(node.id, Wire.DISCOVERED, thread.id);
            event.node = def;
            Tracer.emit(ts, event);
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

    private static void ended(JobNode node) {
        if (Pace.GATE == null) return;
        Pace.nodeFinished(node);
        // Children finish before their job, and callees before their caller. No remaining node needs this ancestry.
        node.paceParent = null;
        node.caller = null;
    }

    public static void coroutineResumed(Object frame, Object context) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            JobNode node = unitOf(frame, context, ts);
            if (node == null || node.finished) return;
            long tid = ts.thread.threadId();
            boolean runningHere;
            synchronized (node) {
                runningHere = node.run == JobNode.RUNNING && node.runThread == tid;
            }
            if (runningHere) {
                // Each frame emits a resume probe, but frames of the same running coroutine don't create separate steps.
                ts.pushUnit(node);
                return;
            }
            long thread = threadNode(ts).id;
            // Await as the coroutine before pushUnit. Using the thread would serialize sibling coroutines on that thread.
            Pace.await(ts, node, node, null);
            // Capture the stack outside the lock, then recheck the state under the lock.
            StackFrameRef[] suspendedAt = node.run == JobNode.RUNNING && node.runThread != tid ? suspensionStack(frame) : null;
            synchronized (node) {
                if (node.run != JobNode.RUNNING || node.runThread != tid) {
                    if (node.run == JobNode.RUNNING) {
                        // Resume can arrive before the other thread's suspend probe. Record that suspension from this frame
                        // and ignore the late probe.
                        node.lateSuspends++;
                        TraceEvent suspended = new TraceEvent(node.id, Wire.SUSPENDED, thread);
                        suspended.stack = suspendedAt != null ? suspendedAt : suspensionStack(frame);
                        Tracer.emit(ts, suspended);
                    }
                    node.run = JobNode.RUNNING;
                    node.runThread = tid;
                    Tracer.emit(ts, new TraceEvent(node.id, Wire.RESUMED, thread));
                }
            }
            ts.pushUnit(node);
        } catch (Throwable e) {
            Tracer.reportInternalError("coroutineResumed", e);
        } finally {
            ts.exitHook();
        }
    }

    public static void coroutineSuspended(Object frame, Object context) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            JobNode node = unitOf(frame, context, ts);
            if (node == null) return;
            ts.popUnit(node);
            if (node.finished) return;
            long tid = ts.thread.threadId();
            boolean reportedAlready, running;
            synchronized (node) {
                reportedAlready = node.lateSuspends > 0 && node.runThread != tid;
                running = node.run == JobNode.RUNNING;
            }
            if (!reportedAlready && !running) return;
            long thread = threadNode(ts).id;
            StackFrameRef[] stack = null;
            if (!reportedAlready) {
                // A concurrent resume awaits the same node, so the suspension frame remains valid for this read.
                Pace.await(ts, node, node, null);
                // Debug metadata access can load classes, so read it outside the lock.
                stack = suspensionStack(frame);
            }
            synchronized (node) {
                if (node.lateSuspends > 0 && node.runThread != tid) {
                    node.lateSuspends--;
                } else if (node.run == JobNode.RUNNING && !reportedAlready) {
                    node.run = JobNode.SUSPENDED;
                    TraceEvent event = new TraceEvent(node.id, Wire.SUSPENDED, thread);
                    event.stack = stack;
                    Tracer.emit(ts, event);
                }
            }
        } catch (Throwable e) {
            Tracer.reportInternalError("coroutineSuspended", e);
        } finally {
            ts.exitHook();
        }
    }

    private static StackFrameRef[] suspensionStack(Object frame) throws Throwable {
        return KotlinAccess.get().coroutineStack(frame, Tracer.config.stackDepth);
    }

    private static JobNode nodeOfContext(Object context, ThreadState ts) throws Throwable {
        KotlinAccess access = KotlinAccess.get();
        if (access == null) return null; // nothing of Kotlin's coroutines has been seen yet
        Tagged job = access.job(context);
        return job == null ? null : nodeOf(job, ts);
    }

    /** The coroutine a frame belongs to: the one of the Job in its context, or a jobless one found by its root frame. */
    private static JobNode unitOf(Object frame, Object context, ThreadState ts) throws Throwable {
        KotlinAccess access = KotlinAccess.get();
        if (access == null) return null;
        Tagged job = access.job(context);
        return job != null ? nodeOf(job, ts) : joblessNode(access, frame, null);
    }

    /**
     * Jobless coroutines are keyed by root frame. A completion object can't identify a coroutine because several coroutines can share it.
     */
    private static final WeakIdentityMap<Object, JobNode> JOBLESS = new WeakIdentityMap<>();

    /**
     * Excludes coroutines with known Jobs and generators. A generator's yield produces a value, not a concurrency event.
     */
    public static void continuationCreated(Object continuation) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.forContinuation(continuation);
            if (access == null || !access.isCompiledFrame(continuation) || access.isGeneratorFrame(continuation)) return;
            Object context = access.contextOf(continuation);
            if (access.hasJob(context)) return;
            JobNode node = new JobNode(Tracer.newNodeId(), false, false);
            if (JOBLESS.putIfAbsent(continuation, node) != null) return;

            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            PaceNode creatorNode = unit != null ? unit : thread;
            long creator = creatorNode.id;
            // Capture before await because the construct determines ancestry. This thread's stack can't change during the hold.
            StackFrameRef[] stack = StackCapture.captureForSite();
            int site = StackCapture.siteIndex(stack);
            String construct = StackCapture.construct(stack, site, "startCoroutine");
            // The thread waits for suspend main and receives its failure. Other jobless coroutines are independent roots.
            boolean suspendMain = construct.equals(StackCapture.SUSPEND_MAIN);
            if (suspendMain && Pace.GATE != null) {
                node.paceParent = creatorNode;
                node.caller = creatorNode;
            }
            Pace.await(ts, creatorNode, node, null);

            TraceEvent.NodeDef def = new TraceEvent.NodeDef();
            def.id = node.id;
            def.kind = Wire.KIND_COROUTINE;
            def.construct = construct;
            Object completion = access.completionOf(continuation);
            def.implClass = completion == null ? "" : completion.getClass().getName();
            def.creatorId = creator;
            if (suspendMain) {
                def.parentId = creator;
                node.rethrowsToId = creator;
                node.rethrowsTo = unit;
            }
            setSite(def, stack, site);

            ContextEntry[] parentContext = def.parentId != 0 && unit != null ? unit.context : null;
            ArrayList<ContextEntry> entries = new ArrayList<>();
            for (Object element : access.elements(context)) {
                ContextEntry inherited = find(parentContext, element);
                ContextEntry entry = inherited != null ? inherited : access.describe(element);
                if (entry == null) continue;
                entries.add(entry);
                if (inherited == null && entry.kind == Wire.CTX_NAME) def.name = entry.value;
            }
            node.context = entries.toArray(new ContextEntry[0]);
            def.context = node.context;
            node.defined = true;

            TraceEvent launched = new TraceEvent(node.id, Wire.LAUNCHED, thread.id);
            launched.node = def;
            launched.stack = StackCapture.limit(stack, Tracer.config.stackDepth);
            Tracer.emit(ts, launched);
            emitContextDiff(ts, node, thread, parentContext);
        } catch (Throwable e) {
            Tracer.reportInternalError("continuationCreated", e);
        } finally {
            ts.exitHook();
        }
    }

    /**
     * {@code frame} can be any frame where this run of the coroutine began, not necessarily its root.
     */
    public static void continuationCompleted(Object completion, Object result, Object frame) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.get();
            if (access == null || JOBLESS.isEmpty() || access.job(access.contextOf(frame)) != null) return;
            JobNode node = joblessNode(access, frame, completion);
            if (node == null || node.finished) return;
            long thread = threadNode(ts).id;
            // Without a Job, CancellationException is a failure rather than cancellation.
            Throwable failure = access.failureOfResult(result);
            PaceNode unit = unitOn(ts); // capture the unit before popUnit
            Pace.await(ts, unit, node, failure != null ? node.caller : null);
            if (node.finished) return;
            node.finished = true;
            ts.popUnit(node);
            if (failure != null && !node.isRethrowOfReceived(failure)) {
                TraceEvent thrown = new TraceEvent(node.id, Wire.EXCEPTION_THROWN, thread);
                thrown.exception = Describe.exception(failure, true, Tracer.config.stackDepth);
                Tracer.emit(ts, thrown);
            }
            if (failure != null && node.rethrowsToId != 0) {
                JobNode caller = node.rethrowsTo;
                if (caller != null) caller.markReceived(failure);
                TraceEvent propagated = new TraceEvent(node.rethrowsToId, Wire.EXCEPTION_PROPAGATED, thread);
                propagated.otherNodeId = node.id;
                propagated.direction = Wire.CHILD_TO_PARENT;
                propagated.exception = Describe.exception(failure, false, 0);
                Tracer.emit(ts, propagated);
            }
            TraceEvent event = new TraceEvent(node.id, Wire.FINISHED, thread);
            event.finalState = failure == null ? Wire.STATE_COMPLETED : Wire.STATE_FAILED;
            Tracer.emit(ts, event);
            ended(node);
        } catch (Throwable e) {
            Tracer.reportInternalError("continuationCompleted", e);
        } finally {
            ts.exitHook();
        }
    }

    /**
     * Follows completion links to a known root. CoroutineStackFrame can expose a caller hidden by wrappers such as SafeCollector.
     * A nonnull {@code into} restricts the match to a coroutine that completes into that object.
     */
    private static JobNode joblessNode(KotlinAccess access, Object frame, Object into) throws Throwable {
        if (JOBLESS.isEmpty() || access.isGeneratorFrame(frame)) return null;
        Object current = frame;
        for (int steps = 0; current != null && steps < 10_000; steps++) {
            Object next;
            if (access.isCompiledFrame(current)) {
                next = access.completionOf(current);
                if (access.isCompiledFrame(next)) {
                    current = next;
                    continue;
                }
                if (into == null || into == next) {
                    JobNode node = JOBLESS.get(current);
                    if (node != null) return node;
                }
            }
            next = access.callerOf(current);
            if (next == current) return null;
            current = next;
        }
        return null;
    }

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
            // An outsider that cancels into a paused subtree is held here, before the cancellation happens.
            Pace.await(ts, unit != null ? unit : thread, node, null);
            if (node.finished) return;
            TraceEvent event = new TraceEvent(node.id, Wire.CANCELLATION_REQUESTED, thread.id);
            event.otherNodeId = unit != null ? unit.id : thread.id;
            event.stack = StackCapture.capture(Tracer.config.stackDepth);
            if (cause != null) event.exception = Describe.exception(cause, false, 0);
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("cancelRequested", e);
        } finally {
            ts.exitHook();
        }
    }

    public static void parentCancelled(Object child, Object parent) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (!(child instanceof Tagged c) || !(parent instanceof Tagged p)) return;
            JobNode node = nodeOf(c, ts);
            if (node.finished || node.cancelling) return; // can be the failed child that caused the parent's cancellation
            JobNode parentNode = nodeOf(p, ts);
            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            Pace.await(ts, unit != null ? unit : thread, node, null);
            if (node.finished || node.cancelling) return;
            TraceEvent event = new TraceEvent(node.id, Wire.CANCELLATION_PROPAGATED, thread.id);
            event.otherNodeId = parentNode.id;
            event.direction = Wire.PARENT_TO_CHILD;
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("parentCancelled", e);
        } finally {
            ts.exitHook();
        }
    }

    public static void cancelling(Object job, Throwable cause) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (!(job instanceof Tagged tagged)) return;
            JobNode node = nodeOf(tagged, ts);
            if (node.cancelling || node.finished) return;
            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            Pace.await(ts, unit != null ? unit : thread, node, null);
            if (node.cancelling || node.finished) return;
            node.cancelling = true;
            TraceEvent event = new TraceEvent(node.id, Wire.CANCELLING, thread.id);
            if (cause != null) event.exception = Describe.exception(cause, false, 0);
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("cancelling", e);
        } finally {
            ts.exitHook();
        }
    }

    public static void completing(Object job, Object proposedUpdate) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.get();
            if (access == null || !(job instanceof Tagged tagged)) return;
            JobNode node = nodeOf(tagged, ts);
            Throwable failure = access.failureOf(proposedUpdate);
            boolean thrown = failure != null && !(failure instanceof CancellationException)
                && !node.isRethrowOfReceived(failure); // scope failures are already recorded as propagated
            // Capture the execution unit before popUnit removes it.
            PaceNode unit = thrown ? unitOn(ts) : null;
            // The body has left this thread even if the job later completes elsewhere after its children.
            ts.popUnit(node);
            if (!thrown) return;
            long thread = threadNode(ts).id;
            Pace.await(ts, unit, node, null);
            if (node.isRethrowOfReceived(failure)) return;
            TraceEvent event = new TraceEvent(node.id, Wire.EXCEPTION_THROWN, thread);
            event.exception = Describe.exception(failure, true, Tracer.config.stackDepth);
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("completing", e);
        } finally {
            ts.exitHook();
        }
    }

    /** Runs exactly once for each job. */
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
            long thread = threadNode(ts).id;
            Throwable failure = access.failureOf(finalState);
            boolean failed = failure != null && !(failure instanceof CancellationException);
            PaceNode unit = unitOn(ts); // capture the unit before popUnit
            // A scope can finish on its last child's thread. Await the caller too before emitting propagation on it.
            Pace.await(ts, unit, node, failed && node.rethrowsToId != 0 ? node.caller : null);
            if (node.finished) return;
            node.finished = true;
            ts.popUnit(node);
            if (failed && node.deferred) {
                TraceEvent held = new TraceEvent(node.id, Wire.EXCEPTION_HANDLED, thread);
                held.handledBy = Wire.BY_DEFERRED_HELD;
                held.exception = Describe.exception(failure, false, 0);
                Tracer.emit(ts, held);
            }
            if (failed && node.rethrowsToId != 0) {
                // The exception continues as a throw from coroutineScope/withContext/runBlocking/… in the caller's code.
                JobNode caller = node.rethrowsTo;
                if (caller != null) caller.markReceived(failure);
                TraceEvent propagated = new TraceEvent(node.rethrowsToId, Wire.EXCEPTION_PROPAGATED, thread);
                propagated.otherNodeId = node.id;
                propagated.direction = Wire.CHILD_TO_PARENT;
                propagated.exception = Describe.exception(failure, false, 0);
                Tracer.emit(ts, propagated);
            }
            TraceEvent event = new TraceEvent(node.id, Wire.FINISHED, thread);
            event.finalState = failure == null ? Wire.STATE_COMPLETED : failed ? Wire.STATE_FAILED : Wire.STATE_CANCELLED;
            Tracer.emit(ts, event);
            ended(node);
        } catch (Throwable e) {
            Tracer.reportInternalError("completed", e);
        } finally {
            ts.exitHook();
        }
    }

    public static void childCancelled(Object child, Object parent, Throwable cause) {
        if (!Tracer.active || cause instanceof CancellationException) return; // child cancellation doesn't cancel its parent
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (!(child instanceof Tagged c) || !(parent instanceof Tagged p)) return;
            JobNode childNode = nodeOf(c, ts);
            if (childNode.hasPropagated(cause)) return;
            JobNode parentNode = nodeOf(p, ts);
            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            Pace.await(ts, unit != null ? unit : thread, parentNode, null); // a step of its own, about the parent
            if (!childNode.markPropagated(cause)) return;
            TraceEvent event = new TraceEvent(parentNode.id, Wire.EXCEPTION_PROPAGATED, thread.id);
            event.otherNodeId = childNode.id;
            event.direction = Wire.CHILD_TO_PARENT;
            event.exception = Describe.exception(cause, false, 0);
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("childCancelled", e);
        } finally {
            ts.exitHook();
        }
    }

    public static void childCancelledResult(boolean handled, Object child, Object parent, Throwable cause) {
        if (!Tracer.active || handled || cause instanceof CancellationException) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (!(child instanceof Tagged c) || !(parent instanceof Tagged p)) return;
            JobNode parentNode = nodeOf(p, ts);
            // An unhandled failure indicates a supervisor only if the parent remains active.
            if (parentNode.cancelling || parentNode.finished) return;
            JobNode childNode = nodeOf(c, ts);
            if (childNode.hasStopped(cause)) return;
            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            Pace.await(ts, unit != null ? unit : thread, parentNode, null);
            if (parentNode.cancelling || parentNode.finished) return;
            if (!childNode.markStopped(cause)) return;
            TraceEvent event = new TraceEvent(parentNode.id, Wire.EXCEPTION_HANDLED, thread.id);
            event.otherNodeId = childNode.id;
            event.handledBy = Wire.BY_SUPERVISOR;
            event.exception = Describe.exception(cause, false, 0);
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("childCancelledResult", e);
        } finally {
            ts.exitHook();
        }
    }

    public static void exceptionReachedHandler(Object context, Throwable exception) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            KotlinAccess access = KotlinAccess.get();
            if (access == null) return;
            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            JobNode node = nodeOfContext(context, ts);
            if (node == null) node = unit;
            PaceNode target = node != null ? node : thread;
            Pace.await(ts, unit != null ? unit : thread, target, null);
            TraceEvent event = new TraceEvent(target.id, Wire.EXCEPTION_HANDLED, thread.id);
            event.handledBy = access.hasExceptionHandler(context) ? Wire.BY_COROUTINE_EXCEPTION_HANDLER : Wire.BY_UNCAUGHT_EXCEPTION_HANDLER;
            event.exception = Describe.exception(exception, false, 0);
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("exceptionReachedHandler", e);
        } finally {
            ts.exitHook();
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
            PaceNode target = unit != null ? unit : thread;
            Pace.await(ts, target, target, null);
            TraceEvent event = new TraceEvent(target.id, Wire.EXCEPTION_HANDLED, thread.id);
            event.handledBy = Wire.BY_CATCH;
            event.exception = Describe.exception(exception, true, Tracer.config.stackDepth);
            event.stack = StackCapture.capture(Tracer.config.stackDepth);
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("exceptionCaught", e);
        } finally {
            ts.exitHook();
        }
    }

    /** {@code Thread.start}, on the starting thread. */
    public static void threadStart(Thread thread) {
        if (!Tracer.active || thread instanceof AgentThread) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            ThreadNode starter = threadNode(ts);
            JobNode unit = ts.currentUnit();
            PaceNode creatorNode = unit != null ? unit : starter;
            long creator = creatorNode.id;

            // A thread interrupted before start can already have an undefined node.
            ThreadNode node = THREADS.get(thread);
            if (node == null) {
                node = new ThreadNode(Tracer.newNodeId());
                ThreadNode raced = THREADS.putIfAbsent(thread, node);
                if (raced != null) node = raced;
            }
            if (node.defined) return; // a virtual thread passes through two instrumented start methods
            PoolNode pool = poolOf(ts, thread, creatorNode, starter);
            if (Pace.GATE != null) node.paceParent = pool != null ? pool : starter;
            // The starter can own the Thread monitor here. Releasing a hold must not require another thread's progress.
            Pace.await(ts, creatorNode, node, null);
            synchronized (node) {
                if (node.defined) return;
                node.defined = true;
            }
            StackFrameRef[] stack = StackCapture.captureForSite();
            TraceEvent.NodeDef def = threadDef(node, thread);
            def.creatorId = creator;
            if (pool != null) {
                // Worker creation sites depend on pool scheduling and don't identify project thread creation.
                def.parentId = pool.id;
                def.construct = "worker";
                def.origin = Wire.ORIGIN_LIBRARY;
            } else {
                def.parentId = starter.id;
                int site = StackCapture.siteIndex(stack);
                setSite(def, stack, site);
                if (StackCapture.startedDirectlyBySite(stack, site)) {
                    def.construct = StackCapture.construct(stack, site, "Thread.start");
                } else {
                    // Runtime-created threads can have a project call site without being project-created threads.
                    def.construct = "Thread.start";
                    def.origin = Wire.ORIGIN_LIBRARY;
                }
            }
            TraceEvent event = new TraceEvent(node.id, Wire.LAUNCHED, starter.id);
            event.node = def;
            event.stack = StackCapture.limit(stack, Tracer.config.stackDepth);
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("threadStart", e);
        } finally {
            ts.exitHook();
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
            JobNode unit = ts.currentUnit();
            Pace.await(ts, unit != null ? unit : node, node, null);
            if (node.finished) return;
            node.finished = true;
            TraceEvent event = new TraceEvent(node.id, Wire.FINISHED, node.id);
            event.finalState = node.failed ? Wire.STATE_FAILED : Wire.STATE_COMPLETED;
            Tracer.emit(ts, event);
            // A thread can't end inside a blocking call. Nonzero depth indicates unbalanced hooks, possibly from an agent hold.
            if (Tracer.DEBUG && (ts.blockDepth != 0 || ts.blockEmittedAt != 0)) {
                Tracer.reportInternalError("blocking calls of " + thread.getName(),
                    new IllegalStateException("unbalanced at the end of the thread: depth " + ts.blockDepth + ", reported at " + ts.blockEmittedAt));
            }
            // Remove the setting but retain ancestry because child threads can outlive this thread.
            Pace.nodeFinished(node);
        } catch (Throwable e) {
            Tracer.reportInternalError("threadExit", e);
        } finally {
            ts.exitHook();
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
            ThreadNode targetNode = threadNode(ts, target, thread.id);
            Pace.await(ts, unit != null ? unit : thread, targetNode, null); // before the interrupt happens
            TraceEvent event = new TraceEvent(targetNode.id, Wire.THREAD_INTERRUPTED, thread.id);
            event.otherNodeId = unit != null ? unit.id : thread.id;
            event.stack = StackCapture.capture(Tracer.config.stackDepth);
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("threadInterrupt", e);
        } finally {
            ts.exitHook();
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
            JobNode unit = ts.currentUnit();
            Pace.await(ts, unit != null ? unit : node, node, null);
            node.failed = true;
            TraceEvent thrown = new TraceEvent(node.id, Wire.EXCEPTION_THROWN, node.id);
            thrown.exception = Describe.exception(exception, true, Tracer.config.stackDepth);
            Tracer.emit(ts, thrown);
            TraceEvent handled = new TraceEvent(node.id, Wire.EXCEPTION_HANDLED, node.id);
            handled.handledBy = Wire.BY_UNCAUGHT_EXCEPTION_HANDLER;
            handled.exception = Describe.exception(exception, false, 0);
            Tracer.emit(ts, handled);
        } catch (Throwable e) {
            Tracer.reportInternalError("threadUncaught", e);
        } finally {
            ts.exitHook();
        }
    }

    private static ThreadNode threadNode(ThreadState ts) {
        ThreadNode node = ts.node;
        if (node == null) {
            node = threadNode(ts, ts.thread, 0);
            ts.node = node;
        }
        return node;
    }

    /**
     * Threads first seen after start are discovered here. Unstarted threads stay undefined until their launch hook runs.
     * Discovery is a separate step that passes the gate before the decision.
     */
    private static ThreadNode threadNode(ThreadState ts, Thread thread, long reportingThread) {
        ThreadNode node = THREADS.get(thread);
        if (node == null) {
            node = new ThreadNode(Tracer.newNodeId());
            ThreadNode raced = THREADS.putIfAbsent(thread, node);
            if (raced != null) node = raced;
        }
        if (node.defined || thread.getState() == Thread.State.NEW) return node;
        PaceNode unit = ts.currentUnit();
        if (unit == null) unit = thread == ts.thread ? node : threadNode(ts);
        Pace.await(ts, unit, node, null);
        synchronized (node) {
            if (node.defined) return node;
            node.defined = true;
        }
        TraceEvent.NodeDef def = threadDef(node, thread);
        def.origin = Wire.ORIGIN_LIBRARY;
        TraceEvent event = new TraceEvent(node.id, Wire.DISCOVERED, reportingThread != 0 ? reportingThread : node.id);
        event.node = def;
        Tracer.emit(ts, event);
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

    /**
     * The first worker defines its pool in a separate step of the thread that expanded the pool.
     */
    private static PoolNode poolOf(ThreadState ts, Thread thread, PaceNode creatorNode, ThreadNode starter) {
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
            return null;
        }
        if (pool == null) return null;
        PoolNode known = POOLS.get(pool);
        if (known != null) return known;
        PoolNode node = new PoolNode(Tracer.newNodeId());
        known = POOLS.putIfAbsent(pool, node);
        if (known != null) return known;
        // A concurrent worker can reference this pool before its definition. Readers support references before definitions.
        Pace.await(ts, creatorNode, node, null);
        TraceEvent.NodeDef def = new TraceEvent.NodeDef();
        def.id = node.id;
        def.kind = Wire.KIND_POOL;
        def.name = name;
        def.implClass = pool.getClass().getName();
        def.creatorId = creatorNode.id;
        def.origin = Wire.ORIGIN_LIBRARY;
        TraceEvent event = new TraceEvent(node.id, Wire.LAUNCHED, starter.id);
        event.node = def;
        Tracer.emit(ts, event);
        return node;
    }

    /**
     * Opens the gate before the JVM starts any shutdown hook. Emits no event and needs no await.
     */
    public static void shutdownBegins() {
        try {
            Pace gate = Pace.GATE;
            if (gate != null) gate.shutdown();
        } catch (Throwable e) {
            Tracer.reportInternalError("shutdownBegins", e);
        }
    }

    /**
     * Reports only the outermost blocking call of each unit. Every enter must have a matching exit, including unreported calls.
     * Agent holds enter both hooks with {@code inHook} set. Neither hook can change the depth for those holds.
     * @param reason a {@code Wire.BLOCK_*} constant
     */
    public static void blockEnter(int reason) {
        if (!Tracer.active) return;
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            ts.blockDepth++;
            if (reason == NOT_REPORTED || ts.blockEmittedAt != 0) return;
            if (reason == Wire.BLOCK_PARK && isRuntimeHousekeeping(StackCapture.capture(8))) return;
            ThreadNode thread = threadNode(ts);
            JobNode unit = ts.currentUnit();
            // Await before blocking. Object.wait still owns its monitor here, and the JVMTI probe hasn't acquired the contended monitor.
            Pace.await(ts, unit != null ? unit : thread, thread, null);
            ts.blockEmittedAt = ts.blockDepth;
            ts.blockOtherNodeId = unit != null ? unit.id : 0;
            TraceEvent event = new TraceEvent(thread.id, Wire.THREAD_BLOCKED, thread.id);
            event.otherNodeId = ts.blockOtherNodeId;
            event.blockReason = reason;
            event.stack = StackCapture.capture(Tracer.config.stackDepth);
            Tracer.emit(ts, event);
        } catch (Throwable e) {
            Tracer.reportInternalError("blockEnter", e);
        } finally {
            ts.exitHook();
        }
    }

    /** Only standard input reads count as blocking I/O events. */
    public static void blockEnterIfStdin(Object stream) {
        boolean stdin = false;
        try {
            stdin = stream instanceof FileInputStream in && in.getFD() == FileDescriptor.in;
        } catch (Throwable ignored) {
        }
        // File reads still enter without a report because the exit hook always decrements the depth.
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
                ThreadNode thread = threadNode(ts);
                JobNode unit = ts.currentUnit();
                // Await after wake-up, with any reacquired locks. The hold must preserve the interrupt flag or pending exception.
                Pace.await(ts, unit != null ? unit : thread, thread, null);
                ts.blockEmittedAt = 0;
                TraceEvent event = new TraceEvent(thread.id, Wire.THREAD_UNBLOCKED, thread.id);
                event.otherNodeId = ts.blockOtherNodeId;
                Tracer.emit(ts, event);
            }
            ts.blockDepth--;
        } catch (Throwable e) {
            Tracer.reportInternalError("blockExit", e);
        } finally {
            ts.exitHook();
        }
    }

    /**
     * Dispatcher workers and event loops park while idle, so those parks aren't reported as blocking.
     * runBlocking has a separate blocking event.
     */
    private static boolean isRuntimeHousekeeping(StackFrameRef[] top) {
        for (StackFrameRef frame : top) {
            // What counts is the method that parks, not the inline function whose code the call is.
            if (frame.inlined || frame.className.startsWith("java.util.concurrent.locks.")) continue;
            return frame.className.startsWith("kotlinx.coroutines.");
        }
        return false;
    }
}
