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
 *
 * The work has an order, because a hook is where the program can be held ({@link Pace}, DESIGN §3.1):
 * <ol>
 * <li>find everybody the step is about. Finding a node may report it ({@link #discover}, {@link #threadNode},
 *     {@link #poolOf}), which is a step of its own and passes the gate itself, so all of it comes first;</li>
 * <li>{@code Pace.await}: the one place where the thread may be held;</li>
 * <li>only then read what is reported, decide, lock, emit.</li>
 * </ol>
 * What a hook looks at before its {@code await} are its arguments, state that only this thread changes, and peeks
 * that save the thread from being held for a step with nothing to report; whatever a peek found is decided again
 * after the hold. {@link Tracer#emit} refuses, loudly, an event of a hook call that has not passed the gate.
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

    // ------------------------------------------------------------------ coroutines: structure

    /** End of the {@code AbstractCoroutine} constructor: a coroutine, scope or context change came to be. */
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

    /** End of the {@code JobImpl} constructor: {@code Job()}, {@code SupervisorJob()}, the job of a {@code CoroutineScope()}. */
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
        // The node may be there already: a job attaches to its parent inside its constructor, and a parent that is
        // cancelled or done cancels it right then, before this hook at the end of the constructor. Whoever needed
        // the node first made it, without a definition; the definition is what this hook adds. The tag is published
        // before anything slow happens, for the same reason: the job is already reachable through its parent.
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
            // Its place in the structure is known before the gate: a child of a paused subtree is held at its own "launched".
            node.paceParent = parentNode != null ? parentNode : calledInPlace ? creatorNode : null;
            if (calledInPlace) node.caller = creatorNode;
        }
        Pace.await(ts, creatorNode, node, null);

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
        def.parentId = parentNode != null ? parentNode.id : calledInPlace ? creator : 0;
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
        if (calledInPlace) {
            // They do not report a failure to their parent job; they throw it at whoever called them.
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

    /** CONTEXT_CHANGED for everything but the dispatcher, DISPATCHER_CHANGED for the dispatcher; the Job is not news. */
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

    /** A step of its own, whichever hook came across the job: it passes the gate before the lock its decision is made under. */
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

    /** The node has ended: a setting of the gate on it is dropped, and it lets go of whom it hung under. */
    private static void ended(JobNode node) {
        if (Pace.GATE == null) return;
        Pace.nodeFinished(node);
        // A job ends after its children and a callee before its caller: nobody looks up through this node any more.
        node.paceParent = null;
        node.caller = null;
    }

    // ------------------------------------------------------------------ coroutines: suspend and resume

    /** {@code probeCoroutineResumed}: a continuation frame of some coroutine is about to run on this thread. */
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
                // The probe fires for every frame as a suspend call chain unwinds: nothing to report, nothing to be held for.
                ts.pushUnit(node);
                return;
            }
            long thread = threadNode(ts).id;
            // The coroutine's own step, in its own flow. It is not on this thread's books yet, and if the thread stood
            // in for it, the ten children of a single-threaded runBlocking would be spaced against each other.
            Pace.await(ts, node, node, null);
            // A peek without the lock, to do the expensive part outside of it; the decision is made under the lock.
            StackFrameRef[] suspendedAt = node.run == JobNode.RUNNING && node.runThread != tid ? suspensionStack(frame) : null;
            synchronized (node) {
                if (node.run != JobNode.RUNNING || node.runThread != tid) {
                    if (node.run == JobNode.RUNNING) {
                        // Running on another thread by our books: it suspended there and was resumed here before
                        // that thread got to say so. Say it for that thread, and ignore its report when it comes.
                        // Where it was suspended is no secret either: it is the frame that is being resumed.
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

    /** {@code probeCoroutineSuspended}: the coroutine that owns {@code frame} is leaving this thread. */
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
                // Like the resume, the coroutine's own step. Whoever resumes it elsewhere meanwhile waits for the same
                // node, so the frame still says where it was suspended when this thread gets to read it.
                Pace.await(ts, node, node, null);
                // Reading debug metadata runs library code and may load classes: not something to do holding a lock.
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

    // ------------------------------------------------------------------ coroutines without a Job

    /**
     * Coroutines of the bare standard library ({@code suspend fun main}, {@code startCoroutine}, {@code createCoroutine}),
     * by their root frame: the continuation the coroutine was created as, which every frame above it leads to.
     * What the coroutine completes into does not identify it: nothing keeps a program from starting a hundred
     * coroutines with one and the same completion object.
     */
    private static final WeakIdentityMap<Object, JobNode> JOBLESS = new WeakIdentityMap<>();

    /**
     * End of {@code createCoroutineUnintercepted}, which everything that creates a coroutine goes through. Most of what
     * comes by belongs to a Job and is known already; what is left is a node of its own, generators excepted:
     * for {@code sequence {}} to suspend at every {@code yield} is how it returns a value, not something that happens to it.
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
            // Before the gate, for once: what the construct is decides where the node hangs, which the gate has to
            // know. A stack does not change while its thread is held.
            StackFrameRef[] stack = StackCapture.captureForSite();
            int site = StackCapture.siteIndex(stack);
            String construct = StackCapture.construct(stack, site, "startCoroutine");
            // suspend fun main is to its thread what runBlocking is: the thread waits for it and gets its failure.
            // Anything else was started and left to itself, which is what a root is.
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
            // What stands where a kotlinx.coroutines coroutine has its Job: the continuation it completes into.
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
     * In {@code BaseContinuationImpl.resumeWith}, where the last frame of a coroutine hands the result to what the
     * coroutine was started with. {@code frame} is where that run of the coroutine began, any frame of it.
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
            // There is no Job, so there is no cancellation either: a CancellationException is an exception like any other.
            Throwable failure = access.failureOfResult(result);
            PaceNode unit = unitOn(ts); // before the coroutine is taken off this thread's books: it is its own last step
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
     * Down the chain of frames to the root frame of a jobless coroutine that is known. Compiled frames lead on with
     * their completion. A frame that completes into something else is a root frame, ours or not; if not, there may
     * still be a way on ({@code SafeCollector} of a flow is such a frame: it keeps its real caller to itself and says
     * so as a {@code CoroutineStackFrame}). With {@code into}, only a coroutine that completes into it will do.
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
            Throwable failure = access.failureOf(proposedUpdate);
            boolean thrown = failure != null && !(failure instanceof CancellationException)
                && !node.isRethrowOfReceived(failure); // came out of a scope it called; already recorded as propagated
            // Whose step it is, is asked while the body that is over is still on this thread's books.
            PaceNode unit = thrown ? unitOn(ts) : null;
            // Its code is done with this thread even if the job now waits for children and completes elsewhere.
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
            long thread = threadNode(ts).id;
            Throwable failure = access.failureOf(finalState);
            boolean failed = failure != null && !(failure instanceof CancellationException);
            PaceNode unit = unitOn(ts); // before the node is taken off this thread's books
            // "Propagated to the caller" lands on the caller. That is the scope's own flow when the scope ends where it
            // ran, but a scope may end on the thread of its last child, and then it is another sequence to keep a distance in.
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

    /** Start of {@code ChildHandleNode.childCancelled}: a failing child tells its parent. */
    public static void childCancelled(Object child, Object parent, Throwable cause) {
        if (!Tracer.active || cause instanceof CancellationException) return; // a cancelled child does not concern the parent
        ThreadState ts = ThreadState.current();
        if (ts.inHook) return;
        ts.inHook = true;
        try {
            if (!(child instanceof Tagged c) || !(parent instanceof Tagged p)) return;
            JobNode childNode = nodeOf(c, ts);
            if (childNode.hasPropagated(cause)) return; // the library reports to the parent twice: when cancelling and when final
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
            PaceNode creatorNode = unit != null ? unit : starter;
            long creator = creatorNode.id;

            // The node may exist, undefined, if the thread was heard of before it was started (see threadNode).
            ThreadNode node = THREADS.get(thread);
            if (node == null) {
                node = new ThreadNode(Tracer.newNodeId());
                ThreadNode raced = THREADS.putIfAbsent(thread, node);
                if (raced != null) node = raced;
            }
            if (node.defined) return; // a virtual thread passes through two instrumented start methods
            PoolNode pool = poolOf(ts, thread, creatorNode, starter);
            if (Pace.GATE != null) node.paceParent = pool != null ? pool : starter;
            // Inside Thread.start: the starter may own the monitor of the Thread. Whoever wants it waits as for any
            // slow thread; the release of this one waits for nobody.
            Pace.await(ts, creatorNode, node, null);
            synchronized (node) {
                if (node.defined) return;
                node.defined = true;
            }
            StackFrameRef[] stack = StackCapture.captureForSite();
            TraceEvent.NodeDef def = threadDef(node, thread);
            def.creatorId = creator;
            if (pool != null) {
                // Which thread happened to make the pool grow is an accident, and so is the line it was at.
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
                    // Started by a runtime as a side effect of whatever the site called (a timer thread behind delay,
                    // an executor growing): the site is worth showing, but this is not a thread the project made.
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
            // A thread cannot end inside a blocking call. If the books say it does, an enter went without its exit: the
            // kind of damage a hold could do (it sleeps inside hooks, and sleep is hooked) and must never do.
            if (Tracer.DEBUG && (ts.blockDepth != 0 || ts.blockEmittedAt != 0)) {
                Tracer.reportInternalError("blocking calls of " + thread.getName(),
                    new IllegalStateException("unbalanced at the end of the thread: depth " + ts.blockDepth + ", reported at " + ts.blockEmittedAt));
            }
            // Its setting goes; its place in the structure stays, for the threads it started live on below it.
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
     * The node of a thread, DISCOVERED here if its start was not seen (main, JVM threads). A thread that has not been
     * started yet — interrupting one is legal — is left undefined: its start will be seen, and LAUNCHED defines it.
     * Discovering is a step of its own and passes the gate before its decision.
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
     * The pool a worker thread belongs to, {@code null} if it is not a pool worker this agent knows how to recognise.
     * The first worker of a pool brings the pool along: a step of its own, of the thread that made the pool grow.
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
        // Who reports the pool is settled; a worker that another thread starts meanwhile refers to a node that is
        // defined a moment later, which readers are used to.
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
     * Start of {@code ApplicationShutdownHooks.runHooks}: the JVM is on its way down and is about to start the shutdown
     * hooks, ours among them. The gate opens now, not when our hook gets to run. No event, so no {@code await}.
     */
    public static void shutdownBegins() {
        try {
            Pace gate = Pace.GATE;
            if (gate != null) gate.shutdown();
        } catch (Throwable e) {
            Tracer.reportInternalError("shutdownBegins", e);
        }
    }

    // ------------------------------------------------------------------ threads: blocking

    /**
     * Start of an instrumented blocking method; {@code reason} is a {@code Wire.BLOCK_*} constant. Blocking methods
     * call each other ({@code join} waits with {@code wait}, {@code sleep} on a virtual thread parks), so only the
     * outermost one of a unit is reported. The depth is kept even when nothing is reported: every enter has an exit.
     *
     * The gate's own sleep comes by here too, and by {@link #blockExit}, with {@code inHook} set: both return before
     * they touch the depth, so the hold is not a blocking call and leaves the books as they were.
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
            // Held before it blocks: inside park, sleep, wait (owning the monitor it is about to wait on) or, called
            // from the JVMTI probe, in front of a contended monitor.
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
                ThreadNode thread = threadNode(ts);
                JobNode unit = ts.currentUnit();
                // Held after it woke up, with whatever it woke up owning. An interrupt that ended the blocking call is
                // in the flag or on its way as an exception; either way it is there when the thread goes on.
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
     * A park made by kotlinx.coroutines itself is a dispatcher worker or an event loop waiting for work. That is the
     * thread being idle, not something blocking it. ({@code runBlocking} is reported separately, as such.)
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
