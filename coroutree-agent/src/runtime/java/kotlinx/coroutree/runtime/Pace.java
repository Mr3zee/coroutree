package kotlinx.coroutree.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The only place a hook can wait (DESIGN §3.1). Holds application threads at events, globally or for a subtree.
 * Hold invariants:
 * <ul>
 * <li>No dependency on another thread's progress. Held threads read volatile settings and sleep for at most one tick.</li>
 * <li>No trace events or blocking-state changes. {@code inHook} suppresses sleep and interrupt hooks.</li>
 * <li>No change to application state. Sleep leaves the park permit untouched. Holds restore the interrupt flag and throw nothing.</li>
 * <li>No holds after tracing stops. Writer failure or shutdown releases threads at their next tick.</li>
 * </ul>
 * A sequence consists of steps from the same flow or on the same node. Parallel sequences have independent minimum intervals.
 *
 * {@link #GATE} is null when pace is disabled, or when neither a live socket nor a configured pace needs it.
 */
final class Pace {
    static final long NEVER = Long.MIN_VALUE;

    /** Maximum delay before a held thread checks settings and permits again. */
    static final long TICK_NANOS = 10_000_000;

    /** Caps intervals at one day to prevent overflow in {@code last + interval}. */
    static final long MAX_INTERVAL_NANOS = 86_400_000_000_000L;

    private static final long LOOK_AGAIN = -1;

    /** {@link Tracer#start} must set the configuration before this class loads. */
    static final Pace GATE = create(Tracer.config);

    private static Pace create(AgentConfig config) {
        if (config == null || !config.hasGate()) return null;
        return new Pace(config.paceIntervalNanos, config.pacePaused && config.live, true);
    }

    /** The global setting: what governs a node that has no setting on its way to the root. */
    final PaceScope root = new PaceScope(null);

    /** True only when no scope is paused or paced, so await needs only this volatile read. */
    private volatile boolean open;

    private final long configuredIntervalNanos;

    /** False for the warm-up gate, which records nothing and works without active tracing. */
    private final boolean recorded;

    // Serializes commands, shutdown, and removal of finished-node settings. Held threads never take this lock.
    // Nothing can wait for execution control under this lock.
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

    /**
     * Call after node discovery and before state reads, decisions, or locks. Always returns normally.
     * @param unit the execution unit where the step happens
     * @param node the node where the event happens
     * @param other a second node this hook emits on, or null
     */
    static void await(ThreadState ts, PaceNode unit, PaceNode node, PaceNode other) {
        Pace gate = GATE;
        if (gate == null) return;
        gate.pass(ts, unit, node, other);
    }

    void pass(ThreadState ts, PaceNode unit, PaceNode node, PaceNode other) {
        if (ts.awaited) settle(ts); // node discovery can create a separate step in the same hook
        ts.awaited = true;
        if (open || ts.neverHeld) return;
        hold(ts, unit, node, other);
    }

    /**
     * Call at hook exit or before the next await. Steps that emit nothing must return their slots and permits.
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
                if (wait == LOOK_AGAIN) continue; // another thread claimed a slot
                if (started == NEVER) started = now;
                // Clear the interrupt flag so sleep doesn't spin, then restore it before application code resumes.
                // Other threads can observe the cleared flag during the hold.
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

    /** Returns 0 after a claim, {@link #LOOK_AGAIN} for an immediate retry, or nanoseconds to sleep. */
    private long tryPass(ThreadState ts, long now, PaceNode unit, PaceNode flow, PaceNode node, PaceNode other) {
        // A paused subtree must block both events from it and events on it.
        PaceScope a = scopeOf(unit);
        PaceScope b = scopeOf(node);
        PaceScope c = other == null ? b : scopeOf(other);
        if (b == a) b = null;
        if (c == a || c == b) c = null;
        // A fixed order prevents two steps from each holding a permit the other needs.
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
            // Time the program spends blocked or suspended counts toward the interval.
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

        // All slots and the event use one timestamp, so hook overhead doesn't change the pacing interval.
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

    /** Returns the counter to refund, or null if no permit is available. */
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

    PaceScope scopeOf(PaceNode node) {
        for (PaceNode n = node; n != null; n = n.paceParent) {
            PaceScope scope = n.scope;
            if (scope != null) return scope;
        }
        return root;
    }

    /** Call after the trace has FINISHED and the node's finished flag is set. */
    static void nodeFinished(PaceNode node) {
        Pace gate = GATE;
        if (gate == null || node.scope == null) return;
        synchronized (gate.commandLock) {
            gate.drop(node, Wire.PACE_NODE_FINISHED);
            gate.updateOpen();
        }
    }

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

    /**
     * Control commands have no direct reply. Every client receives the resulting PaceDef in the trace stream.
     * A valid command makes its sender a controller, even if its target node is unknown and the command does nothing.
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
            // A new local setting starts with the inherited values.
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
                // A step command pauses a running program after the requested steps.
                scope.permits = new AtomicLong(steps);
                scope.paused = true;
            }
        }
        if (created) {
            target.scope = scope;
            scopedNodes.add(target);
        }
        record(definition(scope, steps, Wire.PACE_CONTROLLER, false));
        // The finish hook sets finished before checking scope. We set scope before checking finished.
        // Either path removes a setting created during completion. Repeated removal is safe.
        if (created && target.isFinished()) drop(target, Wire.PACE_NODE_FINISHED);
    }

    private void drop(PaceNode node, int reason) {
        PaceScope scope = node.scope;
        if (scope == null) return;
        node.scope = null;
        scopedNodes.remove(node);
        record(definition(scope, 0, reason, true));
    }

    void controllerJoined() {
        synchronized (commandLock) {
            controllers++;
        }
    }

    /**
     * The last controller's departure must resume the program, restore the configured pace, and remove subtree settings.
     */
    void controllerLeft() {
        synchronized (commandLock) {
            if (--controllers > 0 || shutDown || (recorded && !Tracer.active)) return;
            controllers = 0;
            revert(configuredIntervalNanos, Wire.PACE_FAIL_OPEN);
        }
    }

    /**
     * Must run before the first shutdown hook starts ({@link Hooks#shutdownBegins}), while the trace is open.
     * Otherwise, a paused System.exit caller can block at Thread.start before the shutdown hook can disable tracing.
     * Releases all holds and rejects later commands.
     */
    void shutdown() {
        synchronized (commandLock) {
            if (shutDown) return;
            shutDown = true;
            revert(0, Wire.PACE_SHUTDOWN);
        }
    }

    /**
     * Without a live socket, no controller can resume an initial pause. Returns whether that pause was removed.
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

    // A control reader must never load a class for the first time: its static initializer can be running on a held
    // thread. So no regex here, and warmUp runs the parser before tracing starts.

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
     * Loads hold and command dependencies before any thread can be held. Uses a separate gate without trace output.
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
        /** Reserved from real trace ids because {@link PaceNode} also registers warm-up nodes in the real gate. */
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
