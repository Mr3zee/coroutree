package kotlinx.coroutree.runtime

import com.acme.app.App
import kotlinx.coroutines.testfixture.EventLoop
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.Origin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

/**
 * The hooks of threads, called the way instrumented JDK code calls them, in a JVM that traces for real: what comes out
 * is read back from the trace file. (The hooks of coroutines need kotlinx.coroutines instrumented, which takes the
 * agent itself: they are the integration tests'.) The JVM has a gate with a pace, so every scenario also checks that
 * each of its events passed the gate: one that did not is an ERROR in the trace.
 */
@Timeout(120)
internal class HooksTest {
    // ------------------------------------------------------------------ blocking

    @Test
    fun onlyTheOutermostBlockingCallIsReportedAndEveryEnterHasItsExit() {
        val name = name("blocker")
        var depth = -1
        var tid = 0L
        val recording = TracedJvm.record(name) {
            tid = Thread.currentThread().threadId()
            // join waits with wait, which parks: one blocking call to whoever looks at the thread.
            App.blocking(Wire.BLOCK_JOIN) { App.blocking(Wire.BLOCK_WAIT) { App.blocking(Wire.BLOCK_PARK, NOTHING) } }
            try {
                App.blocking(Wire.BLOCK_SLEEP) { throw App.failure("interrupted, say") }
            } catch (leftByException: IllegalStateException) {
                // the exit hook ran on the way out
            }
            depth = ThreadState.current().blockDepth
        }

        val thread = recording.node(name)
        assertEquals(listOf("DISCOVERED", "THREAD_BLOCKED(JOIN)", "THREAD_UNBLOCKED", "THREAD_BLOCKED(SLEEP)", "THREAD_UNBLOCKED"), recording.story(thread.id))
        assertEquals(0, depth)
        assertEquals(emptyList<String>(), recording.errors())

        // A thread whose start was not seen is defined when it is first seen, as what it is.
        assertEquals(NodeKind.THREAD, thread.kind)
        assertEquals(Origin.LIBRARY, thread.origin)
        assertEquals(tid, thread.thread!!.tid)
        assertTrue(thread.thread!!.daemon)
        assertFalse(thread.thread!!.virtual)
        assertEquals(0, thread.parentId)

        val events = recording.of(thread.id)
        val blocked = events[1]
        assertEquals(thread.id, blocked.threadId)
        assertEquals(0, blocked.otherNodeId, "no coroutine was running on it")
        assertEquals("com.acme.app.App.blocking", recording.stack(blocked.stack)[0], "the agent's own frames are not part of a stack")
        assertTrue(blocked.stack.size <= TracedJvm.STACK_DEPTH)

        // All five are steps of one sequence, the thread's, in a JVM paced at one step a millisecond: each is at least
        // that far from the one before, by the times in the trace, and the hold itself is nowhere but in held_nanos.
        for ((i, event) in events.withIndex()) {
            assertFalse(event.sameStep, "one hook call each")
            if (i > 0) {
                val apart = event.timeNanos - events[i - 1].timeNanos
                assertTrue(apart >= TracedJvm.INTERVAL_NANOS, "steps ${i - 1} and $i are $apart ns apart")
            }
        }
        assertTrue(events.sumOf { it.heldNanos } > 0, "the thread was held, and the trace says for how long")
    }

