package kotlinx.coroutree.gui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutree.gui.demo.DemoGate
import kotlinx.coroutree.gui.demo.DemoTrace
import kotlinx.coroutree.gui.view.PaceCommand
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.NodeInfo
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.TraceReader
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The demo is what screenshots, UI tests and `--demo` show, so it has to be a trace an agent could have written, and
 * its stand-in gate has to answer commands the way the agent's does (DESIGN §12, "M1.2 as built", row "settings"),
 * or what is tried and tested on the demo says nothing about the real thing.
 */
class DemoTraceTest {
    private val frames = DemoTrace.frames()

    /** docs/TRACE_FORMAT.md: "The first frame holds the `header`." */
    @Test
    fun theDemoBeginsWithItsHeaderAsEveryTraceDoes() {
        assertNotNull(frames.first().header, "the first frame of the demo is ${frames.first()}")
    }

    @Test
    fun theDemoIsATraceAnAgentCouldHaveWritten() {
        assertEquals(1, frames.count { it.header != null })
        assertTrue(frames.all { listOfNotNull(it.header, it.event, it.stackFrame, it.diagnostic, it.pace).size == 1 }, "a frame is one thing")

        val definedFrames = HashSet<Int>()
        val nodes = HashMap<Long, NodeInfo>()
        var lastSeq = 0L
        var lastTime = 0L
        for (frame in frames) {
            frame.stackFrame?.let { assertTrue(it.id > 0 && definedFrames.add(it.id), "stack frame ${it.id} is defined once") }
            frame.pace?.let { pace ->
                assertEquals(lastSeq, pace.afterSeq, "a change of the gate says after which event it was made")
                assertTrue(pace.scopeNodeId == 0L || pace.scopeNodeId in nodes, "a setting is about the program or a node there is")
                assertTrue(pace.timeNanos >= lastTime)
            }
            val event = frame.event ?: continue
            val what = "event ${event.seq} (${event.kind})"
            assertEquals(lastSeq + 1, event.seq, "sequence numbers are dense")
            assertTrue(event.timeNanos >= lastTime, "$what: time does not go back")
            lastSeq = event.seq
            lastTime = event.timeNanos
            val definition = event.node
            if (definition != null) {
                assertTrue(event.kind == EventKind.LAUNCHED || event.kind == EventKind.DISCOVERED, "$what defines a node")
                assertEquals(event.nodeId, definition.id, what)
                assertNull(nodes.put(definition.id, definition), "$what: node ${definition.id} is defined once")
                assertTrue(definition.parentId == 0L || definition.parentId in nodes, "$what: its parent is there")
                assertTrue(definition.creatorId == 0L || definition.creatorId in nodes, "$what: its creator is there")
                assertTrue(definition.siteFrame == 0 || definition.siteFrame in definedFrames, "$what: its site is a known frame")
            }
            assertTrue(event.nodeId in nodes, "$what happens to a node that is defined")
            assertTrue(event.otherNodeId == 0L || event.otherNodeId in nodes, "$what names a node that is defined")
            assertTrue(event.threadId == 0L || nodes[event.threadId]?.kind == NodeKind.THREAD, "$what happens on a thread")
            assertTrue(definedFrames.containsAll(event.stack + event.exception?.stack.orEmpty()), "$what: frames are defined before they are used")
        }

        val snapshot = snapshotOf(frames)
        assertEquals(lastSeq.toInt(), snapshot.events.size, "nothing was held back or dropped")
        assertTrue(snapshot.nodes.values.none { it.placeholder })
        assertEquals(frames, TraceReader(ByteArrayInputStream(traceBytes(frames))).use { it.frames().toList() }, "it survives the file format unchanged")
    }

    /** DemoTrace: "touches everything the GUI can show" — what its KDoc lists is what UI tests and screenshots rely on. */
    @Test
    fun theDemoHasWhatItSaysItHas() {
        val snapshot = snapshotOf(frames)
        val nodes = snapshot.nodes.values
        assertTrue(nodes.map { it.info.kind }.containsAll(listOf(NodeKind.THREAD, NodeKind.POOL, NodeKind.COROUTINE, NodeKind.SCOPE, NodeKind.CONTEXT_CHANGE)))
        assertTrue(nodes.map { it.state }.containsAll(listOf(NodeState.COMPLETED, NodeState.FAILED, NodeState.CANCELLED, NodeState.SUSPENDED, NodeState.BLOCKED, NodeState.ACTIVE)))
        assertTrue(nodes.any { it.info.origin == Origin.LIBRARY } && nodes.any { it.info.origin == Origin.PROJECT })
        assertTrue(snapshot.roots.size > 1, "unstructured coroutines are roots of their own")
        val kinds = snapshot.events.map { it.kind }.toSet()
        val told = listOf(
            EventKind.DISCOVERED, EventKind.LAUNCHED, EventKind.RESUMED, EventKind.SUSPENDED, EventKind.FINISHED, EventKind.DISPATCHER_CHANGED, EventKind.CONTEXT_CHANGED,
            EventKind.EXCEPTION_THROWN, EventKind.EXCEPTION_PROPAGATED, EventKind.EXCEPTION_HANDLED, EventKind.CANCELLING, EventKind.CANCELLATION_REQUESTED,
            EventKind.CANCELLATION_PROPAGATED, EventKind.THREAD_BLOCKED, EventKind.THREAD_UNBLOCKED, EventKind.THREAD_INTERRUPTED,
        )
        assertEquals(emptyList(), told - kinds, "kinds of events the story is said to tell")
        assertTrue(snapshot.diagnostics.isNotEmpty(), "an agent diagnostic")
        assertTrue(snapshot.paceChanges.any { it.paused } && snapshot.paceChanges.any { it.steps > 0 } && snapshot.pace.nodes.isNotEmpty(), "paused, stepped, and one subtree still slowed down at the end")
    }

