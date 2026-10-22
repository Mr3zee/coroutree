package kotlinx.coroutree.runtime

import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.PaceDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * The path every event takes out of a hook, in a JVM that traces for real: sequence numbers, what the gate's step
 * leaves on an event, settings of the gate among the events, and the agent's talk about itself.
 */
@Timeout(120)
internal class TracerTest {
    /** A node the gate keeps time for, of nobody's structure. */
    private class Node : PaceNode(IDS.incrementAndGet()) {
        override fun isFinished(): Boolean = false
    }

    @Test
    fun theTraceOfThisJvmStartsWithItsHeaderAndTheSettingItStartedWith() {
        val frames = TraceFiles.read(TracedJvm.TRACE)
        val header = frames[0].header
        assertNotNull(header, "the first frame holds the header")
        assertEquals("unit", header!!.buildId)
        assertEquals(":agent:test", header.taskPath)
        assertEquals(listOf("com.acme"), header.includePackages)
        assertEquals(listOf("com.acme.generated"), header.excludePackages)
        assertEquals(ProcessHandle.current().pid(), header.jvm.pid)
        assertTrue(header.paceable, "no live socket, but a configured pace: the JVM has a gate")

        if (frames.drop(1).any { it.header != null }) throw AssertionError("a second header")
        val first = frames.firstNotNullOfOrNull { it.pace }
        assertNotNull(first)
        assertEquals(
            PaceDef(first!!.timeNanos, 0, 0, TracedJvm.INTERVAL_NANOS, false, 0, PaceDef.Reason.CONFIG, false), first,
            "the first setting is what the run was configured to start with, made before the first event"
        )
        assertThrows<IllegalStateException>("one capture per JVM") { Tracer.start(TracedJvm.CONFIG) }
    }

