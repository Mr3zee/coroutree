package kotlinx.coroutree.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate by itself, without an instrumented program around it: what holds whom for how long, what a step takes and
 * gives back, what a command does, and what the hold leaves untouched in the thread it holds. Lower bounds on time
 * are exact (that is the promise); upper bounds are generous (that is the machine).
 */
@Timeout(60)
class PaceTest {
    static {
        // The gate of a JVM is made from its configuration the first time Pace is touched; nodes keep time only in a JVM that has one.
        Tracer.config = AgentConfig.parse("live=false,pace.events.per.second=1000");
    }

    private static final long MS = 1_000_000;
    private static final AtomicLong IDS = new AtomicLong(1_000);

    private final List<TraceEvent.PaceDef> defs = new CopyOnWriteArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    @BeforeAll
    static void theJvmHasAGate() {
        assertNotNull(Pace.GATE, "PaceTest must be the one to initialise Pace");
    }

    @BeforeEach
    void setUp() {
        Tracer.active = true;
        Tracer.paceSink = defs;
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        Tracer.active = false; // fail open: whoever a failed test left at a gate goes
        for (Thread thread : threads) thread.join(10_000);
        Tracer.paceSink = null;
        ThreadState ts = ThreadState.current();
        Pace.settle(ts);
        ts.heldNanos = 0;
        Thread.interrupted();
    }

    // ------------------------------------------------------------------ fixtures

    private static final class Node extends PaceNode {
        volatile boolean finished;

        Node() {
            super(IDS.incrementAndGet());
        }

        Node(PaceNode parent) {
            this();
            paceParent = parent;
        }

        @Override
        boolean isFinished() {
            return finished;
        }
    }

    private static Pace gate(long intervalNanos, boolean paused) {
        return new Pace(intervalNanos, paused, true);
    }

    /** A node the gate knows by id, the way every node of a traced program is known to the real one. */
    private static Node known(Pace gate, PaceNode parent) {
        Node node = new Node(parent);
        gate.register(node);
        return node;
    }

    /** What a hook call does with the gate: pass, emit or not, settle. Returns the time of release. */
    private static long step(Pace gate, PaceNode unit, PaceNode node, PaceNode other, boolean emits) {
        ThreadState ts = ThreadState.current();
        gate.pass(ts, unit, node, other);
        long release = ts.releaseNanos;
        if (emits) ts.stepEmitted = true; // what Tracer.emit notes
        Pace.settle(ts);
        return release;
    }

    private static long step(Pace gate, PaceNode node) {
        return step(gate, node, node, null, true);
    }

    private static long takeHeld() {
        ThreadState ts = ThreadState.current();
        long held = ts.heldNanos;
        ts.heldNanos = 0;
        return held;
    }

    private Thread start(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
        return thread;
    }

    /** A thread that makes one step and says when it is through. */
    private final class Stepper {
        final CountDownLatch through = new CountDownLatch(1);
        final AtomicLong held = new AtomicLong(-1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;

        Stepper(Pace gate, PaceNode unit, PaceNode node, boolean emits) {
            thread = start("stepper", () -> {
                try {
                    step(gate, unit, node, null, emits);
                    held.set(takeHeld());
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    through.countDown();
                }
            });
        }

        boolean isThroughWithin(long millis) throws InterruptedException {
            return through.await(millis, TimeUnit.MILLISECONDS);
        }

        void assertHeldFor(long millis) throws InterruptedException {
            assertFalse(isThroughWithin(millis), "the step went through a gate that should hold it");
        }

        void assertThrough() throws InterruptedException {
            assertTrue(isThroughWithin(5_000), "the step is still held");
            assertNull(failure.get());
        }
    }

    private static void awaitState(Thread thread, Thread.State state) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000 * MS;
        while (thread.getState() != state && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(state, thread.getState());
    }

    // ------------------------------------------------------------------ the open gate

    @Test
    void anOpenGateHoldsNobodyAndKeepsNoBooks() {
        Pace gate = gate(0, false);
        Node node = new Node();
        assertTrue(gate.isOpen());
        ThreadState ts = ThreadState.current();
        gate.pass(ts, node, node, null);
        assertTrue(ts.awaited, "the hook has passed the gate, which is what emit asks for");
        assertEquals(Pace.NEVER, ts.releaseNanos, "through an open gate an event takes the time of its emit");
        assertEquals(0, ts.claimedSlots);
        Pace.settle(ts);
        assertFalse(ts.awaited);
        assertEquals(Pace.NEVER, node.lastStep.get());
        assertEquals(0, takeHeld());
    }

    @Test
    void aGateIsOpenOnlyWithNothingSetAnywhere() {
        Pace gate = gate(0, false);
        Node node = known(gate, null);
        assertTrue(gate.isOpen());
        gate.command("pace 0 " + node.id); // a setting that holds nobody is a setting still: it shields the subtree from the global one
        assertFalse(gate.isOpen());
        gate.command("inherit " + node.id);
        assertTrue(gate.isOpen());
        gate.command("pace 5");
        assertFalse(gate.isOpen());
        gate.command("pace 0");
        assertTrue(gate.isOpen());
        gate.command("pause");
        assertFalse(gate.isOpen());
        gate.command("resume");
        assertTrue(gate.isOpen());
    }

    // ------------------------------------------------------------------ a pace

    @Test
    void theFirstStepOfASequenceIsNotHeld() {
        Pace gate = gate(200 * MS, false);
        long before = System.nanoTime();
        step(gate, new Node());
        assertTrue(System.nanoTime() - before < 150 * MS);
        assertEquals(0, takeHeld());
    }

    @Test
    void stepsOfOneSequenceAreAnIntervalApartExactly() {
        long interval = 20 * MS;
        Pace gate = gate(interval, false);
        Node node = new Node();
        long previous = step(gate, node);
        for (int i = 0; i < 10; i++) {
            long release = step(gate, node);
            assertTrue(release - previous >= interval, "released " + (release - previous) + " ns after the step before");
            assertTrue(release - previous < interval + 500 * MS);
            assertEquals(release, node.lastStep.get(), "the time of release is the time the next step is measured from");
            previous = release;
        }
        long held = takeHeld();
        assertTrue(held >= 10 * interval - 10 * MS && held < 10 * interval + 2_000 * MS, "held for " + held);
    }