    /** "Open in IDE" is shown on the demo: project frames lead to a source file, library and JDK frames do not. */
    @Test
    fun framesOfTheDemoProjectResolveToItsSourcesAndNoOthersDo() {
        val snapshot = snapshotOf(frames)
        val defined = frames.mapNotNull { it.stackFrame }
        assertTrue(defined.any { it.className.startsWith("demo.shop.") } && defined.any { !it.className.startsWith("demo.shop.") })
        for (frame in defined) {
            val location = snapshot.sources.resolve(frame)
            if (frame.className.startsWith("demo.shop.")) {
                assertEquals("/demo/shop/src/main/kotlin/demo/shop/${frame.fileName}", location?.absolutePath, "$frame")
                assertEquals(frame.line, location?.line)
            } else {
                assertNull(location, "$frame is not project code")
            }
        }
        for (node in snapshot.nodes.values.filter { it.info.origin == Origin.PROJECT && it.info.siteFrame != 0 }) {
            assertNotNull(snapshot.frame(node.info.siteFrame)?.let(snapshot.sources::resolve), "the site of project node ${node.id} can be opened")
        }
    }
}

class DemoGateTest {
    private val said = ArrayList<PaceDef>()
    private val gate = DemoGate { frame: Frame -> said += frame.pace!! }
    private var seq = 0L

    private fun event(node: Long, kind: EventKind = EventKind.RESUMED, parent: Long? = null) =
        Event(seq = ++seq, timeNanos = seq * 1_000, nodeId = node, kind = kind, node = parent?.let { NodeInfo(id = node, parentId = it) })

    /** Whether the gate lets the next event of [node] through now. A held one is given up on after a moment. */
    private fun passes(node: Long): Boolean = passes(event(node))

    private fun passes(event: Event): Boolean = runBlocking { withTimeoutOrNull(HOLD_MILLIS) { gate.await(event); true } ?: false }

    /** 1 with child 2 and grandchild 3; 4 on its own. All shown while the gate is open. */
    private fun family() {
        for ((node, parent) in listOf(1L to 0L, 2L to 1L, 3L to 2L, 4L to 0L)) assertTrue(passes(event(node, EventKind.LAUNCHED, parent)))
    }

    private fun last() = said.last()

    @Test
    fun everyCommandIsAnsweredWithTheCompleteSettingItLeftBehind() {
        gate.announce()
        assertEquals(PaceDef(reason = PaceDef.Reason.CONFIG), last(), "the gate introduces itself as the agent's does: open, as configured")

        gate.command(PaceCommand.SetPace(5_000))
        assertEquals(PaceDef(intervalNanos = 5_000, reason = PaceDef.Reason.CONTROLLER), last())
        gate.command(PaceCommand.Pause())
        assertEquals(PaceDef(intervalNanos = 5_000, paused = true, reason = PaceDef.Reason.CONTROLLER), last(), "a PaceDef is always a complete setting")
        gate.command(PaceCommand.Step(2))
        assertEquals(PaceDef(intervalNanos = 5_000, paused = true, steps = 2, reason = PaceDef.Reason.CONTROLLER), last())
        gate.command(PaceCommand.Resume())
        assertEquals(PaceDef(intervalNanos = 5_000, reason = PaceDef.Reason.CONTROLLER), last())
        gate.command(PaceCommand.SetPace(-1))
        assertEquals(0, last().intervalNanos, "no limit")
        assertEquals(6, said.size, "one answer per command, and nothing else")
    }

    @Test
    fun aChangeSaysAfterWhichEventItWasMade() {
        family()
        gate.command(PaceCommand.Pause())
        assertEquals(4L to 4_000L, last().afterSeq to last().timeNanos)
        assertFalse(passes(1))
        gate.command(PaceCommand.Resume())
        assertEquals(4L, last().afterSeq, "an event that is being held has not happened")
    }