    @Test
    fun aCoroutineRunningOnTheThreadKeepsBlockingBooksOfItsOwn() {
        val name = name("event-loop")
        var coroutineId = 0L
        var depth = -1
        val recording = TracedJvm.record(name) {
            val ts = ThreadState.current()
            // A thread blocked in runBlocking runs a coroutine that sleeps: the two nest, and both are reported.
            App.blocking(Wire.BLOCK_RUN_BLOCKING) {
                val coroutine = JobNode(Tracer.newNodeId(), false, false)
                coroutineId = coroutine.id
                ts.pushUnit(coroutine)
                App.blocking(Wire.BLOCK_SLEEP) { App.blocking(Wire.BLOCK_PARK, NOTHING) }
                ts.popUnit(coroutine)
                App.blocking(Wire.BLOCK_PARK, NOTHING) // the event loop itself, waiting for the next piece of work: still runBlocking
            }
            depth = ts.blockDepth
        }

        val thread = recording.node(name)
        assertEquals(listOf("DISCOVERED", "THREAD_BLOCKED(RUN_BLOCKING)", "THREAD_BLOCKED(SLEEP)", "THREAD_UNBLOCKED", "THREAD_UNBLOCKED"), recording.story(thread.id))
        val runningOnIt = recording.of(thread.id).map { it.otherNodeId }
        assertEquals(listOf(0L, 0L, coroutineId, coroutineId, 0L), runningOnIt, "blocked and unblocked name the coroutine that was running on the thread")
        assertEquals(0, depth)
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun aBlockingCallThatIsNotReportedStillCountsForTheBalance() {
        val name = name("reader")
        val depths = CopyOnWriteArrayList<Int>()
        val recording = TracedJvm.record(name) {
            val ts = ThreadState.current()
            // Reading a file is not blocking; the exit hook of read() cannot tell and comes all the same.
            Hooks.blockEnterIfStdin(ByteArrayInputStream(ByteArray(0)))
            depths.add(ts.blockDepth)
            App.blocking(Wire.BLOCK_PARK, NOTHING) // not the detail of an outer blocking call: there is none
            Hooks.blockExit()
            depths.add(ts.blockDepth)

            // An exit whose enter was before tracing was on (the agent activates in the middle of somebody's sleep).
            Hooks.blockExit()
            depths.add(ts.blockDepth)

            // Standard input blocks for as long as the user likes.
            Hooks.blockEnterIfStdin(FileInputStream(FileDescriptor.`in`))
            Hooks.blockExit()
            depths.add(ts.blockDepth)

            // Something that is not a stream at all, null included: an enter is an enter.
            Hooks.blockEnterIfStdin(null)
            depths.add(ts.blockDepth)
            Hooks.blockExit()
            depths.add(ts.blockDepth)
        }

        assertEquals(listOf(1, 0, 0, 0, 1, 0), depths)
        assertEquals(listOf("DISCOVERED", "THREAD_BLOCKED(PARK)", "THREAD_UNBLOCKED", "THREAD_BLOCKED(IO)", "THREAD_UNBLOCKED"), recording.story(recording.node(name).id))
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun aParkOfTheCoroutineRuntimeIsAnIdleThreadNotABlockedOne() {
        val name = name("dispatcher-worker")
        val depths = CopyOnWriteArrayList<Int>()
        val recording = TracedJvm.record(name) {
            val ts = ThreadState.current()
            EventLoop.parkForWork { depths.add(ts.blockDepth) }
            depths.add(ts.blockDepth)
            App.blocking(Wire.BLOCK_PARK, NOTHING) // the program's own park is one
        }
        assertEquals(listOf(1, 0), depths, "counted, though not reported")
        assertEquals(listOf("DISCOVERED", "THREAD_BLOCKED(PARK)", "THREAD_UNBLOCKED"), recording.story(recording.node(name).id))
        val blocked = recording.events[1]
        assertEquals("com.acme.app.App.blocking", recording.stack(blocked.stack)[0])
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun theAgentChecksAtAThreadsEndThatItsBlockingCallsWereBalanced() {
        assertTrue(Tracer.DEBUG, "the build runs these tests with -Dcoroutree.debug=true, like every JVM of the integration tests")
        val balanced = name("balanced")
        val unbalanced = name("unbalanced")
        val fine = TracedJvm.record(balanced) {
            App.blocking(Wire.BLOCK_SLEEP, NOTHING)
            Hooks.threadExit(Thread.currentThread())
        }
        assertEquals(emptyList<String>(), fine.errors())

        val broken = TracedJvm.record(unbalanced) {
            Hooks.blockEnter(Wire.BLOCK_SLEEP) // and no exit: what a hook that lost its way would leave behind
            Hooks.threadExit(Thread.currentThread())
        }
        assertEquals(1, broken.errors().size, broken.errors().toString())
        assertTrue(unbalanced in broken.errors()[0] && "unbalanced" in broken.errors()[0], broken.errors()[0])
    }

    @Test
    fun aThreadHeldAtAPausedGateShowsNothingOfTheHoldAndKeepsItsInterruptFlag() {
        val name = name("held")
        var flagAfterwards: Boolean? = null
        val recording = TracedJvm.record {
            val gate = Pace.GATE
            gate.command("pause")
            try {
                val thread = Thread({
                    Thread.currentThread().interrupt() // the program's own flag, set before it ever reaches an event
                    App.blocking(Wire.BLOCK_SLEEP, NOTHING)
                    flagAfterwards = Thread.interrupted()
                }, name)
                thread.isDaemon = true
                thread.start()

                awaitHeld(thread) // at its first event: being discovered
                assertEquals(emptyList<String>(), inTheTraceSoFar(name), "nothing happens from a paused program")

                gate.command("step 1")
                val deadline = System.nanoTime() + 20_000_000_000L
                while (inTheTraceSoFar(name).isEmpty() && System.nanoTime() < deadline) Thread.sleep(2)
                awaitHeld(thread) // and at its second: about to block
                assertEquals(listOf("DISCOVERED"), inTheTraceSoFar(name), "one step went through, exactly")

                gate.command("resume")
                thread.join(20_000)
                assertFalse(thread.isAlive)
            } finally {
                gate.command("resume")
            }
        }

        // Exactly what the thread did, as if nobody had stopped it: no state, no blocking, no interrupt of the agent's making.
        val thread = recording.node(name).id
        assertEquals(listOf("DISCOVERED", "THREAD_BLOCKED(SLEEP)", "THREAD_UNBLOCKED"), recording.story(thread))
        assertEquals(3, recording.events.size)
        assertEquals(true, flagAfterwards, "the flag was cleared for the wait and is back")
        // What is recorded is the truth about time and about settings.
        assertTrue(recording.of(thread).sumOf { it.heldNanos } > 0)
        val settings = recording.paces.map { (if (it.paused) "paused" else "running") + (if (it.steps > 0) " +${it.steps}" else "") }
        assertEquals(listOf("paused", "paused +1", "running"), settings.subList(0, 3))
        assertEquals(emptyList<String>(), recording.errors())
    }

    // ------------------------------------------------------------------ the guards every hook has

    @Test
    fun aHookCalledFromInsideAHookSeesAndCountsNothing() {
        // Hooks use the JDK and the JDK is instrumented; and the gate's own sleep comes by blockEnter and blockExit.
        var depth = -1
        val recording = TracedJvm.record(name("in-hook")) {
            val ts = ThreadState.current()
            ts.inHook = true
            try {
                App.blocking(Wire.BLOCK_SLEEP) { depth = ts.blockDepth }
                Hooks.blockEnter(Wire.BLOCK_PARK) // an enter alone, as while the hold sleeps
                if (depth == 0) depth = ts.blockDepth
                App.catches(App.failure("inside"))
                App.interrupt(Thread.currentThread())
                App.start(Thread(NOTHING))
                Hooks.threadUncaught(Thread.currentThread(), App.failure("inside"))
                Hooks.threadExit(Thread.currentThread())
            } finally {
                ts.inHook = false
            }
        }
        assertEquals(0, depth, "enter and exit alike leave the depth alone")
        assertEquals(emptyList<Any>(), recording.events)
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun withTracingOffHooksDoNothingAndAnExitWithoutItsEnterIsHarmless() {
        val name = name("late")
        val depths = CopyOnWriteArrayList<Int>()
        val recording = TracedJvm.record(name) {
            val ts = ThreadState.current()
            Tracer.active = false
            try {
                Hooks.blockEnter(Wire.BLOCK_SLEEP) // asleep while the agent starts
                App.catches(App.failure("unseen"))
                depths.add(ts.blockDepth)
            } finally {
                Tracer.active = true
            }
            Hooks.blockExit() // wakes up traced
            depths.add(ts.blockDepth)
            App.blocking(Wire.BLOCK_SLEEP, NOTHING)
            depths.add(ts.blockDepth)
        }
        assertEquals(listOf(0, 0, 0), depths)
        assertEquals(listOf("DISCOVERED", "THREAD_BLOCKED(SLEEP)", "THREAD_UNBLOCKED"), recording.story(recording.node(name).id))
        assertEquals(3, recording.events.size)
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun theAgentsOwnThreadsAreNotPartOfThePicture() {
        val recording = TracedJvm.record {
            val agent = object : AgentThread("coroutree-test") {
                override fun run() {
                    App.blocking(Wire.BLOCK_PARK, NOTHING)
                    App.catches(App.failure("the agent's own"))
                    Hooks.threadUncaught(this, App.failure("the agent's own"))
                    Hooks.threadExit(this)
                }
            }
            val starter = Thread({
                // Neither its start nor an interrupt of it is anybody's event; and the thread that did both stays unseen.
                App.start(agent)
                App.interrupt(agent)
            }, name("starter-of-agent-thread"))
            starter.start()
            starter.join()
            agent.start()
            agent.join()
        }
        assertEquals(emptyList<Any>(), recording.events)
        assertEquals(emptyList<String>(), recording.errors())
    }

    /** Whatever a hook is handed: it returns, it throws nothing, and it leaves the thread as it found it. */
    @Test
    fun aHookNeverThrowsIntoTheProgramWhateverItIsGiven() {
        // Not Kotlin's and not kotlinx.coroutines' objects, where the hooks expect them; and null, which no JVM passes.
        val stray = object {
            override fun toString(): String = throw IllegalStateException("toString of an application object")
        }
        val hostile = object : RuntimeException() {
            override val message: String
                get() = throw IllegalStateException("getMessage of an application exception")

            override fun toString(): String = throw IllegalStateException("toString of an application exception")

            override fun getStackTrace(): Array<StackTraceElement> = throw IllegalStateException("getStackTrace of an application exception")
        }
        val calls = linkedMapOf<String, () -> Unit>(
            "coroutineCreated" to { Hooks.coroutineCreated(stray, stray, stray) },
            "coroutineCreated(null)" to { Hooks.coroutineCreated(null, null, null) },
            "jobCreated" to { Hooks.jobCreated(stray, stray) },
            "coroutineResumed" to { Hooks.coroutineResumed(stray, stray) },
            "coroutineResumed(null)" to { Hooks.coroutineResumed(null, null) },
            "coroutineSuspended" to { Hooks.coroutineSuspended(stray, stray) },
            "continuationCreated" to { Hooks.continuationCreated(stray) },
            "continuationCompleted" to { Hooks.continuationCompleted(stray, stray, stray) },
            "cancelRequested" to { Hooks.cancelRequested(stray, hostile) },
            "parentCancelled" to { Hooks.parentCancelled(stray, stray) },
            "cancelling" to { Hooks.cancelling(stray, hostile) },
            "completing" to { Hooks.completing(stray, stray) },
            "completed" to { Hooks.completed(stray, stray) },
            "childCancelled" to { Hooks.childCancelled(stray, stray, hostile) },
            "childCancelledResult" to { Hooks.childCancelledResult(false, stray, stray, hostile) },
            "exceptionReachedHandler" to { Hooks.exceptionReachedHandler(stray, hostile) },
            "exceptionReachedHandler(null)" to { Hooks.exceptionReachedHandler(null, null) },
            "exceptionCaught" to { Hooks.exceptionCaught(hostile) },
            "exceptionCaught(null)" to { Hooks.exceptionCaught(null) },
            "threadStart(null)" to { Hooks.threadStart(null) },
            "threadInterrupt(null)" to { Hooks.threadInterrupt(null) },
            "threadUncaught" to { Hooks.threadUncaught(Thread.currentThread(), hostile) },
            "threadUncaught(null)" to { Hooks.threadUncaught(null, null) },
            "threadExit(null)" to { Hooks.threadExit(null) },
        )

        val name = name("hostile")
        val problems = CopyOnWriteArrayList<String>()
        val recording = TracedJvm.record(name) {
            val ts = ThreadState.current()
            for ((what, call) in calls) {
                try {
                    call()
                } catch (e: Throwable) {
                    problems.add("$what threw $e")
                }
                if (ts.inHook) problems.add("$what left the thread inside a hook: every later hook on it would be blind")
                if (ts.blockDepth != 0) problems.add("$what left a blocking call open")
                if (ts.awaited) problems.add("$what left its step at the gate unsettled")
                ts.inHook = false
                ts.blockDepth = 0
            }
        }
        assertEquals(emptyList<String>(), problems)

        // What could be said was said: the exception whose every method throws is still an exception that was caught
        // and one the thread died of, by its class.
        val thread = recording.node(name).id
        val classes = recording.of(thread).mapNotNull { event ->
            event.exception?.let { "${event.kind} ${it.className} '${it.message}'" }
        }
        val hostileClass = hostile.javaClass.name
        assertEquals(
            listOf("EXCEPTION_HANDLED $hostileClass ''", "EXCEPTION_THROWN $hostileClass ''", "EXCEPTION_HANDLED $hostileClass ''"),
            classes
        )
    }

    // ------------------------------------------------------------------ threads: lifecycle

    @Test
    fun aThreadStartedUnderObservationIsLaunchedOnceByItsStarterAndFinishedOnceByItself() {
        val starterName = name("starter")
        val childName = name("child")
        var childTid = 0L
        val recording = TracedJvm.record(starterName) {
            val child = Thread({
                App.blocking(Wire.BLOCK_SLEEP, NOTHING)
                Hooks.threadExit(Thread.currentThread())
                Hooks.threadExit(Thread.currentThread()) // however often the JVM comes by
            }, childName)
            child.isDaemon = false
            childTid = child.threadId()
            App.start(child)
            App.start(child) // a virtual thread passes through two instrumented start methods
            child.start()
            child.join()
        }

        val starter = recording.node(starterName)
        val child = recording.node(childName)
        assertEquals(listOf("DISCOVERED"), recording.story(starter.id))
        assertEquals(listOf("LAUNCHED", "THREAD_BLOCKED(SLEEP)", "THREAD_UNBLOCKED", "FINISHED(COMPLETED)"), recording.story(child.id))
        assertEquals(emptyList<String>(), recording.errors())

        assertEquals(NodeKind.THREAD, child.kind)
        assertEquals(starter.id, child.parentId, "the structural parent of a thread is the thread that started it")
        assertEquals(starter.id, child.creatorId)
        assertEquals("java.lang.Thread", child.implClass)
        assertEquals(childTid, child.thread!!.tid)
        assertFalse(child.thread!!.daemon)
        assertEquals("com.acme.app.App", recording.stackFrames[child.siteFrame]!!.className, "the innermost frame outside the runtimes")

        val events = recording.of(child.id)
        assertEquals(starter.id, events[0].threadId, "launched: captured on the starting thread")
        assertEquals(child.id, events[3].threadId, "finished: on the thread that ends")
    }

    @Test
    fun aThreadInterruptedBeforeItIsStartedIsReferredToFirstAndDefinedByItsStart() {
        val starterName = name("starter")
        val lateName = name("interrupted-before-start")
        val recording = TracedJvm.record(starterName) {
            val late = Thread(NOTHING, lateName)
            App.interrupt(late) // legal, and it sets the flag the thread starts with
            App.start(late)
        }

        val starter = recording.node(starterName)
        val late = recording.node(lateName)
        val events = recording.of(late.id)
        assertEquals(listOf("THREAD_INTERRUPTED", "LAUNCHED"), recording.story(late.id), "one node, no second definition")
        assertNull(events[0].node)
        assertEquals(starter.id, events[0].otherNodeId, "the interrupter")
        assertEquals("com.acme.app.App.interrupt", recording.stack(events[0].stack)[0])
        assertNotNull(events[1].node)
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun aRunningThreadNobodySawStartIsDiscoveredByWhoeverTouchesItFirst() {
        val interrupterName = name("interrupter")
        val bystanderName = name("bystander")
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bystander = Thread({
            running.countDown()
            try {
                release.await()
            } catch (e: InterruptedException) {
                // the test is over
            }
        }, bystanderName)
        bystander.isDaemon = true
        bystander.start() // before tracing is on, and it calls no hook itself
        assertTrue(running.await(10, TimeUnit.SECONDS))
        try {
            val recording = TracedJvm.record(interrupterName) {
                App.interrupt(bystander)
                App.interrupt(bystander)
            }
            val interrupter = recording.node(interrupterName)
            val node = recording.node(bystanderName)
            assertEquals(listOf("DISCOVERED", "THREAD_INTERRUPTED", "THREAD_INTERRUPTED"), recording.story(node.id))
            val discovered = recording.of(node.id)[0]
            assertEquals(interrupter.id, discovered.threadId, "the event was captured on the thread that came across it")
            assertEquals(Origin.LIBRARY, node.origin)
            assertEquals(0, node.parentId, "nobody knows who started it")
            assertEquals(emptyList<String>(), recording.errors())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun aThreadThatDiesOfAnExceptionSaysThrownAndHandledInOneStepAndFinishesFailed() {
        val name = name("dying")
        var thrown: Throwable? = null
        val recording = TracedJvm.record(name) {
            val failure = App.failure("boom")
            thrown = failure
            Hooks.threadUncaught(Thread.currentThread(), failure)
            Hooks.threadExit(Thread.currentThread())
        }

        val thread = recording.node(name).id
        assertEquals(listOf("DISCOVERED", "EXCEPTION_THROWN", "EXCEPTION_HANDLED(UNCAUGHT_EXCEPTION_HANDLER)", "FINISHED(FAILED)"), recording.story(thread))
        val events = recording.of(thread)
        val thrownEvent = events[1]
        val handled = events[2]
        assertEquals("java.lang.IllegalStateException", thrownEvent.exception!!.className)
        assertEquals("boom", thrownEvent.exception!!.message)
        assertFalse(thrownEvent.exception!!.cancellation)
        assertEquals(System.identityHashCode(thrown), thrownEvent.exception!!.identity)
        assertEquals("com.acme.app.App.failure", recording.stack(thrownEvent.exception!!.stack)[0], "where it was thrown")
        assertEquals(thrownEvent.exception!!.identity, handled.exception!!.identity, "one instance, followed across events")
        assertEquals(emptyList<Int>(), handled.exception!!.stack, "the throwable's stack is only on thrown and on a catch")
        assertFalse(thrownEvent.sameStep)
        assertTrue(handled.sameStep, "no program code between the two: one step, never spread out")
        assertEquals(0, handled.heldNanos)
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun aThreadThatNeverDidAnythingTheAgentSawEndsWithoutANode() {
        val recording = TracedJvm.record(name("unseen")) { Hooks.threadExit(Thread.currentThread()) }
        assertEquals(emptyList<Any>(), recording.events)
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun anExceptionCaughtByProjectCodeIsHandledByCatchWithBothStacksCutToTheConfiguredDepth() {
        val name = name("catcher")
        val recording = TracedJvm.record(name) {
            var failure: RuntimeException? = null
            App.deep(40) { failure = App.failure("deep") }
            App.deep(40) { App.catches(failure) }
        }

        val thread = recording.node(name).id
        assertEquals(listOf("DISCOVERED", "EXCEPTION_HANDLED(CATCH)"), recording.story(thread))
        val caught = recording.of(thread)[1]
        val caughtAt = recording.stack(caught.stack)
        val thrownAt = recording.stack(caught.exception!!.stack)
        assertEquals(TracedJvm.STACK_DEPTH, caughtAt.size)
        assertEquals(TracedJvm.STACK_DEPTH, thrownAt.size)
        assertEquals("com.acme.app.App.catches", caughtAt[0])
        assertEquals("com.acme.app.App.failure", thrownAt[0])
        assertEquals(EventKind.EXCEPTION_HANDLED, caught.kind)
        assertEquals("deep", caught.exception!!.message)
        assertEquals(emptyList<String>(), recording.errors())
    }

    // ------------------------------------------------------------------ pools

    @Test
    fun theFirstWorkerOfAPoolBringsThePoolAlongAndEveryWorkerHangsUnderIt() {
        val pool = ForkJoinPool(2)
        val running = CountDownLatch(2)
        val release = CountDownLatch(1)
        val workers = CopyOnWriteArrayList<Thread>()
        repeat(2) {
            pool.execute {
                workers.add(Thread.currentThread())
                running.countDown()
                try {
                    release.await()
                } catch (e: InterruptedException) {
                    // the test is over
                }
            }
        }
        try {
            assertTrue(running.await(10, TimeUnit.SECONDS), "two workers of the pool")
            val starterName = name("pool-user")
            val recording = TracedJvm.record(starterName) {
                for (worker in workers) App.start(worker)
            }

            val starter = recording.node(starterName)
            val launchedNodes = recording.events.filter { it.kind == EventKind.LAUNCHED }.map { event ->
                assertEquals(starter.id, event.threadId)
                event.node!!
            }
            assertEquals(listOf("POOL", "THREAD", "THREAD"), launchedNodes.map { it.kind.name }, "the pool once, before its first worker")
            val poolNode = launchedNodes.single { it.kind == NodeKind.POOL }
            assertEquals("ForkJoinPool", poolNode.name)
            assertEquals("java.util.concurrent.ForkJoinPool", poolNode.implClass)
            assertEquals(Origin.LIBRARY, poolNode.origin)
            assertEquals(0, poolNode.parentId)
            assertEquals(starter.id, poolNode.creatorId)
            assertNull(poolNode.thread)
            for (worker in workers) {
                val node = recording.node(worker.name)
                assertEquals(poolNode.id, node.parentId, "the owning pool, not whichever thread made it grow")
                assertEquals("worker", node.construct)
                assertEquals(Origin.LIBRARY, node.origin)
                assertEquals(0, node.siteFrame, "the line the pool happened to grow at is an accident")
                assertEquals(starter.id, node.creatorId)
            }
            assertEquals(emptyList<String>(), recording.errors())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    companion object {
        private val NOTHING = Runnable {}
        private var names = 0

        @JvmStatic
        @BeforeAll
        fun traceThisJvm() {
            TracedJvm.start()
        }

        private fun name(what: String) = "$what-${++names}"

        // ------------------------------------------------------------------ held at the gate

        private fun awaitHeld(thread: Thread) {
            val deadline = System.nanoTime() + 20_000_000_000L
            while (thread.state != Thread.State.TIMED_WAITING) {
                if (System.nanoTime() > deadline) throw AssertionError("${thread.name} is ${thread.state}, not held")
                Thread.sleep(1)
            }
        }

        /** The kinds of the events of the thread of that name that are in the trace file by now. */
        private fun inTheTraceSoFar(threadName: String): List<String> {
            var id = 0L
            val kinds = ArrayList<String>()
            for (frame in TraceFiles.read(TracedJvm.TRACE)) {
                val event = frame.event ?: continue
                if (event.node?.name == threadName) id = event.nodeId
                if (id != 0L && event.nodeId == id) kinds.add(event.kind.name)
            }
            return kinds
        }
    }
}
