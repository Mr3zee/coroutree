package kotlinx.coroutree.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The gate: the one place where a hook may wait (DESIGN §3.1). It slows the program down, stops it and lets it go
 * on step by step, by holding application threads at their next event — the whole program or a subtree.
 *
 * What stopping somebody else's program demands, and how it is met here:
 * <ul>
 * <li><b>Nothing can strand a held thread.</b> There is no queue, no owner and no lock on its path: it reads volatile
 *     settings, compares the clock with the last step of its sequence, and sleeps for at most a tick before it looks
 *     again. It waits for time and for settings, never for another thread's progress.</li>
 * <li><b>The hold is not recorded.</b> It happens with {@code inHook} set, so the hooks of {@code Thread.sleep} and of
 *     the interrupt that restores the flag see nothing, and leave {@code blockDepth} alone, enter and exit alike.</li>
 * <li><b>The hold does not disturb.</b> The wait is {@code Thread.sleep}, not {@code LockSupport.parkNanos}: a park
 *     consumes the thread's park permit, and a thread held inside the hook of its own {@code park()} would lose an
 *     {@code unpark} that came before and block forever. The interrupt flag is cleared for the wait and set again.
 *     Nothing is thrown.</li>
 * <li><b>It fails open:</b> tracing off (writer failure, JVM shutdown) and every thread goes at its next tick.</li>
 * </ul>
 * A pace is a minimum interval between two steps of one <i>sequence</i>: steps made by the same flow, or happening to
 * the same node. Parallel sequences are paced independently; there is no global order of turns.
 *
 * Switched off ({@code pace=false}; or no live socket and no configured pace) there is no gate: {@link #GATE} is null.
 */
final class Pace {
    static final long NEVER = Long.MIN_VALUE;

    /** A held thread looks again at least this often: that is how a change of pace, a resume, a permit, a fail-open reach it. */
    static final long TICK_NANOS = 10_000_000;

    /** Longer intervals are cut to this. A pace of a day is a pause; and {@code last + interval} must never overflow. */
    static final long MAX_INTERVAL_NANOS = 86_400_000_000_000L;

    private static final long LOOK_AGAIN = -1;

    /** {@code null}: no gate in this JVM. Initialised from the configuration, which {@link Tracer#start} sets first. */
    static final Pace GATE = create(Tracer.config);

    private static Pace create(AgentConfig config) {
        if (config == null || !config.hasGate()) return null;
        return new Pace(config.paceIntervalNanos, config.pacePaused && config.live, true);
    }

    /** The global setting: what governs a node that has no setting on its way to the root. */
    final PaceScope root = new PaceScope(null);

    /** Nobody is paused or paced anywhere: what {@link #await} reads, and all it reads, in a program that runs free. */
    private volatile boolean open;

    private final long configuredIntervalNanos;

    /** False for the gate that {@link #warmUp} plays with: it leaves nothing in the trace and runs whether tracing is on or not. */
    private final boolean recorded;

    // Commands, and nothing else, run under this lock: control readers, the shutdown hook, and a hook whose node ends
    // while it carries a setting. A held thread never takes it and nothing waits under it.
    private final Object commandLock = new Object();
    private final ArrayList<PaceNode> scopedNodes = new ArrayList<>();
    private int controllers;
    private boolean shutDown;

    private final ConcurrentHashMap<Long, NodeRef> nodes = new ConcurrentHashMap<>();
    private final ReferenceQueue<PaceNode> collected = new ReferenceQueue<>();

    Pace(long intervalNanos, boolean paused, boolean recorded) {
        this.recorded = recorded;
        configuredIntervalNanos = Math.min(Math.max(intervalNanos, 0), MAX_INTERVAL_NANOS);
        root.intervalNanos = configuredIntervalNanos;
        root.paused = paused;
        updateOpen();
    }

    // ------------------------------------------------------------------ the hook's side

    /**
     * Called by a hook once it knows whom the step is about, before it reads, decides or locks anything.
     * {@code unit} is the execution unit the step happens in, {@code node} the node it happens to, {@code other} a
     * second node the same hook call emits on, or {@code null}. Returns normally, always.
     */
    static void await(ThreadState ts, PaceNode unit, PaceNode node, PaceNode other) {
        Pace gate = GATE;
        if (gate == null) return;
        gate.pass(ts, unit, node, other);
    }

    void pass(ThreadState ts, PaceNode unit, PaceNode node, PaceNode other) {
        if (ts.awaited) settle(ts); // the step before this one in the same hook call: finding a node was a step of its own
        ts.awaited = true;
        if (open || ts.neverHeld) return;
        hold(ts, unit, node, other);
    }

    /**
     * End of a step: the hook returns, or comes to its next {@code await}. A step that took its place in the
     * sequence and then had nothing to report (a probe for a frame of a coroutine that turned out to be running
     * already, a report that another thread made in the meantime) gives it back: it costs the sequence nothing.
     */
    static void settle(ThreadState ts) {
        if (ts.claimedSlots > 0 || ts.claimedPermits > 0) {
            if (!ts.stepEmitted) {
                for (int i = 0; i < ts.claimedSlots; i++) ts.claimSlot[i].lastStep.compareAndSet(ts.claimTime, ts.claimPrevious[i]);
                for (int i = 0; i < ts.claimedPermits; i++) ts.claimPermit[i].incrementAndGet();
            }
            for (int i = 0; i < ts.claimedSlots; i++) ts.claimSlot[i] = null;
            for (int i = 0; i < ts.claimedPermits; i++) ts.claimPermit[i] = null;
            ts.claimedSlots = 0;
            ts.claimedPermits = 0;
        }
        ts.awaited = false;
        ts.stepEmitted = false;
        ts.releaseNanos = NEVER;
    }

    private void hold(ThreadState ts, PaceNode unit, PaceNode node, PaceNode other) {
        long started = NEVER;
        boolean interrupted = false;
        try {
            if (unit == null) unit = node;
            PaceNode flow = unit.flow();
            while ((Tracer.active || !recorded) && !open) {
                long now = Tracer.now();
                long wait = tryPass(ts, now, unit, flow, node, other);
                if (wait == 0) break;
                if (wait == LOOK_AGAIN) continue; // lost a race for a slot: somebody else moved, which is progress
                if (started == NEVER) started = now;
                // A sleep refuses an interrupted thread, and the hold would spin. The flag is the program's: it is put
                // back before the program runs again. Asked by another thread meanwhile, isInterrupted() says false.
                if (Thread.interrupted()) interrupted = true;
                try {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } catch (Throwable e) {
            Tracer.reportInternalError("pace", e);
        } finally {
            if (started != NEVER) ts.heldNanos += Tracer.now() - started;
            if (interrupted) {
                try {
                    Thread.currentThread().interrupt();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 0: the step may go and has taken its place. {@link #LOOK_AGAIN}: at once. Otherwise: how long to sleep first. */
    private long tryPass(ThreadState ts, long now, PaceNode unit, PaceNode flow, PaceNode node, PaceNode other) {
        // The step is governed by the stricter of the settings of where it happens and whom it happens to: nothing
        // happens from a paused subtree, and nothing happens to it.
        PaceScope a = scopeOf(unit);
        PaceScope b = scopeOf(node);
        PaceScope c = other == null ? b : scopeOf(other);
        if (b == a) b = null;
        if (c == a || c == b) c = null;
        // In a fixed order, so that two steps never take a permit each and wait for the other's.
        if (b != null && b.order() < a.order()) { PaceScope t = a; a = b; b = t; }
        if (c != null && c.order() < a.order()) { PaceScope t = a; a = c; c = t; }
        if (b != null && c != null && c.order() < b.order()) { PaceScope t = b; b = c; c = t; }
        if (b == null) { b = c; c = null; }

        boolean pausedA = a.paused, pausedB = b != null && b.paused, pausedC = c != null && c.paused;
        long interval = pausedA ? 0 : a.intervalNanos;
        if (b != null && !pausedB) interval = Math.max(interval, b.intervalNanos);
        if (c != null && !pausedC) interval = Math.max(interval, c.intervalNanos);

        if (node == flow) node = null;
        if (other == flow || other == node) other = null;
        long lastOfFlow = flow.lastStep.get();
        long lastOfNode = node == null ? NEVER : node.lastStep.get();
        long lastOfOther = other == null ? NEVER : other.lastStep.get();
        long last = Math.max(lastOfFlow, Math.max(lastOfNode, lastOfOther));
        if (last != NEVER) {
            // The interval is a minimum, not a surcharge: time the program spent blocked or suspended counts.
            if (interval > 0 && last + interval > now) return Math.min(last + interval - now, TICK_NANOS);
            if (last > now) return LOOK_AGAIN; // claimed by a thread that read the clock after this one did
        }

        AtomicLong permitA = null, permitB = null, permitC = null;
        if (pausedA && (permitA = take(a)) == null) return TICK_NANOS;
        if (pausedB && (permitB = take(b)) == null) {
            giveBack(permitA);
            return TICK_NANOS;
        }
        if (pausedC && (permitC = take(c)) == null) {
            giveBack(permitA);
            giveBack(permitB);
            return TICK_NANOS;
        }

        // One time for every slot of the step, and it becomes the time of the event: two steps that share a slot are
        // then an interval apart exactly, whatever the hooks do between here and the emit.
        boolean claimed = flow.lastStep.compareAndSet(lastOfFlow, now);
        if (claimed && node != null && !node.lastStep.compareAndSet(lastOfNode, now)) {
            flow.lastStep.compareAndSet(now, lastOfFlow);
            claimed = false;
        }
        if (claimed && other != null && !other.lastStep.compareAndSet(lastOfOther, now)) {
            flow.lastStep.compareAndSet(now, lastOfFlow);
            if (node != null) node.lastStep.compareAndSet(now, lastOfNode);
            claimed = false;
        }
        if (!claimed) {
            giveBack(permitA);
            giveBack(permitB);
            giveBack(permitC);
            return LOOK_AGAIN;
        }

        ts.claimTime = now;
        ts.releaseNanos = now;
        ts.claim(flow, lastOfFlow);
        if (node != null) ts.claim(node, lastOfNode);
        if (other != null) ts.claim(other, lastOfOther);
        if (permitA != null) ts.claim(permitA);
        if (permitB != null) ts.claim(permitB);
        if (permitC != null) ts.claim(permitC);
        return 0;
    }

    /** The counter a permit was taken from, to hand it back to; {@code null} if there is none to take. */
    private static AtomicLong take(PaceScope scope) {
        AtomicLong permits = scope.permits;
        while (true) {
            long available = permits.get();
            if (available <= 0) return null;
            if (permits.compareAndSet(available, available - 1)) return permits;
        }
    }

    private static void giveBack(AtomicLong permits) {
        if (permits != null) permits.incrementAndGet();
    }

    /** The innermost setting on the way from {@code node} to the root; the global one is the root's. */
    PaceScope scopeOf(PaceNode node) {
        for (PaceNode n = node; n != null; n = n.paceParent) {
            PaceScope scope = n.scope;
            if (scope != null) return scope;
        }
        return root;
    }

    /** After the node's FINISHED is in the trace (and {@code isFinished()} says so): a setting on it is dropped. */
    static void nodeFinished(PaceNode node) {
        Pace gate = GATE;
        if (gate == null || node.scope == null) return;
        synchronized (gate.commandLock) {
            gate.drop(node, Wire.PACE_NODE_FINISHED);
            gate.updateOpen();
        }
    }

    // ------------------------------------------------------------------ nodes by id

    /** By id, weakly: a command names a node the way the trace does. */
    void register(PaceNode node) {
        for (Object gone = collected.poll(); gone != null; gone = collected.poll()) {
            NodeRef ref = (NodeRef) gone;
            nodes.remove(ref.id, ref);
        }
        nodes.put(node.id, new NodeRef(node, collected));
    }

    PaceNode node(long id) {
        NodeRef ref = nodes.get(id);
        return ref == null ? null : ref.get();
    }

    private static final class NodeRef extends WeakReference<PaceNode> {
        final Long id;

        NodeRef(PaceNode node, ReferenceQueue<PaceNode> queue) {
            super(node, queue);
            id = node.id;
        }
    }

    // ------------------------------------------------------------------ the controller's side

    /**
     * One line of a live client: {@code pace <intervalNanos> [node]} (0: no limit), {@code pause [node]},
     * {@code resume [node]}, {@code step <n> [node]}, {@code inherit <node>}. There is no reply: what it did comes
     * back as a PaceDef in the stream, the same for every client. Returns whether the line was a command at all,
     * which is what makes its sender a controller; a command that names a node nobody knows is one, and does nothing.
     */
    boolean command(String line) {
        String[] words = words(line);
        if (words.length == 0) return false;
        String verb = words[0];
        int arguments = words.length - 1;
        long number = 0;
        boolean takesNumber = verb.equals("pace") || verb.equals("step");
        if (takesNumber) {
            if (arguments < 1) return false;
            number = number(words[1]);
            if (number < 0 || (number == 0 && verb.equals("step"))) return false;
        } else if (!verb.equals("pause") && !verb.equals("resume") && !verb.equals("inherit")) {
            return false;
        }
        int nodeAt = takesNumber ? 2 : 1;
        if (words.length > nodeAt + 1) return false;
        long nodeId = 0;
        if (words.length > nodeAt) {
            nodeId = number(words[nodeAt]);
            if (nodeId < 0) return false;
        }
        if (verb.equals("inherit") && nodeId == 0) return false; // the global setting has nobody to inherit from

        PaceNode target = null;
        if (nodeId != 0) {
            target = node(nodeId);
            if (target == null || target.isFinished()) return true;
        }
        synchronized (commandLock) {
            if (shutDown) return true;
            apply(verb, number, target);
            updateOpen();
        }
        return true;
    }

    private void apply(String verb, long number, PaceNode target) {
        if (verb.equals("inherit")) {
            drop(target, Wire.PACE_CONTROLLER);
            return;
        }
        PaceScope scope = target == null ? root : target.scope;
        boolean created = scope == null;
        if (created) {
            // A setting of its own starts as what governs the node now, and goes its own way from here.
            PaceScope governing = scopeOf(target);
            scope = new PaceScope(target);
            scope.intervalNanos = governing.intervalNanos;
            scope.paused = governing.paused;
        }
        int steps = 0;
        if (verb.equals("pace")) {
            scope.intervalNanos = Math.min(number, MAX_INTERVAL_NANOS);
        } else if (verb.equals("pause")) {
            scope.permits = new AtomicLong();
            scope.paused = true;
        } else if (verb.equals("resume")) {
            scope.paused = false;
            scope.permits = new AtomicLong();
        } else { // step
            steps = (int) Math.min(number, Integer.MAX_VALUE);
            if (scope.paused) {
                scope.permits.addAndGet(steps); // five quick presses are five steps
            } else {
                // Asked of a program that runs: stop it after that many more.
                scope.permits = new AtomicLong(steps);
                scope.paused = true;
            }
        }
        if (created) {
            target.scope = scope;
            scopedNodes.add(target);
        }
        record(definition(scope, steps, Wire.PACE_CONTROLLER, false));
        // The node may have ended while this was going on. Its hook sets "finished" and then looks for a setting, this
        // sets the setting and then looks at "finished": one of the two sees the other, and dropping twice is dropping once.
        if (created && target.isFinished()) drop(target, Wire.PACE_NODE_FINISHED);
    }

    private void drop(PaceNode node, int reason) {
        PaceScope scope = node.scope;
        if (scope == null) return;
        node.scope = null;
        scopedNodes.remove(node);
        // Whoever waits for a permit of this scope finds, a tick later, that another setting governs it now.
        record(definition(scope, 0, reason, true));
    }

    void controllerJoined() {
        synchronized (commandLock) {
            controllers++;
        }
    }

    /**
     * A GUI that went away, crashed or not, must not leave the program stopped: with the last controller gone
     * everything is as configured again, unpaused, and no subtree has a setting.
     */
    void controllerLeft() {
        synchronized (commandLock) {
            if (--controllers > 0 || shutDown || (recorded && !Tracer.active)) return;
            controllers = 0;
            revert(configuredIntervalNanos, Wire.PACE_FAIL_OPEN);
        }
    }

    /**
     * The JVM is going down: every held thread goes, and the gate stays open whatever anybody still sends. Said in the
     * trace, which is still open. Called when the shutdown sequence begins, before it starts the first shutdown hook
     * ({@link Hooks#shutdownBegins}): a thread that calls System.exit while the program is paused would otherwise be
     * held at the Thread.start of somebody's shutdown hook, with the hook that turns tracing off still unstarted.
     */
    void shutdown() {
        synchronized (commandLock) {
            if (shutDown) return;
            shutDown = true;
            revert(0, Wire.PACE_SHUTDOWN);
        }
    }

    /**
     * The live socket was asked for and could not be had: nobody will ever send a command, so a program that was to
     * start paused runs. Returns whether it was.
     */
    boolean liveUnavailable() {
        synchronized (commandLock) {
            if (!root.paused) return false;
            revert(configuredIntervalNanos, Wire.PACE_FAIL_OPEN);
            return true;
        }
    }

    private void revert(long intervalNanos, int reason) {
        boolean changed = !scopedNodes.isEmpty() || root.paused || root.intervalNanos != intervalNanos;
        while (!scopedNodes.isEmpty()) drop(scopedNodes.get(scopedNodes.size() - 1), reason);
        root.intervalNanos = intervalNanos;
        root.paused = false;
        root.permits = new AtomicLong();
        updateOpen();
        if (changed) record(definition(root, 0, reason, false));
    }

    /** The setting the run starts with, for the trace. */
    void announce() {
        synchronized (commandLock) {
            record(definition(root, 0, Wire.PACE_CONFIG, false));
        }
    }

    boolean isOpen() {
        return open;
    }

    private void updateOpen() {
        open = !root.paused && root.intervalNanos == 0 && scopedNodes.isEmpty();
    }

    private void record(TraceEvent.PaceDef def) {
        if (recorded) Tracer.pace(def);
    }

    private static TraceEvent.PaceDef definition(PaceScope scope, int steps, int reason, boolean dropped) {
        TraceEvent.PaceDef def = new TraceEvent.PaceDef();
        def.scopeNodeId = scope.node == null ? 0 : scope.node.id;
        def.intervalNanos = dropped ? 0 : scope.intervalNanos;
        def.paused = !dropped && scope.paused;
        def.steps = steps;
        def.reason = reason;
        def.dropped = dropped;
        return def;
    }

    // A control reader must not be at the mercy of a held thread either: a regular expression, a class loaded for the
    // first time while its initializer is parked at the gate, and "resume" would never arrive. Hence by hand, and
    // warmed up at start-up.

    private static String[] words(String line) {
        ArrayList<String> words = new ArrayList<>(4);
        int length = line.length();
        int start = -1;
        for (int i = 0; i <= length; i++) {
            boolean blank = i == length || line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\r';
            if (blank) {
                if (start >= 0) words.add(line.substring(start, i));
                start = -1;
            } else if (start < 0) {
                start = i;
            }
        }
        return words.toArray(new String[0]);
    }

    /** A non-negative decimal number, -1 for anything else. Too large is as large as it gets. */
    private static long number(String word) {
        if (word.isEmpty() || word.length() > 30) return -1;
        long value = 0;
        for (int i = 0; i < word.length(); i++) {
            int digit = word.charAt(i) - '0';
            if (digit < 0 || digit > 9) return -1;
            value = value > (Long.MAX_VALUE - digit) / 10 ? Long.MAX_VALUE : value * 10 + digit;
        }
        return value;
    }

    /**
     * Everything a held thread and a control reader will run, run once while nobody is held and nothing can be in
     * the way: on a gate of its own, so that it leaves no mark on the real one or in the trace.
     */
    static void warmUp() {
        Pace gate = GATE;
        if (gate == null) return;
        ThreadState ts = ThreadState.current();
        boolean inHook = ts.inHook;
        ts.inHook = true;
        try {
            Pace scratch = new Pace(0, false, false);
            WarmUpNode unit = new WarmUpNode(), node = new WarmUpNode(), other = new WarmUpNode();
            node.paceParent = unit;
            scratch.nodes.put(node.id, new NodeRef(node, scratch.collected));
            String id = " " + node.id;
            String[] lines = {"pace 1", "pause", "step 2", "resume", "pace 1" + id, "pause" + id, "step 1" + id, "resume" + id, "inherit" + id, "bogus", ""};
            for (String line : lines) scratch.command(line);
            scratch.command("pace 1000000");
            scratch.pass(ts, unit, node, other); // claims three slots
            ts.stepEmitted = true;               // and keeps them,
            scratch.pass(ts, unit, node, other); // so that this one sleeps out its millisecond
            settle(ts);                          // and gives its own back
            scratch.command("step 1");
            scratch.pass(ts, unit, node, null);  // on a permit
            settle(ts);
            scratch.controllerJoined();
            scratch.controllerLeft();
            scratch.shutdown();
        } catch (Throwable e) {
            Tracer.reportInternalError("pace warm-up", e);
        } finally {
            settle(ts);
            ts.heldNanos = 0;
            ts.inHook = inHook;
            gate.nodes.remove(WarmUpNode.ID);
        }
    }

    private static final class WarmUpNode extends PaceNode {
        /** An id no node of a trace will reach. The real gate's registry sees these nodes come by, see {@link PaceNode}. */
        static final Long ID = Long.MAX_VALUE;

        WarmUpNode() {
            super(ID);
        }

        @Override
        boolean isFinished() {
            return false;
        }
    }
}
