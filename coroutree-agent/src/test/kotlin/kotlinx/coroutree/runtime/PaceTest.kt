package kotlinx.coroutree.runtime

import kotlinx.coroutree.runtime.TraceEvent.PaceDef
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * The gate by itself, without an instrumented program around it:
 * - what holds which thread, and for how long
 * - what a step takes and gives back
 * - what a command does
 * - what the hold leaves untouched in the thread it holds
 *
 * Lower bounds on time are exact, because a pace is a minimum interval. Upper bounds are generous, because they
 * depend on the machine.
 */
@Timeout(60)
internal class PaceTest {
    private val defs: MutableList<PaceDef> = CopyOnWriteArrayList()
    private val threads = ArrayList<Thread>()

    @BeforeEach
    fun setUp() {
        Tracer.active = true
        Tracer.paceSink = defs
    }

    @AfterEach
    fun tearDown() {
        Tracer.active = false // fail open: the threads a failed test left at a gate are released
        for (thread in threads) thread.join(10_000)
        Tracer.paceSink = null
        val ts = ThreadState.current()
        Pace.settle(ts)
        ts.heldNanos = 0
        Thread.interrupted()
    }

    // ------------------------------------------------------------------ fixtures

    private class Node(parent: PaceNode? = null) : PaceNode(IDS.incrementAndGet()) {
        @Volatile
        var finished = false

        init {
            paceParent = parent
        }

        override fun isFinished(): Boolean = finished
    }

    private fun start(name: String, body: () -> Unit): Thread {
        val thread = Thread(body, name)
        thread.isDaemon = true
        threads.add(thread)
        thread.start()
        return thread
    }

    /** A thread that makes one step and counts `through` down after it. */
    private inner class Stepper(gate: Pace, unit: PaceNode, node: PaceNode, emits: Boolean) {
        val through = CountDownLatch(1)
        val held = AtomicLong(-1)
        @Volatile
        var failure: Throwable? = null
        val thread: Thread = start("stepper") {
            try {
                step(gate, unit, node, null, emits)
                held.set(takeHeld())
            } catch (e: Throwable) {
                failure = e
            } finally {
                through.countDown()
            }
        }

        fun isThroughWithin(millis: Long): Boolean = through.await(millis, TimeUnit.MILLISECONDS)

        fun assertHeldFor(millis: Long) {
            assertFalse(isThroughWithin(millis), "the step went through a gate that should hold it")
        }

        fun assertThrough() {
            assertTrue(isThroughWithin(5000), "the step is still held")
            assertNull(failure)
        }
    }

    // ------------------------------------------------------------------ the open gate

    @Test
    fun anOpenGateHoldsNobodyAndKeepsNoBooks() {
        val gate = gate(0, false)
        val node = Node()
        assertTrue(gate.isOpen())
        val ts = ThreadState.current()
        gate.pass(ts, node, node, null)
        assertTrue(ts.awaited, "the hook has passed the gate, which is what emit asks for")
        assertEquals(Pace.NEVER, ts.releaseNanos, "through an open gate an event takes the time of its emit")
        assertEquals(0, ts.claimedSlots)
        Pace.settle(ts)
        assertFalse(ts.awaited)
        assertEquals(Pace.NEVER, node.lastStep.get())
        assertEquals(0, takeHeld())
    }

    @Test
    fun aGateIsOpenOnlyWithNothingSetAnywhere() {
        val gate = gate(0, false)
        val node = known(gate, null)
        assertTrue(gate.isOpen())
        gate.command("pace 0 ${node.id}") // a setting that holds nobody is a setting still: the subtree no longer follows the global one
        assertFalse(gate.isOpen())
        gate.command("inherit ${node.id}")
        assertTrue(gate.isOpen())
        gate.command("pace 5")
        assertFalse(gate.isOpen())
        gate.command("pace 0")
        assertTrue(gate.isOpen())
        gate.command("pause")
        assertFalse(gate.isOpen())
        gate.command("resume")
        assertTrue(gate.isOpen())
    }

    // ------------------------------------------------------------------ a pace

    @Test
    fun theFirstStepOfASequenceIsNotHeld() {
        val gate = gate(200 * MS, false)
        val before = System.nanoTime()
        step(gate, Node())
        assertTrue(System.nanoTime() - before < 150 * MS)
        assertEquals(0, takeHeld())
    }

    @Test
    fun stepsOfOneSequenceAreAnIntervalApartExactly() {
        val interval = 20 * MS
        val gate = gate(interval, false)
        val node = Node()
        var previous = step(gate, node)
        repeat(10) {
            val release = step(gate, node)
            assertTrue(release - previous >= interval, "released ${release - previous} ns after the step before")
            assertTrue(release - previous < interval + 500 * MS)
            assertEquals(release, node.lastStep.get(), "the time of release is the time the next step is measured from")
            previous = release
        }
        val held = takeHeld()
        assertTrue(held >= 10 * interval - 10 * MS && held < 10 * interval + 2000 * MS, "held for $held")
    }

    @Test
    fun theIntervalIsAMinimumNotASurcharge() {
        val gate = gate(30 * MS, false)
        val node = Node()
        step(gate, node)
        takeHeld()
        Thread.sleep(60) // the program's own blocking counts
        val before = System.nanoTime()
        step(gate, node)
        assertEquals(0, takeHeld(), "a step that comes later than the pace asks is not held at all")
        assertTrue(System.nanoTime() - before < 25 * MS)
    }

    @Test
    fun partOfTheIntervalThatHasPassedIsNotWaitedAgain() {
        val gate = gate(100 * MS, false)
        val node = Node()
        step(gate, node)
        Thread.sleep(70)
        step(gate, node)
        val held = takeHeld()
        assertTrue(held <= 40 * MS, "held for $held")
    }