    @Test
    fun sequenceNumbersAreDenseAndFollowTheOrderOfEmitOnEveryThread() {
        val threads = 8
        val perThread = 2000
        val recording = TracedJvm.record {
            val go = CountDownLatch(1)
            val emitters = (1..threads).map { thread ->
                Thread {
                    val ts = ThreadState.current()
                    go.await()
                    for (i in 1..perThread) {
                        ts.awaited = true // each its own step, through a gate that is not asked here
                        Tracer.emit(ts, TraceEvent(i.toLong(), Wire.RESUMED, thread.toLong()))
                        Pace.settle(ts)
                    }
                }.apply { start() }
            }
            go.countDown()
            for (emitter in emitters) emitter.join()
        }

        // Recording has checked that no number between the first and the last is missing; these are all of them.
        assertEquals(threads * perThread, recording.events.size)
        val lastOfThread = HashMap<Long, Long>()
        var previous = 0L
        for (event in recording.events) {
            if (previous != 0L) assertEquals(previous + 1, event.seq)
            previous = event.seq
            val last = lastOfThread.getOrDefault(event.threadId, 0L)
            assertEquals(last + 1, event.nodeId, "thread ${event.threadId} emitted its events in this order")
            lastOfThread[event.threadId] = event.nodeId
        }
        assertEquals(threads, lastOfThread.size)
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun theEventsOfOneHookCallAreOneStepAndTheHoldIsAccountedOnItsFirstEvent() {
        val node = Node()
        var released = 0L
        var heldBefore = 0L
        val recording = TracedJvm.record("stepper") {
            val ts = ThreadState.current()
            step(node, 2) // the first step of a sequence is not held

            Pace.await(ts, node, node, null) // the second waits out the interval,
            released = ts.releaseNanos
            Thread.sleep(3) // and its hook takes its time to put three events together
            repeat(3) { Tracer.emit(ts, TraceEvent(node.id, Wire.RESUMED, 0)) }
            Pace.settle(ts)

            // A hook call that waited at the gate and then had nothing to report: its time is not lost. (Held right
            // after a step of its sequence it is; tried again should the machine have stalled for the whole interval.)
            var attempt = 0
            while (attempt < 50 && ts.heldNanos == 0L) {
                step(node, 1)
                Pace.await(ts, node, node, null)
                Pace.settle(ts)
                attempt++
            }
            heldBefore = ts.heldNanos
            step(node, 1)
        }

        val events = recording.of(node.id)
        val sameStep = events.map { it.sameStep }
        assertEquals(listOf(false, true, false, true, true), sameStep.subList(0, 5))
        assertFalse(true in sameStep.subList(5, sameStep.size), "every step after that had one event")

        val first = events[0]
        val second = events[2]
        val third = events[5]
        val last = events.last()
        assertTrue(
            second.timeNanos - first.timeNanos >= TracedJvm.INTERVAL_NANOS,
            "two steps of one sequence are at least the interval apart by their times in the trace: ${second.timeNanos - first.timeNanos}"
        )
        assertTrue(third.timeNanos - second.timeNanos >= TracedJvm.INTERVAL_NANOS)
        assertEquals(0, first.heldNanos)
        assertTrue(second.heldNanos > 0, "held for the rest of the interval")
        assertEquals(0, events[1].heldNanos)
        assertEquals(0, events[3].heldNanos, "what was held is said once")
        assertEquals(0, events[4].heldNanos)
        assertTrue(heldBefore > 0, "the step with nothing to report was held like any other")
        assertTrue(last.heldNanos >= heldBefore, "sums per thread are exact: time held for nothing is carried to the thread's next event")
        // The time of a step is the moment the gate let it go, not the later moment its events were put together.
        assertEquals(released, second.timeNanos)
        assertTrue(events[3].timeNanos >= second.timeNanos + 3_000_000, "the others have the time they were emitted at")
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun anEventThatDidNotPassTheGateIsWrittenAndSaidLoudlyOncePerKind() {
        val recording = TracedJvm.record("forgetful") {
            val ts = ThreadState.current()
            // A hook that emits without its await: one the user could not stop the program at.
            Tracer.emit(ts, TraceEvent(77, Wire.CANCELLATION_PROPAGATED, 0))
            Pace.settle(ts)
            Tracer.emit(ts, TraceEvent(77, Wire.CANCELLATION_PROPAGATED, 0))
            Pace.settle(ts)
        }
        assertEquals(2, recording.events.size, "the event itself is not lost")
        assertEquals(EventKind.CANCELLATION_PROPAGATED, recording.events[0].kind)
        assertEquals(1, recording.errors().size, "each distinct problem is reported once: ${recording.errors()}")
        assertTrue("didn't pass the gate" in recording.errors()[0], recording.errors()[0])
    }

    @Test
    fun aSettingOfTheGateTakesNoSequenceNumberAndSaysAfterWhichEventItWasMade() {
        val node = Node()
        var before = 0L
        var after = 0L
        val recording = TracedJvm.record("setter") {
            before = step(node, 1)[0].seq
            Pace.GATE.command("pace 2000000")
            Pace.GATE.command("pace ${TracedJvm.INTERVAL_NANOS}")
            after = step(node, 1)[0].seq
        }
        assertEquals(before + 1, after, "a setting is not something the program did")
        val settings = recording.paces.map { "${it.reason} ${it.intervalNanos} after ${it.afterSeq - before}" }
        assertEquals(listOf("CONTROLLER 2000000 after 0", "CONTROLLER 1000000 after 0"), settings)
        for (pace in recording.paces) assertTrue(pace.timeNanos > 0)
        assertEquals(emptyList<String>(), recording.errors())
    }

    @Test
    fun warmingTheGateUpLeavesNoMarkOnTheRealOneOrInTheTrace() {
        var held = -1L
        val recording = TracedJvm.record("warming-up") {
            // Everything a held thread and a control reader will run, run once: commands, a hold, a step, a controller that leaves.
            Pace.warmUp()
            val ts = ThreadState.current()
            held = ts.heldNanos
            assertFalse(ts.inHook || ts.awaited, "the thread is the program's again")
        }
        assertEquals(emptyList<Any>(), recording.events)
        assertEquals(emptyList<Any>(), recording.paces, "the settings it plays with are those of a gate of its own")
        assertEquals(emptyList<String>(), recording.errors())
        assertEquals(0, held, "its hold is nobody's time")
        assertFalse(Pace.GATE.root.paused)
        assertEquals(TracedJvm.INTERVAL_NANOS, Pace.GATE.root.intervalNanos)
        assertNull(Pace.GATE.node(Long.MAX_VALUE), "its nodes are not nodes of the trace")
    }

    @Test
    fun whatTheAgentSaysAboutItselfIsInTheTraceEachProblemOnceAndNeverThrown() {
        val unprintable = object : RuntimeException() {
            override fun toString(): String = throw IllegalStateException("toString of an application exception")
        }
        val recording = TracedJvm.record {
            Tracer.diagnostic(Wire.WARNING, "said once by a test")
            Tracer.diagnostic(Wire.INFO, "said once by a test")
            repeat(3) { Tracer.reportInternalError("tracerTest", IllegalStateException("the same every time")) }
            Tracer.reportInternalError("tracerTest", IllegalStateException("another"))
            Tracer.reportInternalError("tracerTest, elsewhere", IllegalStateException("the same every time"))
            // This runs in the catch block of every hook: whatever it is given, nothing comes out of it.
            Tracer.reportInternalError("tracerTest", unprintable)
            Tracer.reportInternalError("tracerTest", null)
            Tracer.reportInternalError(null, null)
        }
        val said = recording.diagnostics.map { "${it.severity} ${it.message}" }
        assertEquals(
            listOf(
                "WARNING said once by a test",
                "INFO said once by a test",
                "ERROR internal error: tracerTest: java.lang.IllegalStateException: the same every time",
                "ERROR internal error: tracerTest: java.lang.IllegalStateException: another",
                "ERROR internal error: tracerTest, elsewhere: java.lang.IllegalStateException: the same every time",
                "ERROR internal error: tracerTest: ${unprintable.javaClass.name}"
            ),
            said
        )
    }

    companion object {
        private val IDS = AtomicLong(5_000_000)

        @JvmStatic
        @BeforeAll
        fun traceThisJvm() {
            TracedJvm.start()
        }

        /** What a hook does around its events: pass the gate, emit, settle. */
        private fun step(node: PaceNode, events: Int): List<TraceEvent> {
            val ts = ThreadState.current()
            Pace.await(ts, node, node, null)
            val emitted = List(events) { TraceEvent(node.id, Wire.RESUMED, 0) }
            for (event in emitted) Tracer.emit(ts, event)
            Pace.settle(ts)
            return emitted
        }
    }
}