    @Test
    void theIntervalIsAMinimumNotASurcharge() throws InterruptedException {
        Pace gate = gate(30 * MS, false);
        Node node = new Node();
        step(gate, node);
        takeHeld();
        Thread.sleep(60); // the program's own blocking counts
        long before = System.nanoTime();
        step(gate, node);
        assertEquals(0, takeHeld(), "a step that comes later than the pace asks is not held at all");
        assertTrue(System.nanoTime() - before < 25 * MS);
    }

    @Test
    void partOfTheIntervalThatHasPassedIsNotWaitedAgain() throws InterruptedException {
        Pace gate = gate(100 * MS, false);
        Node node = new Node();
        step(gate, node);
        Thread.sleep(70);
        step(gate, node);
        long held = takeHeld();
        assertTrue(held <= 40 * MS, "held for " + held);
    }

    @Test
    void parallelSequencesAreNotSerialised() throws InterruptedException {
        long interval = 25 * MS;
        int sequences = 8, steps = 8;
        Pace gate = gate(interval, false);
        CountDownLatch done = new CountDownLatch(sequences);
        long started = System.nanoTime();
        for (int i = 0; i < sequences; i++) {
            Node node = new Node();
            start("sequence-" + i, () -> {
                for (int s = 0; s < steps; s++) step(gate, node);
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        long elapsed = System.nanoTime() - started;
        assertTrue(elapsed >= (steps - 1) * interval, "each sequence takes its time");
        // Serialised, it would be sequences × steps × interval = 1.6 s.
        assertTrue(elapsed < sequences * (steps - 1) * interval / 2, "eight sequences took " + elapsed / MS + " ms: they waited for each other");
    }

    @Test
    void twoCoroutinesOnOneThreadAreNotSpacedAgainstEachOther() {
        Pace gate = gate(500 * MS, false);
        Node first = new Node(), second = new Node(), third = new Node();
        long before = System.nanoTime();
        step(gate, first);
        step(gate, second); // same thread, another sequence: concurrent counts as parallel
        step(gate, third);
        assertEquals(0, takeHeld());
        assertTrue(System.nanoTime() - before < 400 * MS);
    }

    @Test
    void theFlowRuleSpacesWhatOneLineOfExecutionDoesToOthers() {
        long interval = 20 * MS;
        Pace gate = gate(interval, false);
        Node parent = new Node();
        long previous = step(gate, parent);
        for (int i = 0; i < 3; i++) { // a loop that launches three children: each is news to a new node, by the same flow
            long release = step(gate, parent, new Node(), null, true);
            assertTrue(release - previous >= interval);
            previous = release;
        }
    }

    @Test
    void theNodeRuleSpacesWhatHappensToOneNodeFromDifferentFlows() {
        long interval = 20 * MS;
        Pace gate = gate(interval, false);
        Node child = new Node();
        long launched = step(gate, new Node(), child, null, true); // launched by its parent,
        long started = step(gate, child, child, null, true);       // then started by itself a moment later,
        long cancelled = step(gate, new Node(), child, null, true); // then cancelled from outside
        assertTrue(started - launched >= interval);
        assertTrue(cancelled - started >= interval);
    }

    @Test
    void aSecondNodeOfTheStepIsASequenceToKeepADistanceInToo() {
        long interval = 30 * MS;
        Pace gate = gate(interval, false);
        Node scope = new Node(), caller = new Node(), lastChild = new Node();
        long callerStep = step(gate, caller);
        // The scope ends on the thread of its last child and hands its failure to the caller: an event on the caller.
        long completed = step(gate, lastChild, scope, caller, true);
        assertTrue(completed - callerStep >= interval);
        assertEquals(completed, caller.lastStep.get());
        assertEquals(completed, scope.lastStep.get());
        assertEquals(completed, lastChild.lastStep.get());
    }

    @Test
    void stepsThatShareASequenceKeepTheirDistanceUnderContention() throws Exception {
        long interval = 2 * MS;
        Pace gate = gate(interval, false);
        int workers = 8, stepsEach = 40;
        Node[] flows = new Node[workers];
        Node[] shared = {new Node(), new Node(), new Node()};
        List<long[]> log = Collections.synchronizedList(new ArrayList<>()); // {release, flow index, shared index}
        CountDownLatch done = new CountDownLatch(workers);
        for (int w = 0; w < workers; w++) {
            flows[w] = new Node();
            int worker = w;
            start("contender-" + w, () -> {
                for (int s = 0; s < stepsEach; s++) {
                    int target = (worker + s) % shared.length;
                    boolean emits = s % 7 != 3; // some steps turn out to have nothing to report and give their place back
                    long release = step(gate, flows[worker], shared[target], null, emits);
                    if (emits) log.add(new long[] {release, worker, target});
                }
                done.countDown();
            });
        }
        assertTrue(done.await(50, TimeUnit.SECONDS));
        for (int slot = 0; slot < workers + shared.length; slot++) {
            int index = slot;
            long[] times = log.stream().filter(e -> index < workers ? e[1] == index : e[2] == index - workers).mapToLong(e -> e[0]).sorted().toArray();
            assertTrue(times.length > 1);
            for (int i = 1; i < times.length; i++) {
                assertTrue(times[i] - times[i - 1] >= interval, "two steps of sequence " + index + " are " + (times[i] - times[i - 1]) + " ns apart");
            }
        }
    }

    // ------------------------------------------------------------------ flows

    @Test
    void aCalleeIsPartOfItsCallersFlow() {
        ThreadNode thread = new ThreadNode(IDS.incrementAndGet());
        JobNode runBlocking = new JobNode(IDS.incrementAndGet(), false, false);
        runBlocking.caller = thread;
        JobNode scope = new JobNode(IDS.incrementAndGet(), true, false);
        scope.caller = runBlocking;
        JobNode launched = new JobNode(IDS.incrementAndGet(), false, false);
        assertSame(thread, thread.flow(), "a thread outside any coroutine is a flow too");
        assertSame(thread, runBlocking.flow());
        assertSame(thread, scope.flow());
        assertSame(launched, launched.flow(), "a coroutine that nobody waits for in place is a flow of its own");
    }

    @Test
    void aFlowIsFoundThroughAnyDepthOfScopes() {
        JobNode root = new JobNode(IDS.incrementAndGet(), false, false);
        JobNode node = root;
        for (int i = 0; i < 200_000; i++) {
            JobNode callee = new JobNode(IDS.incrementAndGet(), true, false);
            callee.caller = node;
            node = callee;
        }
        assertSame(root, node.flow());
    }

    @Test
    void stepsOfACalleeAndOfItsCallerAreOneSequence() {
        long interval = 20 * MS;
        Pace gate = gate(interval, false);
        JobNode caller = new JobNode(IDS.incrementAndGet(), false, false);
        JobNode scope = new JobNode(IDS.incrementAndGet(), true, false);
        scope.caller = caller;
        long first = step(gate, caller);
        long second = step(gate, scope); // another node, the same line of execution
        assertTrue(second - first >= interval);
        assertEquals(second, caller.lastStep.get());
    }

    // ------------------------------------------------------------------ giving back

    @Test
    void aStepWithNothingToReportCostsTheSequenceNothing() {
        Pace gate = gate(300 * MS, false);
        Node node = new Node();
        long first = step(gate, node);
        ThreadState ts = ThreadState.current();
        node.lastStep.set(first - 400 * MS); // as if the interval had passed
        long before = node.lastStep.get();
        long release = step(gate, node, node, null, false);
        assertTrue(release > before);
        assertEquals(before, node.lastStep.get(), "its place in the sequence is given back");
        assertEquals(0, ts.claimedSlots);
        step(gate, node);
        assertEquals(0, takeHeld(), "and the step that does report is not held for it");
    }

    @Test
    void aPlaceIsNotGivenBackOverSomebodyElsesClaim() {
        Pace gate = gate(1, false);
        Node node = new Node();
        ThreadState ts = ThreadState.current();
        gate.pass(ts, node, node, null);
        long later = ts.claimTime + 1_000;
        node.lastStep.set(later); // another thread took the next place off this claim
        Pace.settle(ts);
        assertEquals(later, node.lastStep.get());
    }

    @Test
    void theStepBeforeIsSettledByTheNextAwaitOfTheSameHookCall() {
        Pace gate = gate(1, false);
        Node found = new Node(), about = new Node();
        ThreadState ts = ThreadState.current();
        gate.pass(ts, found, found, null); // finding a node reported it: a step of its own
        ts.stepEmitted = true;
        long discovered = ts.claimTime;
        gate.pass(ts, about, about, null);
        assertFalse(ts.stepEmitted, "the hook's own step starts with a clean slate");
        assertEquals(1, ts.claimedSlots);
        assertEquals(discovered, found.lastStep.get(), "and the step before it keeps its place");
        Pace.settle(ts);
        assertEquals(Pace.NEVER, about.lastStep.get());
    }

    @Test
    void timeHeldForAStepThatReportedNothingIsCarriedToTheNextEvent() {
        Pace gate = gate(15 * MS, false);
        Node node = new Node();
        step(gate, node);
        step(gate, node, node, null, false);
        long carried = ThreadState.current().heldNanos;
        assertTrue(carried >= 10 * MS, "held for " + carried);
        step(gate, node);
        assertTrue(takeHeld() >= carried, "the thread's account of held time only grows until an event takes it");
    }

    // ------------------------------------------------------------------ pause and step

    @Test
    void pauseHoldsEveryStepAndResumeLetsThemGo() throws InterruptedException {
        Pace gate = gate(0, false);
        gate.command("pause");
        Stepper a = new Stepper(gate, new Node(), new Node(), true), b = new Stepper(gate, new Node(), new Node(), true);
        a.assertHeldFor(150);
        b.assertHeldFor(10);
        gate.command("resume");
        a.assertThrough();
        b.assertThrough();
        assertTrue(a.held.get() >= 140 * MS, "held for " + a.held.get());
    }

    @Test
    void stepLetsExactlyThatManyThrough() throws InterruptedException {
        Pace gate = gate(0, true);
        List<Stepper> steppers = new ArrayList<>();
        for (int i = 0; i < 6; i++) steppers.add(new Stepper(gate, new Node(), new Node(), true));
        steppers.get(0).assertHeldFor(100);
        gate.command("step 3");
        Thread.sleep(300);
        assertEquals(3, steppers.stream().filter(s -> s.through.getCount() == 0).count(), "parallel sequences have no order to respect, but three are three");
        gate.command("step 1");
        Thread.sleep(200);
        assertEquals(4, steppers.stream().filter(s -> s.through.getCount() == 0).count());
        gate.command("resume");
        for (Stepper stepper : steppers) stepper.assertThrough();
    }

    @Test
    void aStepWithNothingToReportGivesItsPermitBack() throws InterruptedException {
        Pace gate = gate(0, true);
        gate.command("step 1");
        step(gate, new Node(), new Node(), null, false); // a probe that had nothing to say
        Stepper next = new Stepper(gate, new Node(), new Node(), true);
        next.assertThrough();
        new Stepper(gate, new Node(), new Node(), true).assertHeldFor(100);
    }

    @Test
    void aPermitHandedBackLateDoesNotOpenAGateThatWasClosedMeanwhile() throws InterruptedException {
        Pace gate = gate(0, true);
        gate.command("step 1");
        ThreadState ts = ThreadState.current();
        Node node = new Node();
        gate.pass(ts, node, node, null);
        assertEquals(1, ts.claimedPermits);
        gate.command("pause"); // the user changed their mind while the hook was at work
        Pace.settle(ts);       // … and the hook has nothing to report
        assertEquals(0, gate.root.permits.get());
        new Stepper(gate, new Node(), new Node(), true).assertHeldFor(100);
    }

    @Test
    void quickStepsAddUp() {
        Pace gate = gate(0, true);
        for (int i = 0; i < 5; i++) gate.command("step 1");
        assertEquals(5, gate.root.permits.get());
        for (int i = 0; i < 5; i++) step(gate, new Node());
        assertEquals(0, gate.root.permits.get());
    }

    @Test
    void stepOfARunningProgramStopsItAfterThatMany() throws InterruptedException {
        Pace gate = gate(0, false);
        gate.command("step 2");
        assertTrue(gate.root.paused);
        step(gate, new Node());
        step(gate, new Node());
        new Stepper(gate, new Node(), new Node(), true).assertHeldFor(100);
    }

    @Test
    void pauseAndResumeForgetPermits() {
        Pace gate = gate(0, true);
        gate.command("step 7");
        gate.command("pause");
        assertEquals(0, gate.root.permits.get());
        gate.command("step 7");
        gate.command("resume");
        assertEquals(0, gate.root.permits.get());
        gate.command("pause");
        assertEquals(0, gate.root.permits.get(), "what was left when it was resumed does not come back");
    }

    @Test
    void steppedStepsStillCountForThePaceThatFollows() {
        long interval = 40 * MS;
        Pace gate = gate(interval, true);
        Node node = new Node();
        gate.command("step 1");
        long stepped = step(gate, node);
        gate.command("resume");
        long paced = step(gate, node);
        assertTrue(paced - stepped >= interval);
    }

    @Test
    void aPaceIsNotLookedAtWhilePaused() {
        Pace gate = gate(10_000 * MS, true);
        Node node = new Node();
        gate.command("step 2");
        long before = System.nanoTime();
        step(gate, node);
        step(gate, node); // the user asked for it: it does not wait ten seconds
        assertTrue(System.nanoTime() - before < 2_000 * MS);
    }

    // ------------------------------------------------------------------ per subtree

    @Test
    void aSettingOnANodeHoldsForItsSubtreeAndNobodyElse() throws InterruptedException {
        Pace gate = gate(0, false);
        Node root = known(gate, null), paused = known(gate, root), child = known(gate, paused), grandchild = known(gate, child), sibling = known(gate, root);
        gate.command("pause " + paused.id);
        Stepper own = new Stepper(gate, paused, paused, true), below = new Stepper(gate, grandchild, grandchild, true);
        step(gate, sibling);
        step(gate, root);
        assertEquals(0, takeHeld(), "a sibling subtree and the parent run on");
        own.assertHeldFor(100);
        below.assertHeldFor(10);
        gate.command("resume " + paused.id);
        own.assertThrough();
        below.assertThrough();
    }

    @Test
    void theInnermostSettingOnTheWayToTheRootWins() throws InterruptedException {
        Pace gate = gate(0, false);
        Node outer = known(gate, null), inner = known(gate, outer), leaf = known(gate, inner);
        gate.command("pause");
        gate.command("pause " + outer.id);
        gate.command("resume " + inner.id); // everything stopped but this subtree
        step(gate, leaf);
        step(gate, inner);
        assertEquals(0, takeHeld());
        new Stepper(gate, outer, outer, true).assertHeldFor(100);
        assertSame(gate.scopeOf(leaf), inner.scope);
        assertSame(gate.scopeOf(outer), outer.scope);
        assertSame(gate.scopeOf(new Node()), gate.root);
    }

    @Test
    void aNodesSettingStartsAsWhatGovernsItAndGoesItsOwnWay() {
        Pace gate = gate(7 * MS, false);
        Node parent = known(gate, null), node = known(gate, parent);
        gate.command("pause " + node.id);
        assertTrue(node.scope.paused);
        assertEquals(7 * MS, node.scope.intervalNanos, "pausing a subtree and resuming it leaves it at the pace it had");
        gate.command("pace 1");
        assertEquals(7 * MS, node.scope.intervalNanos, "independent of the global setting from then on");
        gate.command("inherit " + node.id);
        assertNull(node.scope);
        assertEquals(1, gate.scopeOf(node).intervalNanos);

        gate.command("pause");
        gate.command("pace 3 " + node.id);
        assertTrue(node.scope.paused, "a copy of what governs it: a paused program's subtree does not start running because its speed was set");
    }

    @Test
    void nothingHappensToAPausedSubtreeAndNothingHappensFromIt() throws InterruptedException {
        Pace gate = gate(0, false);
        Node pausedRoot = known(gate, null), inside = known(gate, pausedRoot), outside = known(gate, null);
        gate.command("pause " + pausedRoot.id);
        Stepper into = new Stepper(gate, outside, inside, true);  // an outsider cancels a coroutine in there: held at that event
        Stepper outOf = new Stepper(gate, inside, outside, true); // and nothing reaches out of it
        into.assertHeldFor(100);
        outOf.assertHeldFor(10);
        gate.command("step 2 " + pausedRoot.id);
        into.assertThrough();
        outOf.assertThrough();
    }

    @Test
    void theStricterOfTwoPacesGoverns() {
        Pace gate = gate(0, false);
        Node slow = known(gate, null), slower = known(gate, null);
        gate.command("pace " + 10 * MS + " " + slow.id);
        gate.command("pace " + 40 * MS + " " + slower.id);
        long first = step(gate, slow, slower, null, true);
        long second = step(gate, slow, slower, null, true);
        assertTrue(second - first >= 40 * MS);
    }

    @Test
    void aStepBetweenTwoPausedSubtreesTakesAPermitOfEach() throws InterruptedException {
        Pace gate = gate(0, false);
        Node a = known(gate, null), b = known(gate, null);
        gate.command("pause " + a.id);
        gate.command("pause " + b.id);
        Stepper between = new Stepper(gate, a, b, true);
        gate.command("step 1 " + a.id);
        between.assertHeldFor(100);
        assertEquals(1, a.scope.permits.get(), "a permit is not kept while the other one is missing");
        gate.command("step 1 " + b.id);
        between.assertThrough();
        assertEquals(0, a.scope.permits.get());
        assertEquals(0, b.scope.permits.get());
    }

    @Test
    void stepsThatNeedTheSamePermitsInOppositeOrderDoNotStarveEachOther() throws InterruptedException {
        Pace gate = gate(0, false);
        Node a = known(gate, null), b = known(gate, null);
        gate.command("pause " + a.id);
        gate.command("pause " + b.id);
        for (int round = 0; round < 20; round++) {
            Stepper forth = new Stepper(gate, a, b, true), back = new Stepper(gate, b, a, true);
            gate.command("step 1 " + a.id);
            gate.command("step 1 " + b.id);
            Thread.sleep(60);
            assertEquals(1, (forth.through.getCount() == 0 ? 1 : 0) + (back.through.getCount() == 0 ? 1 : 0), "one permit of each lets exactly one of them through");
            gate.command("step 1 " + a.id);
            gate.command("step 1 " + b.id);
            forth.assertThrough();
            back.assertThrough();
        }
    }

    @Test
    void theSettingOfASecondNodeGovernsTheStepToo() throws InterruptedException {
        Pace gate = gate(0, false);
        Node scope = known(gate, null), lastChild = known(gate, null), caller = known(gate, null);
        gate.command("pause " + caller.id);
        ThreadState.current(); // (the stepper has its own)
        AtomicBoolean through = new AtomicBoolean();
        start("completer", () -> {
            step(gate, lastChild, scope, caller, true);
            through.set(true);
        });
        Thread.sleep(100);
        assertFalse(through.get(), "an event lands on the paused caller: held");
        gate.command("resume " + caller.id);
        Thread.sleep(100);
        assertTrue(through.get());
    }

    @Test
    void theEndOfANodeLiftsItsSetting() throws InterruptedException {
        Node node = new Node(); // registered with the JVM's gate: nodeFinished is what a hook calls
        Pace.GATE.command("pause " + node.id);
        assertNotNull(node.scope);
        Stepper below = new Stepper(Pace.GATE, new Node(node), new Node(node), true);
        below.assertHeldFor(100);
        node.finished = true;
        Pace.nodeFinished(node);
        assertNull(node.scope);
        below.assertThrough();
        TraceEvent.PaceDef last = defs.get(defs.size() - 1);
        assertEquals(node.id, last.scopeNodeId);
        assertTrue(last.dropped);
        assertEquals(Wire.PACE_NODE_FINISHED, last.reason);
    }

    @Test
    void aSettingNeverOutlivesItsNodeHoweverTheTwoRace() throws Exception {
        Pace gate = Pace.GATE;
        for (int i = 0; i < 300; i++) {
            Node node = new Node();
            CountDownLatch go = new CountDownLatch(1);
            Thread finisher = start("finisher", () -> {
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                }
                node.finished = true;   // what a hook does: first the flag,
                Pace.nodeFinished(node); // then a look at the setting
            });
            go.countDown();
            gate.command("pause " + node.id);
            finisher.join();
            assertNull(node.scope, "round " + i + ": a node that has ended still carries a setting");
        }
        assertFalse(gate.root.paused);
    }

    // ------------------------------------------------------------------ commands

    @Test
    void whatIsNotACommandIsIgnoredAndMakesNoController() {
        Pace gate = gate(0, false);
        String[] junk = {"", " ", "\t", "bogus", "PAUSE", "Pause", "pace", "pace fast", "pace -1", "pace 1.5", "pace 1e3", "pace 0x10", "pace 1 2 3",
            "step", "step 0", "step -1", "step one", "step 1 2 3", "pause 1 2", "pause me", "resume 1 2", "inherit", "inherit 0", "inherit x",
            "inherit 1 2", "pause ", "pace ١٢٣", "pace +5", "pace 99999999999999999999999999999999", "pause " + "9".repeat(40)};
        for (String line : junk) {
            assertFalse(gate.command(line), "'" + line + "' was taken for a command");
        }
        assertTrue(gate.isOpen());
        assertTrue(defs.isEmpty());
    }

    @Test
    void aCommandAboutANodeNobodyKnowsIsACommandThatDoesNothing() {
        Pace gate = gate(0, false);
        Node ended = known(gate, null);
        ended.finished = true;
        assertTrue(gate.command("pause 123456789"));
        assertTrue(gate.command("pause " + ended.id));
        assertTrue(gate.command("step 3 " + ended.id));
        assertTrue(gate.command("inherit 123456789"));
        assertTrue(gate.command("inherit " + known(gate, null).id), "nothing to drop is nothing to do");
        assertNull(ended.scope);
        assertTrue(gate.isOpen());
        assertTrue(defs.isEmpty());
    }

    @Test
    void commandsAreReadLeniently() {
        Pace gate = gate(0, false);
        assertTrue(gate.command("  pace \t 5000  "));
        assertEquals(5000, gate.root.intervalNanos);
        assertTrue(gate.command("pause\r"));
        assertTrue(gate.root.paused);
        assertTrue(gate.command("resume 0"), "0 names the global setting");
        assertFalse(gate.root.paused);
        assertTrue(gate.command("pace 0"));
        assertTrue(gate.isOpen());
    }

    @Test
    void anIntervalIsCutToWhatCanBeAddedToTheClock() throws InterruptedException {
        Pace gate = gate(0, false);
        gate.command("pace " + Long.MAX_VALUE);
        assertEquals(Pace.MAX_INTERVAL_NANOS, gate.root.intervalNanos);
        gate.command("pace 9223372036854775808"); // one more than fits
        assertEquals(Pace.MAX_INTERVAL_NANOS, gate.root.intervalNanos);
        Node node = new Node();
        step(gate, node);
        new Stepper(gate, node, node, true).assertHeldFor(100); // and not let through by an overflow
        gate.command("step " + Long.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, gate.root.permits.get());
    }

    @Test
    void everyChangeIsSaidInTheTraceTheWayItIsNow() {
        Pace gate = gate(3, false);
        Node node = known(gate, null);
        gate.announce();
        gate.command("pace 9");
        gate.command("pause");
        gate.command("step 4");
        gate.command("pace 2 " + node.id);
        gate.command("inherit " + node.id);
        gate.command("resume");
        String[] expected = {
            "0 interval=3 paused=false steps=0 reason=1 dropped=false",
            "0 interval=9 paused=false steps=0 reason=2 dropped=false",
            "0 interval=9 paused=true steps=0 reason=2 dropped=false",
            "0 interval=9 paused=true steps=4 reason=2 dropped=false",
            node.id + " interval=2 paused=true steps=0 reason=2 dropped=false",
            node.id + " interval=0 paused=false steps=0 reason=2 dropped=true",
            "0 interval=9 paused=false steps=0 reason=2 dropped=false",
        };
        assertEquals(expected.length, defs.size());
        for (int i = 0; i < expected.length; i++) {
            TraceEvent.PaceDef def = defs.get(i);
            assertEquals(expected[i], def.scopeNodeId + " interval=" + def.intervalNanos + " paused=" + def.paused + " steps=" + def.steps
                + " reason=" + def.reason + " dropped=" + def.dropped);
        }
    }

    @Test
    void commandsFromManyClientsAtOnceLeaveTheGateInOneOfTheirStates() throws Exception {
        Pace gate = gate(0, false);
        Node node = known(gate, null);
        CountDownLatch done = new CountDownLatch(4);
        for (int t = 0; t < 4; t++) {
            int client = t;
            start("client-" + t, () -> {
                for (int i = 0; i < 2_000; i++) {
                    switch ((i + client) % 5) {
                        case 0 -> gate.command("pause " + node.id);
                        case 1 -> gate.command("inherit " + node.id);
                        case 2 -> gate.command("pace " + i);
                        case 3 -> gate.command("step 1 " + node.id);
                        default -> gate.command("resume");
                    }
                }
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        gate.command("inherit " + node.id);
        gate.command("pace 0");
        gate.command("resume");
        assertTrue(gate.isOpen(), "what the commands say and what the gate does are the same thing");
        // The trace agrees: the last word about every scope is what the scope is.
        TraceEvent.PaceDef lastOfNode = null;
        for (TraceEvent.PaceDef def : defs) if (def.scopeNodeId == node.id) lastOfNode = def;
        assertNotNull(lastOfNode);
        assertTrue(lastOfNode.dropped);
    }

    // ------------------------------------------------------------------ controllers, failing open

    @Test
    void whenTheLastControllerGoesEverythingIsAsConfiguredAndNothingIsPaused() throws InterruptedException {
        Pace gate = gate(5, true);
        Node node = known(gate, null);
        gate.controllerJoined();
        gate.controllerJoined();
        gate.command("pace 1000");
        gate.command("pause " + node.id);
        Stepper held = new Stepper(gate, node, node, true);
        held.assertHeldFor(100);
        gate.controllerLeft();
        assertTrue(gate.root.paused, "one controller is left");
        assertNotNull(node.scope);
        defs.clear();
        gate.controllerLeft();
        assertFalse(gate.root.paused);
        assertEquals(5, gate.root.intervalNanos, "the configured pace, not none");
        assertNull(node.scope);
        held.assertThrough();
        assertEquals(2, defs.size());
        assertTrue(defs.get(0).dropped);
        assertEquals(node.id, defs.get(0).scopeNodeId);
        for (TraceEvent.PaceDef def : defs) assertEquals(Wire.PACE_FAIL_OPEN, def.reason);
        assertEquals(0, defs.get(1).scopeNodeId);
        assertFalse(defs.get(1).paused);
    }

    @Test
    void aProgramThatWasToStartPausedStaysSoUntilThereHasBeenAController() throws InterruptedException {
        Pace gate = gate(0, true);
        Stepper first = new Stepper(gate, new Node(), new Node(), true);
        first.assertHeldFor(200); // nobody has connected, nobody has left: it waits, as asked
        gate.controllerJoined();
        gate.command("step 1");
        first.assertThrough();
        gate.controllerLeft(); // the GUI crashed
        step(gate, new Node());
        assertEquals(0, takeHeld());
    }

    @Test
    void aControllerThatChangedNothingLeavesNoMark() {
        Pace gate = gate(0, false);
        gate.controllerJoined();
        gate.controllerLeft();
        assertTrue(defs.isEmpty());
    }

    @Test
    void withTracingOffEveryHeldThreadGoes() throws InterruptedException {
        Pace gate = gate(0, true);
        Node node = known(gate, null);
        gate.command("pause " + node.id);
        Stepper global = new Stepper(gate, new Node(), new Node(), true), subtree = new Stepper(gate, node, node, true);
        global.assertHeldFor(100);
        Tracer.active = false; // the writer failed, or the JVM is going down
        global.assertThrough();
        subtree.assertThrough();
    }

    @Test
    void shutdownOpensTheGateAndSaysSo() {
        Pace gate = gate(9, true);
        Node node = known(gate, null);
        gate.command("pause " + node.id);
        defs.clear();
        gate.shutdown();
        assertTrue(gate.isOpen());
        assertEquals(2, defs.size());
        for (TraceEvent.PaceDef def : defs) assertEquals(Wire.PACE_SHUTDOWN, def.reason);
        assertTrue(defs.get(0).dropped);
        assertEquals(0, defs.get(1).intervalNanos);
    }

    @Test
    void afterShutdownTheGateStaysOpenWhateverIsSent() {
        Pace gate = gate(0, false);
        Node node = known(gate, null);
        gate.controllerJoined();
        gate.shutdown();
        defs.clear();
        assertTrue(gate.command("pause"), "still a command, to a gate that no longer listens");
        gate.command("pause " + node.id);
        gate.command("step 1");
        gate.command("pace 1000000000");
        gate.controllerLeft();
        gate.shutdown();
        assertTrue(gate.isOpen());
        assertNull(node.scope);
        assertTrue(defs.isEmpty(), "and nothing more is said about it");
        step(gate, node);
        assertEquals(0, takeHeld());
    }

    @Test
    void aThreadOfTheJvmItselfIsNeverHeld() throws Exception {
        // The Signal Dispatcher starts the "SIGTERM handler" thread, in the root thread group like itself. Held at a
        // paused gate inside that Thread.start, it would never deliver the signal.
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        Pace gate = gate(0, true);
        CountDownLatch through = new CountDownLatch(1);
        AtomicBoolean marked = new AtomicBoolean();
        Thread system = new Thread(root, () -> {
            marked.set(ThreadState.current().neverHeld);
            Node node = new Node();
            step(gate, node);
            if (node.lastStep.get() == Pace.NEVER) through.countDown(); // and it takes no place in anybody's sequence
        }, "like the Signal Dispatcher");
        system.setDaemon(true);
        threads.add(system);
        system.start();
        assertTrue(through.await(5, TimeUnit.SECONDS), "a thread of the root group was held");
        assertTrue(marked.get());
        assertFalse(ThreadState.current().neverHeld, "the program's threads are");
        Thread virtual = Thread.ofVirtual().unstarted(() -> marked.set(ThreadState.current().neverHeld));
        virtual.start();
        virtual.join();
        assertFalse(marked.get(), "and so are virtual threads, whose group is not the root either");
    }

    @Test
    void withoutALiveSocketAProgramThatWasToStartPausedRuns() {
        Pace gate = gate(4, true);
        assertTrue(gate.liveUnavailable());
        assertFalse(gate.root.paused);
        assertEquals(4, gate.root.intervalNanos, "the configured pace needs no socket");
        assertEquals(Wire.PACE_FAIL_OPEN, defs.get(defs.size() - 1).reason);
        assertFalse(gate(4, false).liveUnavailable());
    }

    // ------------------------------------------------------------------ what the hold leaves alone

    @Test
    void aThreadInterruptedBeforeItIsHeldComesOutWithItsFlagSet() {
        Pace gate = gate(40 * MS, false);
        Node node = new Node();
        step(gate, node);
        Thread.currentThread().interrupt();
        long before = System.nanoTime();
        step(gate, node);
        assertTrue(System.nanoTime() - before >= 35 * MS, "the hold is not cut short by the interrupt");
        assertTrue(Thread.interrupted(), "and the interrupt is not lost");
    }

    @Test
    void aThreadInterruptedWhileHeldStaysHeldBurnsNoCpuAndKeepsItsFlag() throws Exception {
        Pace gate = gate(0, true);
        AtomicBoolean flagAfter = new AtomicBoolean();
        AtomicLong cpu = new AtomicLong();
        CountDownLatch through = new CountDownLatch(1);
        Thread held = start("held", () -> {
            long cpuBefore = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
            step(gate, new Node());
            cpu.set(ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpuBefore);
            flagAfter.set(Thread.currentThread().isInterrupted());
            through.countDown();
        });
        awaitState(held, Thread.State.TIMED_WAITING);
        held.interrupt();
        for (int i = 0; i < 20; i++) { // and again, and again
            Thread.sleep(25);
            held.interrupt();
        }
        assertEquals(1, through.getCount(), "an interrupt does not get a thread through the gate");
        gate.command("resume");
        assertTrue(through.await(5, TimeUnit.SECONDS));
        assertTrue(flagAfter.get(), "the flag is the program's and it is back");
        assertTrue(cpu.get() < 200 * MS, "half a second of being held cost " + cpu.get() / MS + " ms of CPU: it spins");
    }

    @Test
    void aThreadThatWasNotInterruptedComesOutWithoutAFlag() {
        Pace gate = gate(10 * MS, false);
        Node node = new Node();
        step(gate, node);
        step(gate, node);
        assertFalse(Thread.currentThread().isInterrupted(), "no interrupt is invented");
    }

    @Test
    void theHoldDoesNotEatAnUnparkThatCameBeforeIt() throws Exception {
        parkPermitSurvives(Thread.ofPlatform());
    }

    @Test
    void theHoldDoesNotEatAnUnparkOfAVirtualThread() throws Exception {
        parkPermitSurvives(Thread.ofVirtual());
    }

    /**
     * The reason the gate sleeps and does not park: a thread is unparked, then held inside the hook of its own
     * park(), then parks. With the permit gone it would never wake up.
     */
    private void parkPermitSurvives(Thread.Builder builder) throws Exception {
        Pace gate = gate(60 * MS, false);
        AtomicLong parkedFor = new AtomicLong(-1);
        Thread thread = builder.unstarted(() -> {
            Node node = new Node();
            step(gate, node);
            LockSupport.unpark(Thread.currentThread());
            step(gate, node); // held for the interval
            long before = System.nanoTime();
            LockSupport.parkNanos(20_000 * MS);
            parkedFor.set(System.nanoTime() - before);
        });
        threads.add(thread);
        thread.start();
        thread.join(30_000);
        assertTrue(parkedFor.get() >= 0 && parkedFor.get() < 5_000 * MS, "the park that followed the hold waited " + parkedFor.get() / MS + " ms for an unpark that had been made");
    }

    @Test
    void anUnparkMadeWhileHeldIsNotLostEither() throws Exception {
        Pace gate = gate(0, true);
        AtomicLong parkedFor = new AtomicLong(-1);
        Thread thread = start("parker", () -> {
            step(gate, new Node());
            long before = System.nanoTime();
            LockSupport.parkNanos(20_000 * MS);
            parkedFor.set(System.nanoTime() - before);
        });
        awaitState(thread, Thread.State.TIMED_WAITING);
        LockSupport.unpark(thread);
        Thread.sleep(50);
        gate.command("resume");
        thread.join(30_000);
        assertTrue(parkedFor.get() >= 0 && parkedFor.get() < 5_000 * MS, "parked for " + parkedFor.get() / MS + " ms");
    }

    @Test
    void aHeldThreadThatOwnsAMonitorOthersWantIsJustASlowThread() throws Exception {
        Pace gate = gate(0, true);
        Object monitor = new Object();
        CountDownLatch owns = new CountDownLatch(1), done = new CountDownLatch(2);
        start("owner", () -> {
            synchronized (monitor) {
                owns.countDown();
                step(gate, new Node()); // held inside the monitor
            }
            done.countDown();
        });
        assertTrue(owns.await(5, TimeUnit.SECONDS));
        Thread waiter = start("waiter", () -> {
            synchronized (monitor) {
                step(gate, new Node());
            }
            done.countDown();
        });
        awaitState(waiter, Thread.State.BLOCKED);
        gate.command("step 1"); // the owner's release depends on the clock and the setting, never on the waiter
        assertFalse(done.await(100, TimeUnit.MILLISECONDS));
        gate.command("step 1");
        assertTrue(done.await(5, TimeUnit.SECONDS), "deadlock");
    }

    @Test
    void virtualThreadsAreHeldAndReleasedPinnedOrNot() throws Exception {
        Pace gate = gate(0, true);
        int count = 50;
        CountDownLatch done = new CountDownLatch(count);
        Object monitor = new Object();
        for (int i = 0; i < count; i++) {
            boolean pinned = i % 2 == 0;
            Thread thread = Thread.ofVirtual().unstarted(() -> {
                if (pinned) {
                    synchronized (new Object()) {
                        step(gate, new Node());
                    }
                } else {
                    step(gate, new Node());
                }
                synchronized (monitor) {
                    done.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }
        assertFalse(done.await(200, TimeUnit.MILLISECONDS));
        assertEquals(count, done.getCount(), "more virtual threads than carriers, and all of them held");
        gate.command("step 20");
        Thread.sleep(300);
        assertEquals(count - 20, done.getCount());
        gate.command("resume");
        assertTrue(done.await(20, TimeUnit.SECONDS));
    }

    @Test
    void theHoldThrowsNothingWhateverItIsGiven() {
        Pace gate = gate(1, false);
        ThreadState ts = ThreadState.current();
        gate.pass(ts, null, new Node(), null); // no unit: the node stands in
        Pace.settle(ts);
        gate.pass(ts, null, null, null);       // a hook's bug must not become the program's exception
        Pace.settle(ts);
        assertFalse(ts.awaited);
    }

    // ------------------------------------------------------------------ nodes by id

    @Test
    void aNodeThatIsGoneIsForgotten() throws Exception {
        Pace gate = gate(0, false);
        long id = ((Callable<Long>) () -> known(gate, null).id).call();
        for (int i = 0; i < 50 && gate.node(id) != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(gate.node(id));
        assertTrue(gate.command("pause " + id));
        assertTrue(gate.isOpen());
        known(gate, null); // registering is when the collected are swept out; nothing to assert but that it does not throw
    }

    @Test
    void aJvmWithAGateKeepsTimePerNodeAndKnowsNodesById() {
        Node node = new Node();
        assertNotNull(node.lastStep);
        assertSame(node, Pace.GATE.node(node.id));
    }

    // ------------------------------------------------------------------ the agent's options

    @Test
    void paceOptions() {
        AgentConfig defaults = AgentConfig.parse("");
        assertTrue(defaults.pace && defaults.live && defaults.hasGate());
        assertFalse(defaults.pacePaused);
        assertEquals(0, defaults.paceIntervalNanos);

        assertFalse(AgentConfig.parse("pace=false").hasGate(), "switched off, there is no gate");
        assertFalse(AgentConfig.parse("pace=false,pace.events.per.second=5,pace.paused=true").hasGate());
        assertEquals(0, AgentConfig.parse("pace=false,pace.events.per.second=5").paceIntervalNanos);
        assertFalse(AgentConfig.parse("live=false").hasGate(), "nobody could ever send a command and nothing is configured");
        assertTrue(AgentConfig.parse("live=false,pace.events.per.second=5").hasGate(), "a configured pace holds without a socket");

        assertEquals(1_000_000_000, AgentConfig.parse("pace.events.per.second=1").paceIntervalNanos);
        assertEquals(5_000_000_000L, AgentConfig.parse("pace.events.per.second=0.2").paceIntervalNanos);
        assertEquals(1, AgentConfig.parse("pace.events.per.second=1e12").paceIntervalNanos, "never rounded down to no limit");
        assertEquals(Pace.MAX_INTERVAL_NANOS, AgentConfig.parse("pace.events.per.second=1e-30").paceIntervalNanos);
        for (String unlimited : new String[] {"unlimited", "UNLIMITED", " unlimited ", ""}) {
            AgentConfig config = AgentConfig.parse("pace.events.per.second=" + unlimited);
            assertEquals(0, config.paceIntervalNanos);
            assertTrue(config.problems.isEmpty(), config.problems.toString());
        }
        for (String bad : new String[] {"0", "-3", "fast", "NaN", "Infinity", "1e999", "1/2"}) {
            AgentConfig config = AgentConfig.parse("pace.events.per.second=" + bad);
            assertEquals(0, config.paceIntervalNanos, bad);
            assertEquals(1, config.problems.size(), bad);
            assertTrue(config.problems.get(0).contains("pace.events.per.second"), config.problems.get(0));
        }

        assertTrue(AgentConfig.parse("pace.paused=true").pacePaused);
        AgentConfig refused = AgentConfig.parse("pace.paused=true,live=false,pace.events.per.second=2");
        assertFalse(refused.pacePaused, "nothing could ever resume it");
        assertTrue(refused.problems.get(0).contains("pace.paused"), refused.problems.toString());
        assertTrue(refused.hasGate());
        assertTrue(AgentConfig.parse("pace.paused=true,pace=false").problems.isEmpty(), "with pace off, paused is not even looked at");
    }
}