    @Test
    fun parallelSequencesAreNotSerialised() {
        val interval = 25 * MS
        val sequences = 8
        val steps = 8
        val gate = gate(interval, false)
        val done = CountDownLatch(sequences)
        val started = System.nanoTime()
        repeat(sequences) { i ->
            val node = Node()
            start("sequence-$i") {
                repeat(steps) { step(gate, node) }
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        val elapsed = System.nanoTime() - started
        assertTrue(elapsed >= (steps - 1) * interval, "each sequence takes its time")
        // Serialized, the sequences take sequences × steps × interval = 1.6 s.
        assertTrue(elapsed < sequences * (steps - 1) * interval / 2, "eight sequences took ${elapsed / MS} ms: they waited for each other")
    }

    @Test
    fun twoCoroutinesOnOneThreadAreNotSpacedAgainstEachOther() {
        val gate = gate(500 * MS, false)
        val first = Node()
        val second = Node()
        val third = Node()
        val before = System.nanoTime()
        step(gate, first)
        step(gate, second) // same thread, another sequence: concurrent counts as parallel
        step(gate, third)
        assertEquals(0, takeHeld())
        assertTrue(System.nanoTime() - before < 400 * MS)
    }

    @Test
    fun theFlowRuleSpacesWhatOneLineOfExecutionDoesToOthers() {
        val interval = 20 * MS
        val gate = gate(interval, false)
        val parent = Node()
        var previous = step(gate, parent)
        repeat(3) { // a loop that launches three children: each is an event on a new node, from the same flow
            val release = step(gate, parent, Node(), null, true)
            assertTrue(release - previous >= interval)
            previous = release
        }
    }

    @Test
    fun theNodeRuleSpacesWhatHappensToOneNodeFromDifferentFlows() {
        val interval = 20 * MS
        val gate = gate(interval, false)
        val child = Node()
        val launched = step(gate, Node(), child, null, true) // launched by its parent,
        val started = step(gate, child, child, null, true) // then started by itself a moment later,
        val cancelled = step(gate, Node(), child, null, true) // then cancelled from outside
        assertTrue(started - launched >= interval)
        assertTrue(cancelled - started >= interval)
    }

    @Test
    fun aSecondNodeOfTheStepIsASequenceToKeepADistanceInToo() {
        val interval = 30 * MS
        val gate = gate(interval, false)
        val scope = Node()
        val caller = Node()
        val lastChild = Node()
        val callerStep = step(gate, caller)
        // The scope ends on the thread of its last child and rethrows its failure to the caller: an event on the caller.
        val completed = step(gate, lastChild, scope, caller, true)
        assertTrue(completed - callerStep >= interval)
        assertEquals(completed, caller.lastStep.get())
        assertEquals(completed, scope.lastStep.get())
        assertEquals(completed, lastChild.lastStep.get())
    }

    @Test
    fun stepsThatShareASequenceKeepTheirDistanceUnderContention() {
        val interval = 2 * MS
        val gate = gate(interval, false)
        val workers = 8
        val stepsEach = 40
        val flows = List(workers) { Node() }
        val shared = listOf(Node(), Node(), Node())
        val log: MutableList<LongArray> = Collections.synchronizedList(ArrayList()) // {release, flow index, shared index}
        val done = CountDownLatch(workers)
        for (worker in 0 until workers) {
            start("contender-$worker") {
                for (s in 0 until stepsEach) {
                    val target = (worker + s) % shared.size
                    val emits = s % 7 != 3 // some steps have nothing to report and give their place back
                    val release = step(gate, flows[worker], shared[target], null, emits)
                    if (emits) log.add(longArrayOf(release, worker.toLong(), target.toLong()))
                }
                done.countDown()
            }
        }
        assertTrue(done.await(50, TimeUnit.SECONDS))
        for (index in 0 until workers + shared.size) {
            val times = log
                .filter { if (index < workers) it[1] == index.toLong() else it[2] == (index - workers).toLong() }
                .map { it[0] }
                .sorted()
            assertTrue(times.size > 1)
            for (i in 1 until times.size) {
                assertTrue(times[i] - times[i - 1] >= interval, "two steps of sequence $index are ${times[i] - times[i - 1]} ns apart")
            }
        }
    }

    // ------------------------------------------------------------------ flows

    @Test
    fun aCalleeIsPartOfItsCallersFlow() {
        val thread = ThreadNode(IDS.incrementAndGet())
        val runBlocking = JobNode(IDS.incrementAndGet(), false, false)
        runBlocking.caller = thread
        val scope = JobNode(IDS.incrementAndGet(), true, false)
        scope.caller = runBlocking
        val launched = JobNode(IDS.incrementAndGet(), false, false)
        assertSame(thread, thread.flow(), "a thread outside any coroutine is a flow too")
        assertSame(thread, runBlocking.flow())
        assertSame(thread, scope.flow())
        assertSame(launched, launched.flow(), "a coroutine that nobody waits for in place is a flow of its own")
    }

    @Test
    fun aFlowIsFoundThroughAnyDepthOfScopes() {
        val root = JobNode(IDS.incrementAndGet(), false, false)
        var node = root
        repeat(200_000) {
            val callee = JobNode(IDS.incrementAndGet(), true, false)
            callee.caller = node
            node = callee
        }
        assertSame(root, node.flow())
    }

    @Test
    fun stepsOfACalleeAndOfItsCallerAreOneSequence() {
        val interval = 20 * MS
        val gate = gate(interval, false)
        val caller = JobNode(IDS.incrementAndGet(), false, false)
        val scope = JobNode(IDS.incrementAndGet(), true, false)
        scope.caller = caller
        val first = step(gate, caller)
        val second = step(gate, scope) // another node, the same flow
        assertTrue(second - first >= interval)
        assertEquals(second, caller.lastStep.get())
    }

    // ------------------------------------------------------------------ giving back

    @Test
    fun aStepWithNothingToReportCostsTheSequenceNothing() {
        val gate = gate(300 * MS, false)
        val node = Node()
        val first = step(gate, node)
        val ts = ThreadState.current()
        node.lastStep.set(first - 400 * MS) // as if the interval had passed
        val before = node.lastStep.get()
        val release = step(gate, node, node, null, false)
        assertTrue(release > before)
        assertEquals(before, node.lastStep.get(), "its place in the sequence is given back")
        assertEquals(0, ts.claimedSlots)
        step(gate, node)
        assertEquals(0, takeHeld(), "and the step that does report is not held for it")
    }

    @Test
    fun aPlaceIsNotGivenBackOverSomebodyElsesClaim() {
        val gate = gate(1, false)
        val node = Node()
        val ts = ThreadState.current()
        gate.pass(ts, node, node, null)
        val later = ts.claimTime + 1000
        node.lastStep.set(later) // another thread claimed the next place after this one
        Pace.settle(ts)
        assertEquals(later, node.lastStep.get())
    }

    @Test
    fun theStepBeforeIsSettledByTheNextAwaitOfTheSameHookCall() {
        val gate = gate(1, false)
        val found = Node()
        val about = Node()
        val ts = ThreadState.current()
        gate.pass(ts, found, found, null) // finding a node reported it: a step of its own
        ts.stepEmitted = true
        val discovered = ts.claimTime
        gate.pass(ts, about, about, null)
        assertFalse(ts.stepEmitted, "the hook's own step starts with a clean slate")
        assertEquals(1, ts.claimedSlots)
        assertEquals(discovered, found.lastStep.get(), "and the step before it keeps its place")
        Pace.settle(ts)
        assertEquals(Pace.NEVER, about.lastStep.get())
    }

    @Test
    fun timeHeldForAStepThatReportedNothingIsCarriedToTheNextEvent() {
        val gate = gate(15 * MS, false)
        val node = Node()
        step(gate, node)
        step(gate, node, node, null, false)
        val carried = ThreadState.current().heldNanos
        assertTrue(carried >= 10 * MS, "held for $carried")
        step(gate, node)
        assertTrue(takeHeld() >= carried, "the thread's account of held time only grows until an event takes it")
    }

    // ------------------------------------------------------------------ pause and step

    @Test
    fun pauseHoldsEveryStepAndResumeLetsThemGo() {
        val gate = gate(0, false)
        gate.command("pause")
        val a = Stepper(gate, Node(), Node(), true)
        val b = Stepper(gate, Node(), Node(), true)
        a.assertHeldFor(150)
        b.assertHeldFor(10)
        gate.command("resume")
        a.assertThrough()
        b.assertThrough()
        assertTrue(a.held.get() >= 140 * MS, "held for ${a.held.get()}")
    }

    @Test
    fun stepLetsExactlyThatManyThrough() {
        val gate = gate(0, true)
        val steppers = List(6) { Stepper(gate, Node(), Node(), true) }
        steppers[0].assertHeldFor(100)
        gate.command("step 3")
        Thread.sleep(300)
        assertEquals(3, steppers.count { it.through.count == 0L }, "parallel sequences have no order to respect, but three are three")
        gate.command("step 1")
        Thread.sleep(200)
        assertEquals(4, steppers.count { it.through.count == 0L })
        gate.command("resume")
        for (stepper in steppers) stepper.assertThrough()
    }

    @Test
    fun aStepWithNothingToReportGivesItsPermitBack() {
        val gate = gate(0, true)
        gate.command("step 1")
        step(gate, Node(), Node(), null, false) // a probe that had nothing to report
        val next = Stepper(gate, Node(), Node(), true)
        next.assertThrough()
        Stepper(gate, Node(), Node(), true).assertHeldFor(100)
    }

    @Test
    fun aPermitHandedBackLateDoesNotOpenAGateThatWasClosedMeanwhile() {
        val gate = gate(0, true)
        gate.command("step 1")
        val ts = ThreadState.current()
        val node = Node()
        gate.pass(ts, node, node, null)
        assertEquals(1, ts.claimedPermits)
        gate.command("pause") // the user changed their mind while the hook was at work
        Pace.settle(ts) // and the hook reports nothing
        assertEquals(0, gate.root.permits.get())
        Stepper(gate, Node(), Node(), true).assertHeldFor(100)
    }

    @Test
    fun quickStepsAddUp() {
        val gate = gate(0, true)
        repeat(5) { gate.command("step 1") }
        assertEquals(5, gate.root.permits.get())
        repeat(5) { step(gate, Node()) }
        assertEquals(0, gate.root.permits.get())
    }

    @Test
    fun stepOfARunningProgramStopsItAfterThatMany() {
        val gate = gate(0, false)
        gate.command("step 2")
        assertTrue(gate.root.paused)
        step(gate, Node())
        step(gate, Node())
        Stepper(gate, Node(), Node(), true).assertHeldFor(100)
    }

    @Test
    fun pauseAndResumeForgetPermits() {
        val gate = gate(0, true)
        gate.command("step 7")
        gate.command("pause")
        assertEquals(0, gate.root.permits.get())
        gate.command("step 7")
        gate.command("resume")
        assertEquals(0, gate.root.permits.get())
        gate.command("pause")
        assertEquals(0, gate.root.permits.get(), "what was left when it was resumed does not come back")
    }

    @Test
    fun steppedStepsStillCountForThePaceThatFollows() {
        val interval = 40 * MS
        val gate = gate(interval, true)
        val node = Node()
        gate.command("step 1")
        val stepped = step(gate, node)
        gate.command("resume")
        val paced = step(gate, node)
        assertTrue(paced - stepped >= interval)
    }

    @Test
    fun aPaceIsNotLookedAtWhilePaused() {
        val gate = gate(10_000 * MS, true)
        val node = Node()
        gate.command("step 2")
        val before = System.nanoTime()
        step(gate, node)
        step(gate, node) // the user asked for the step: it doesn't wait ten seconds
        assertTrue(System.nanoTime() - before < 2000 * MS)
    }

    // ------------------------------------------------------------------ per subtree

    @Test
    fun aSettingOnANodeHoldsForItsSubtreeAndNobodyElse() {
        val gate = gate(0, false)
        val root = known(gate, null)
        val paused = known(gate, root)
        val child = known(gate, paused)
        val grandchild = known(gate, child)
        val sibling = known(gate, root)
        gate.command("pause ${paused.id}")
        val own = Stepper(gate, paused, paused, true)
        val below = Stepper(gate, grandchild, grandchild, true)
        step(gate, sibling)
        step(gate, root)
        assertEquals(0, takeHeld(), "a sibling subtree and the parent run on")
        own.assertHeldFor(100)
        below.assertHeldFor(10)
        gate.command("resume ${paused.id}")
        own.assertThrough()
        below.assertThrough()
    }

    @Test
    fun theInnermostSettingOnTheWayToTheRootWins() {
        val gate = gate(0, false)
        val outer = known(gate, null)
        val inner = known(gate, outer)
        val leaf = known(gate, inner)
        gate.command("pause")
        gate.command("pause ${outer.id}")
        gate.command("resume ${inner.id}") // everything stopped but this subtree
        step(gate, leaf)
        step(gate, inner)
        assertEquals(0, takeHeld())
        Stepper(gate, outer, outer, true).assertHeldFor(100)
        assertSame(gate.scopeOf(leaf), inner.scope)
        assertSame(gate.scopeOf(outer), outer.scope)
        assertSame(gate.scopeOf(Node()), gate.root)
    }

    @Test
    fun aNodesSettingStartsAsWhatGovernsItAndGoesItsOwnWay() {
        val gate = gate(7 * MS, false)
        val parent = known(gate, null)
        val node = known(gate, parent)
        gate.command("pause ${node.id}")
        assertTrue(node.scope.paused)
        assertEquals(7 * MS, node.scope.intervalNanos, "pausing a subtree and resuming it leaves it at the pace it had")
        gate.command("pace 1")
        assertEquals(7 * MS, node.scope.intervalNanos, "independent of the global setting from then on")
        gate.command("inherit ${node.id}")
        assertNull(node.scope)
        assertEquals(1, gate.scopeOf(node).intervalNanos)

        gate.command("pause")
        gate.command("pace 3 ${node.id}")
        assertTrue(node.scope.paused, "a copy of what governs it: a paused program's subtree does not start running because its speed was set")
    }

    @Test
    fun nothingHappensToAPausedSubtreeAndNothingHappensFromIt() {
        val gate = gate(0, false)
        val pausedRoot = known(gate, null)
        val inside = known(gate, pausedRoot)
        val outside = known(gate, null)
        gate.command("pause ${pausedRoot.id}")
        val into = Stepper(gate, outside, inside, true) // a node outside cancels a coroutine inside: held at that event
        val outOf = Stepper(gate, inside, outside, true) // and an event from inside on a node outside is held too
        into.assertHeldFor(100)
        outOf.assertHeldFor(10)
        gate.command("step 2 ${pausedRoot.id}")
        into.assertThrough()
        outOf.assertThrough()
    }

    @Test
    fun theStricterOfTwoPacesGoverns() {
        val gate = gate(0, false)
        val slow = known(gate, null)
        val slower = known(gate, null)
        gate.command("pace ${10 * MS} ${slow.id}")
        gate.command("pace ${40 * MS} ${slower.id}")
        val first = step(gate, slow, slower, null, true)
        val second = step(gate, slow, slower, null, true)
        assertTrue(second - first >= 40 * MS)
    }

    @Test
    fun aStepBetweenTwoPausedSubtreesTakesAPermitOfEach() {
        val gate = gate(0, false)
        val a = known(gate, null)
        val b = known(gate, null)
        gate.command("pause ${a.id}")
        gate.command("pause ${b.id}")
        val between = Stepper(gate, a, b, true)
        gate.command("step 1 ${a.id}")
        between.assertHeldFor(100)
        assertEquals(1, a.scope.permits.get(), "a permit is not kept while the other one is missing")
        gate.command("step 1 ${b.id}")
        between.assertThrough()
        assertEquals(0, a.scope.permits.get())
        assertEquals(0, b.scope.permits.get())
    }

    @Test
    fun stepsThatNeedTheSamePermitsInOppositeOrderDoNotStarveEachOther() {
        val gate = gate(0, false)
        val a = known(gate, null)
        val b = known(gate, null)
        gate.command("pause ${a.id}")
        gate.command("pause ${b.id}")
        repeat(20) {
            val forth = Stepper(gate, a, b, true)
            val back = Stepper(gate, b, a, true)
            gate.command("step 1 ${a.id}")
            gate.command("step 1 ${b.id}")
            Thread.sleep(60)
            assertEquals(1, listOf(forth, back).count { it.through.count == 0L }, "one permit of each lets exactly one of them through")
            gate.command("step 1 ${a.id}")
            gate.command("step 1 ${b.id}")
            forth.assertThrough()
            back.assertThrough()
        }
    }

    @Test
    fun theSettingOfASecondNodeGovernsTheStepToo() {
        val gate = gate(0, false)
        val scope = known(gate, null)
        val lastChild = known(gate, null)
        val caller = known(gate, null)
        gate.command("pause ${caller.id}")
        val through = AtomicBoolean()
        start("completer") {
            step(gate, lastChild, scope, caller, true)
            through.set(true)
        }
        Thread.sleep(100)
        assertFalse(through.get(), "an event lands on the paused caller: held")
        gate.command("resume ${caller.id}")
        Thread.sleep(100)
        assertTrue(through.get())
    }

    @Test
    fun theEndOfANodeLiftsItsSetting() {
        val node = Node() // registered with the JVM's gate, because nodeFinished uses Pace.GATE as a hook does
        Pace.GATE.command("pause ${node.id}")
        assertNotNull(node.scope)
        val below = Stepper(Pace.GATE, Node(node), Node(node), true)
        below.assertHeldFor(100)
        node.finished = true
        Pace.nodeFinished(node)
        assertNull(node.scope)
        below.assertThrough()
        val last = defs.last()
        assertEquals(node.id, last.scopeNodeId)
        assertTrue(last.dropped)
        assertEquals(Wire.PACE_NODE_FINISHED, last.reason)
    }

    @Test
    fun aSettingNeverOutlivesItsNodeHoweverTheTwoRace() {
        val gate = Pace.GATE
        repeat(300) { round ->
            val node = Node()
            val go = CountDownLatch(1)
            val finisher = start("finisher") {
                go.await()
                node.finished = true // what a hook does: first the flag,
                Pace.nodeFinished(node) // then a look at the setting
            }
            go.countDown()
            gate.command("pause ${node.id}")
            finisher.join()
            assertNull(node.scope, "round $round: a node that has ended still carries a setting")
        }
        assertFalse(gate.root.paused)
    }

    // ------------------------------------------------------------------ commands

    @Test
    fun whatIsNotACommandIsIgnoredAndMakesNoController() {
        val gate = gate(0, false)
        val junk = listOf(
            "", " ", "\t", "bogus", "PAUSE", "Pause", "pace", "pace fast", "pace -1", "pace 1.5", "pace 1e3", "pace 0x10", "pace 1 2 3",
            "step", "step 0", "step -1", "step one", "step 1 2 3", "pause 1 2", "pause me", "resume 1 2",
            "inherit", "inherit 0", "inherit x", "inherit 1 2", "pause\u0000", "pace ١٢٣", "pace +5",
            "pace 99999999999999999999999999999999", "pause " + "9".repeat(40)
        )
        for (line in junk) {
            assertFalse(gate.command(line), "'$line' was taken for a command")
        }
        assertTrue(gate.isOpen())
        assertTrue(defs.isEmpty())
    }

    @Test
    fun aCommandAboutANodeNobodyKnowsIsACommandThatDoesNothing() {
        val gate = gate(0, false)
        val ended = known(gate, null)
        ended.finished = true
        assertTrue(gate.command("pause 123456789"))
        assertTrue(gate.command("pause ${ended.id}"))
        assertTrue(gate.command("step 3 ${ended.id}"))
        assertTrue(gate.command("inherit 123456789"))
        assertTrue(gate.command("inherit ${known(gate, null).id}"), "nothing to drop is nothing to do")
        assertNull(ended.scope)
        assertTrue(gate.isOpen())
        assertTrue(defs.isEmpty())
    }

    @Test
    fun commandsAreReadLeniently() {
        val gate = gate(0, false)
        assertTrue(gate.command("  pace \t 5000  "))
        assertEquals(5000, gate.root.intervalNanos)
        assertTrue(gate.command("pause\r"))
        assertTrue(gate.root.paused)
        assertTrue(gate.command("resume 0"), "0 names the global setting")
        assertFalse(gate.root.paused)
        assertTrue(gate.command("pace 0"))
        assertTrue(gate.isOpen())
    }

    @Test
    fun anIntervalIsCutToWhatCanBeAddedToTheClock() {
        val gate = gate(0, false)
        gate.command("pace ${Long.MAX_VALUE}")
        assertEquals(Pace.MAX_INTERVAL_NANOS, gate.root.intervalNanos)
        gate.command("pace 9223372036854775808") // one more than fits
        assertEquals(Pace.MAX_INTERVAL_NANOS, gate.root.intervalNanos)
        val node = Node()
        step(gate, node)
        Stepper(gate, node, node, true).assertHeldFor(100) // and not let through by an overflow
        gate.command("step ${Long.MAX_VALUE}")
        assertEquals(Int.MAX_VALUE.toLong(), gate.root.permits.get())
    }

    @Test
    fun everyChangeIsSaidInTheTraceTheWayItIsNow() {
        val gate = gate(3, false)
        val node = known(gate, null)
        gate.announce()
        gate.command("pace 9")
        gate.command("pause")
        gate.command("step 4")
        gate.command("pace 2 ${node.id}")
        gate.command("inherit ${node.id}")
        gate.command("resume")
        val expected = listOf(
            "0 interval=3 paused=false steps=0 reason=1 dropped=false",
            "0 interval=9 paused=false steps=0 reason=2 dropped=false",
            "0 interval=9 paused=true steps=0 reason=2 dropped=false",
            "0 interval=9 paused=true steps=4 reason=2 dropped=false",
            "${node.id} interval=2 paused=true steps=0 reason=2 dropped=false",
            "${node.id} interval=0 paused=false steps=0 reason=2 dropped=true",
            "0 interval=9 paused=false steps=0 reason=2 dropped=false",
        )
        assertEquals(
            expected,
            defs.map { "${it.scopeNodeId} interval=${it.intervalNanos} paused=${it.paused} steps=${it.steps} reason=${it.reason} dropped=${it.dropped}" }
        )
    }

    @Test
    fun commandsFromManyClientsAtOnceLeaveTheGateInOneOfTheirStates() {
        val gate = gate(0, false)
        val node = known(gate, null)
        val done = CountDownLatch(4)
        for (client in 0 until 4) {
            start("client-$client") {
                for (i in 0 until 2000) {
                    when ((i + client) % 5) {
                        0 -> gate.command("pause ${node.id}")
                        1 -> gate.command("inherit ${node.id}")
                        2 -> gate.command("pace $i")
                        3 -> gate.command("step 1 ${node.id}")
                        else -> gate.command("resume")
                    }
                }
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        gate.command("inherit ${node.id}")
        gate.command("pace 0")
        gate.command("resume")
        assertTrue(gate.isOpen(), "what the commands say and what the gate does are the same thing")
        // The trace matches: the last definition of every scope is the scope's state.
        val lastOfNode = defs.lastOrNull { it.scopeNodeId == node.id }
        assertNotNull(lastOfNode)
        assertTrue(lastOfNode!!.dropped)
    }

    // ------------------------------------------------------------------ controllers, failing open

    @Test
    fun whenTheLastControllerGoesEverythingIsAsConfiguredAndNothingIsPaused() {
        val gate = gate(5, true)
        val node = known(gate, null)
        gate.controllerJoined()
        gate.controllerJoined()
        gate.command("pace 1000")
        gate.command("pause ${node.id}")
        val held = Stepper(gate, node, node, true)
        held.assertHeldFor(100)
        gate.controllerLeft()
        assertTrue(gate.root.paused, "one controller is left")
        assertNotNull(node.scope)
        defs.clear()
        gate.controllerLeft()
        assertFalse(gate.root.paused)
        assertEquals(5, gate.root.intervalNanos, "the configured pace, not none")
        assertNull(node.scope)
        held.assertThrough()
        assertEquals(2, defs.size)
        assertTrue(defs[0].dropped)
        assertEquals(node.id, defs[0].scopeNodeId)
        for (def in defs) assertEquals(Wire.PACE_FAIL_OPEN, def.reason)
        assertEquals(0, defs[1].scopeNodeId)
        assertFalse(defs[1].paused)
    }

    @Test
    fun aProgramThatWasToStartPausedStaysSoUntilThereHasBeenAController() {
        val gate = gate(0, true)
        val first = Stepper(gate, Node(), Node(), true)
        first.assertHeldFor(200) // nobody has connected, nobody has left: it waits, as asked
        gate.controllerJoined()
        gate.command("step 1")
        first.assertThrough()
        gate.controllerLeft() // the GUI crashed
        step(gate, Node())
        assertEquals(0, takeHeld())
    }

    @Test
    fun aControllerThatChangedNothingLeavesNoMark() {
        val gate = gate(0, false)
        gate.controllerJoined()
        gate.controllerLeft()
        assertTrue(defs.isEmpty())
    }

    @Test
    fun withTracingOffEveryHeldThreadGoes() {
        val gate = gate(0, true)
        val node = known(gate, null)
        gate.command("pause ${node.id}")
        val global = Stepper(gate, Node(), Node(), true)
        val subtree = Stepper(gate, node, node, true)
        global.assertHeldFor(100)
        Tracer.active = false // the writer failed, or the JVM is going down
        global.assertThrough()
        subtree.assertThrough()
    }

    @Test
    fun shutdownOpensTheGateAndSaysSo() {
        val gate = gate(9, true)
        val node = known(gate, null)
        gate.command("pause ${node.id}")
        defs.clear()
        gate.shutdown()
        assertTrue(gate.isOpen())
        assertEquals(2, defs.size)
        for (def in defs) assertEquals(Wire.PACE_SHUTDOWN, def.reason)
        assertTrue(defs[0].dropped)
        assertEquals(0, defs[1].intervalNanos)
    }

    @Test
    fun afterShutdownTheGateStaysOpenWhateverIsSent() {
        val gate = gate(0, false)
        val node = known(gate, null)
        gate.controllerJoined()
        gate.shutdown()
        defs.clear()
        assertTrue(gate.command("pause"), "still a command, to a gate that no longer listens")
        gate.command("pause ${node.id}")
        gate.command("step 1")
        gate.command("pace 1000000000")
        gate.controllerLeft()
        gate.shutdown()
        assertTrue(gate.isOpen())
        assertNull(node.scope)
        assertTrue(defs.isEmpty(), "and nothing more is said about it")
        step(gate, node)
        assertEquals(0, takeHeld())
    }

    @Test
    fun aThreadOfTheJvmItselfIsNeverHeld() {
        // The Signal Dispatcher starts the "SIGTERM handler" thread, in the root thread group like itself. Held at a
        // paused gate inside that Thread.start, it never delivers the signal.
        var root = Thread.currentThread().threadGroup
        while (root.parent != null) root = root.parent
        val gate = gate(0, true)
        val through = CountDownLatch(1)
        val marked = AtomicBoolean()
        val system = Thread(root, {
            marked.set(ThreadState.current().neverHeld)
            val node = Node()
            step(gate, node)
            if (node.lastStep.get() == Pace.NEVER) through.countDown() // and it takes no place in any sequence
        }, "like the Signal Dispatcher")
        system.isDaemon = true
        threads.add(system)
        system.start()
        assertTrue(through.await(5, TimeUnit.SECONDS), "a thread of the root group was held")
        assertTrue(marked.get())
        assertFalse(ThreadState.current().neverHeld, "the program's threads are")
        val virtual = Thread.ofVirtual().unstarted { marked.set(ThreadState.current().neverHeld) }
        virtual.start()
        virtual.join()
        assertFalse(marked.get(), "and so are virtual threads, whose group is not the root either")
    }

    @Test
    fun withoutALiveSocketAProgramThatWasToStartPausedRuns() {
        val gate = gate(4, true)
        assertTrue(gate.liveUnavailable())
        assertFalse(gate.root.paused)
        assertEquals(4, gate.root.intervalNanos, "the configured pace needs no socket")
        assertEquals(Wire.PACE_FAIL_OPEN, defs.last().reason)
        assertFalse(gate(4, false).liveUnavailable())
    }

    // ------------------------------------------------------------------ what the hold leaves alone

    @Test
    fun aThreadInterruptedBeforeItIsHeldComesOutWithItsFlagSet() {
        val gate = gate(40 * MS, false)
        val node = Node()
        step(gate, node)
        Thread.currentThread().interrupt()
        val before = System.nanoTime()
        step(gate, node)
        assertTrue(System.nanoTime() - before >= 35 * MS, "the hold is not cut short by the interrupt")
        assertTrue(Thread.interrupted(), "and the interrupt is not lost")
    }

    @Test
    fun aThreadInterruptedWhileHeldStaysHeldBurnsNoCpuAndKeepsItsFlag() {
        val gate = gate(0, true)
        val flagAfter = AtomicBoolean()
        val cpu = AtomicLong()
        val through = CountDownLatch(1)
        val held = start("held") {
            val cpuBefore = ManagementFactory.getThreadMXBean().currentThreadCpuTime
            step(gate, Node())
            cpu.set(ManagementFactory.getThreadMXBean().currentThreadCpuTime - cpuBefore)
            flagAfter.set(Thread.currentThread().isInterrupted)
            through.countDown()
        }
        awaitState(held, Thread.State.TIMED_WAITING)
        held.interrupt()
        repeat(20) {
            Thread.sleep(25)
            held.interrupt()
        }
        assertEquals(1, through.count, "an interrupt does not get a thread through the gate")
        gate.command("resume")
        assertTrue(through.await(5, TimeUnit.SECONDS))
        assertTrue(flagAfter.get(), "the flag is the program's and it is back")
        assertTrue(cpu.get() < 200 * MS, "half a second of being held cost ${cpu.get() / MS} ms of CPU: it spins")
    }

    @Test
    fun aThreadThatWasNotInterruptedComesOutWithoutAFlag() {
        val gate = gate(10 * MS, false)
        val node = Node()
        step(gate, node)
        step(gate, node)
        assertFalse(Thread.currentThread().isInterrupted, "no interrupt is invented")
    }

    @Test
    fun theHoldDoesNotEatAnUnparkThatCameBeforeIt() {
        parkPermitSurvives(Thread.ofPlatform())
    }

    @Test
    fun theHoldDoesNotEatAnUnparkOfAVirtualThread() {
        parkPermitSurvives(Thread.ofVirtual())
    }

    /**
     * The reason the gate sleeps and doesn't park: a thread is unparked, then held inside the hook of its own
     * `park()`, then parks. If the hold used the permit, the thread never wakes up.
     */
    private fun parkPermitSurvives(builder: Thread.Builder) {
        val gate = gate(60 * MS, false)
        val parkedFor = AtomicLong(-1)
        val thread = builder.unstarted {
            val node = Node()
            step(gate, node)
            LockSupport.unpark(Thread.currentThread())
            step(gate, node) // held for the interval
            val before = System.nanoTime()
            LockSupport.parkNanos(20_000 * MS)
            parkedFor.set(System.nanoTime() - before)
        }
        threads.add(thread)
        thread.start()
        thread.join(30_000)
        assertTrue(parkedFor.get() >= 0 && parkedFor.get() < 5000 * MS, "the park that followed the hold waited ${parkedFor.get() / MS} ms for an unpark that had been made")
    }

    @Test
    fun anUnparkMadeWhileHeldIsNotLostEither() {
        val gate = gate(0, true)
        val parkedFor = AtomicLong(-1)
        val thread = start("parker") {
            step(gate, Node())
            val before = System.nanoTime()
            LockSupport.parkNanos(20_000 * MS)
            parkedFor.set(System.nanoTime() - before)
        }
        awaitState(thread, Thread.State.TIMED_WAITING)
        LockSupport.unpark(thread)
        Thread.sleep(50)
        gate.command("resume")
        thread.join(30_000)
        assertTrue(parkedFor.get() >= 0 && parkedFor.get() < 5000 * MS, "parked for ${parkedFor.get() / MS} ms")
    }

    @Test
    fun aHeldThreadThatOwnsAMonitorOthersWantIsJustASlowThread() {
        val gate = gate(0, true)
        val monitor = Any()
        val owns = CountDownLatch(1)
        val done = CountDownLatch(2)
        start("owner") {
            synchronized(monitor) {
                owns.countDown()
                step(gate, Node()) // held inside the monitor
            }
            done.countDown()
        }
        assertTrue(owns.await(5, TimeUnit.SECONDS))
        val waiter = start("waiter") {
            synchronized(monitor) {
                step(gate, Node())
            }
            done.countDown()
        }
        awaitState(waiter, Thread.State.BLOCKED)
        gate.command("step 1") // the owner's release depends on the clock and the setting, never on the waiter
        assertFalse(done.await(100, TimeUnit.MILLISECONDS))
        gate.command("step 1")
        assertTrue(done.await(5, TimeUnit.SECONDS), "deadlock")
    }

    @Test
    fun virtualThreadsAreHeldAndReleasedPinnedOrNot() {
        val gate = gate(0, true)
        val count = 50
        val done = CountDownLatch(count)
        val monitor = Any()
        for (i in 0 until count) {
            val pinned = i % 2 == 0
            val thread = Thread.ofVirtual().unstarted {
                if (pinned) {
                    synchronized(Any()) {
                        step(gate, Node())
                    }
                } else {
                    step(gate, Node())
                }
                synchronized(monitor) {
                    done.countDown()
                }
            }
            threads.add(thread)
            thread.start()
        }
        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        assertEquals(count.toLong(), done.count, "more virtual threads than carriers, and all of them held")
        gate.command("step 20")
        Thread.sleep(300)
        assertEquals((count - 20).toLong(), done.count)
        gate.command("resume")
        assertTrue(done.await(20, TimeUnit.SECONDS))
    }

    @Test
    fun theHoldThrowsNothingWhateverItIsGiven() {
        val gate = gate(1, false)
        val ts = ThreadState.current()
        gate.pass(ts, null, Node(), null) // no unit: the node is the unit
        Pace.settle(ts)
        gate.pass(ts, null, null, null) // a hook's bug must not become the program's exception
        Pace.settle(ts)
        assertFalse(ts.awaited)
    }

    // ------------------------------------------------------------------ nodes by id

    @Test
    fun aNodeThatIsGoneIsForgotten() {
        val gate = gate(0, false)
        val id = idOfANodeNobodyHolds(gate)
        var i = 0
        while (i < 50 && gate.node(id) != null) {
            System.gc()
            Thread.sleep(20)
            i++
        }
        assertNull(gate.node(id))
        assertTrue(gate.command("pause $id"))
        assertTrue(gate.isOpen())
        known(gate, null) // registering is when collected references are removed, and the only assertion is that it doesn't throw
    }

    /** A method of its own, so the node doesn't survive in a local of the caller's frame. */
    private fun idOfANodeNobodyHolds(gate: Pace): Long = known(gate, null).id

    @Test
    fun aJvmWithAGateKeepsTimePerNodeAndKnowsNodesById() {
        val node = Node()
        assertNotNull(node.lastStep)
        assertSame(node, Pace.GATE.node(node.id))
    }

    // ------------------------------------------------------------------ the agent's options

    @Test
    fun paceOptions() {
        val defaults = AgentConfig.parse("")
        assertTrue(defaults.pace && defaults.live && defaults.hasGate())
        assertFalse(defaults.pacePaused)
        assertEquals(0, defaults.paceIntervalNanos)

        assertFalse(AgentConfig.parse("pace=false").hasGate(), "switched off, there is no gate")
        assertFalse(AgentConfig.parse("pace=false,pace.events.per.second=5,pace.paused=true").hasGate())
        assertEquals(0, AgentConfig.parse("pace=false,pace.events.per.second=5").paceIntervalNanos)
        assertFalse(AgentConfig.parse("live=false").hasGate(), "nobody could ever send a command and nothing is configured")
        assertTrue(AgentConfig.parse("live=false,pace.events.per.second=5").hasGate(), "a configured pace holds without a socket")

        assertEquals(1_000_000_000, AgentConfig.parse("pace.events.per.second=1").paceIntervalNanos)
        assertEquals(5_000_000_000L, AgentConfig.parse("pace.events.per.second=0.2").paceIntervalNanos)
        assertEquals(1, AgentConfig.parse("pace.events.per.second=1e12").paceIntervalNanos, "never rounded down to no limit")
        assertEquals(Pace.MAX_INTERVAL_NANOS, AgentConfig.parse("pace.events.per.second=1e-30").paceIntervalNanos)
        for (unlimited in listOf("unlimited", "UNLIMITED", " unlimited ", "")) {
            val config = AgentConfig.parse("pace.events.per.second=$unlimited")
            assertEquals(0, config.paceIntervalNanos)
            assertTrue(config.problems.isEmpty(), config.problems.toString())
        }
        for (bad in listOf("0", "-3", "fast", "NaN", "Infinity", "1e999", "1/2")) {
            val config = AgentConfig.parse("pace.events.per.second=$bad")
            assertEquals(0, config.paceIntervalNanos, bad)
            assertEquals(1, config.problems.size, bad)
            assertTrue("pace.events.per.second" in config.problems[0], config.problems[0])
        }

        assertTrue(AgentConfig.parse("pace.paused=true").pacePaused)
        val refused = AgentConfig.parse("pace.paused=true,live=false,pace.events.per.second=2")
        assertFalse(refused.pacePaused, "nothing could ever resume it")
        assertTrue("pace.paused" in refused.problems[0], refused.problems.toString())
        assertTrue(refused.hasGate())
        assertTrue(AgentConfig.parse("pace.paused=true,pace=false").problems.isEmpty(), "with pace off, paused is not even looked at")
    }

    companion object {
        init {
            // The gate of a JVM is made from `Tracer.config` when Pace loads, and nodes have a `lastStep` only in a JVM that has one.
            Tracer.config = AgentConfig.parse("live=false,pace.events.per.second=1000")
        }

        private const val MS = 1_000_000L
        private val IDS = AtomicLong(1000)

        @JvmStatic
        @BeforeAll
        fun theJvmHasAGate() {
            assertNotNull(Pace.GATE, "PaceTest must be the one to initialise Pace")
        }

        private fun gate(intervalNanos: Long, paused: Boolean) = Pace(intervalNanos, paused, true)

        /** A node registered with the gate by id, as every node of a traced program is with the real one. */
        private fun known(gate: Pace, parent: PaceNode?): Node {
            val node = Node(parent)
            gate.register(node)
            return node
        }

        /** What a hook call does with the gate: pass, emit or not, settle. Returns the time of release. */
        private fun step(gate: Pace, unit: PaceNode?, node: PaceNode, other: PaceNode?, emits: Boolean): Long {
            val ts = ThreadState.current()
            gate.pass(ts, unit, node, other)
            val release = ts.releaseNanos
            if (emits) ts.stepEmitted = true // what Tracer.emit sets
            Pace.settle(ts)
            return release
        }

        private fun step(gate: Pace, node: PaceNode): Long = step(gate, node, node, null, true)

        private fun takeHeld(): Long {
            val ts = ThreadState.current()
            val held = ts.heldNanos
            ts.heldNanos = 0
            return held
        }

        private fun awaitState(thread: Thread, state: Thread.State) {
            val deadline = System.nanoTime() + 5000 * MS
            while (thread.state != state && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(state, thread.state)
        }
    }
}