    /** "`step n` of a scope that is not paused pauses it and grants n; on a paused scope permits add up; `pause` / `resume` zero them." */
    @Test
    fun stepPausesAndGrantsPermitsAddUpAndPauseAndResumeZeroThem() {
        family()
        gate.command(PaceCommand.Step(3))
        assertTrue(last().paused && last().steps == 3, "→ in a running session: stop after three more")
        assertEquals(listOf(true, true, true, false), listOf(passes(1), passes(4), passes(3), passes(1)), "exactly three")

        gate.command(PaceCommand.Step(1))
        gate.command(PaceCommand.Step(1))
        assertEquals(listOf(true, true, false), listOf(passes(2), passes(2), passes(2)), "one and one are two")

        gate.command(PaceCommand.Step(5))
        gate.command(PaceCommand.Pause())
        assertFalse(passes(1), "pause takes back what step granted")

        gate.command(PaceCommand.Step(5))
        gate.command(PaceCommand.Resume())
        assertTrue((1..8).all { passes(1) }, "running again")
        gate.command(PaceCommand.Pause())
        assertFalse(passes(1), "no permits are left over from before the resume")

        gate.command(PaceCommand.Step(0))
        assertEquals(1, last().steps, "there is no such thing as no step")
        assertEquals(listOf(true, false), listOf(passes(1), passes(1)))
    }

    /** "A node's setting starts as a copy of what governs the node … and is independent from then on (so 'pause everything, then resume 42' runs one subtree alone)." */
    @Test
    fun aSubtreeSettingStartsAsACopyOfWhatGovernsItAndIsItsOwnFromThenOn() {
        family()
        gate.command(PaceCommand.SetPace(7))
        gate.command(PaceCommand.Pause())
        gate.command(PaceCommand.Resume(2))
        assertEquals(PaceDef(afterSeq = 4, timeNanos = 4_000, scopeNodeId = 2, intervalNanos = 7, paused = false, reason = PaceDef.Reason.CONTROLLER), last(), "the program's pace, without its pause")
        assertEquals(listOf(true, true, false, false), listOf(passes(2), passes(3), passes(1), passes(4)), "one subtree runs alone")

        gate.command(PaceCommand.Resume())
        gate.command(PaceCommand.SetPace(0))
        gate.command(PaceCommand.Pause(2))
        assertEquals(7, last().intervalNanos, "what the program was set to since is not the subtree's business")
        assertEquals(listOf(false, false, true, true), listOf(passes(2), passes(3), passes(1), passes(4)), "and now it alone stands")

        // The innermost setting governs.
        gate.command(PaceCommand.Resume(3))
        assertEquals(3L to false, last().scopeNodeId to last().paused)
        assertEquals(listOf(true, false), listOf(passes(3), passes(2)))

        gate.command(PaceCommand.Inherit(3))
        assertEquals(PaceDef(afterSeq = last().afterSeq, timeNanos = last().timeNanos, scopeNodeId = 3, reason = PaceDef.Reason.CONTROLLER, dropped = true), last())
        assertFalse(passes(3), "it goes by its parent's again, which is paused")
        gate.command(PaceCommand.Inherit(2))
        assertTrue(passes(3) && passes(2))
    }

    @Test
    fun aSubtreeIsSteppedOnPermitsOfItsOwn() {
        family()
        gate.command(PaceCommand.Step(2, node = 2))
        assertEquals(Triple(2L, true, 2), Triple(last().scopeNodeId, last().paused, last().steps), "a running subtree: paused, two granted")
        assertEquals(listOf(true, true, true, true, false, false), listOf(passes(1), passes(3), passes(4), passes(2), passes(3), passes(2)), "two of the subtree's, any number of anybody else's")
        gate.command(PaceCommand.Step())
        assertTrue(passes(1), "a step of the program is not a step of the subtree")
        assertFalse(passes(3))
    }

    @Test
    fun commandsThatChangeNothingAreNotAnswered() {
        family()
        gate.command(PaceCommand.Pause(node = 999))
        gate.command(PaceCommand.Step(3, node = 999))
        gate.command(PaceCommand.Inherit(999))
        gate.command(PaceCommand.Inherit(2)) // it has no setting to drop
        assertEquals(emptyList(), said, "a node nobody knows, a setting that is not there")
        assertTrue(passes(1) && passes(2))
    }

    /** DESIGN §3.1: a pace is a minimum interval per sequence; "parallel sequences are never serialised". */
    @Test
    fun aPaceSpacesTheEventsOfOneNodeAndNotThoseOfDifferentNodes() {
        val interval = 300_000_000L
        gate.command(PaceCommand.SetPace(interval))
        val started = System.nanoTime()
        assertTrue(passes(11) && passes(12) && passes(13) && passes(14), "four nodes, four sequences: nobody waits for anybody")
        runBlocking { gate.await(event(11)) }
        val waited = System.nanoTime() - started
        assertTrue(waited >= interval, "the second event of node 11 came ${waited / 1_000_000} ms after the first")
    }

    private companion object {
        /** Far more than the gate's tick, far less than anybody would notice. */
        const val HOLD_MILLIS = 120L
    }
}
