package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.view.EventLogItems
import kotlinx.coroutree.gui.view.PaceCommand
import kotlinx.coroutree.gui.view.SpeedScale
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.gui.view.TraceViewModel.ControlKey
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.TraceHeader
import kotlinx.coroutree.model.tree.PaceSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Execution control as the GUI's logic sees it: what is sent, what is shown, and where in the log a change goes. */
class PaceControlTest {
    // ------------------------------------------------------------------ commands

    @Test
    fun commandsAreTheLinesTheAgentReads() {
        assertEquals("pause", PaceCommand.Pause().line)
        assertEquals("pause 42", PaceCommand.Pause(42).line)
        assertEquals("resume", PaceCommand.Resume().line)
        assertEquals("resume 7", PaceCommand.Resume(7).line)
        assertEquals("step 1", PaceCommand.Step().line)
        assertEquals("step 5 7", PaceCommand.Step(5, 7).line)
        assertEquals("step 1", PaceCommand.Step(0).line, "there is no such thing as no step")
        assertEquals("pace 0", PaceCommand.SetPace(0).line)
        assertEquals("pace 500000000 9", PaceCommand.SetPace(500_000_000, 9).line)
        assertEquals("pace 0", PaceCommand.SetPace(-5).line)
        assertEquals("inherit 9", PaceCommand.Inherit(9).line)
        for (command in listOf(PaceCommand.Pause(3), PaceCommand.Step(2, 3), PaceCommand.SetPace(1, 3))) {
            assertFalse('\n' in command.line)
        }
    }

    // ------------------------------------------------------------------ the speed scale

    @Test
    fun theSliderRunsFromOneEventInTenSecondsToNoLimit() {
        assertEquals(10_000_000_000, SpeedScale.intervalAt(0f))
        assertEquals(0, SpeedScale.intervalAt(1f))
        assertEquals(0, SpeedScale.intervalAt(SpeedScale.UNLIMITED_FROM))
        assertEquals(0, SpeedScale.intervalAt(7f))
        assertEquals(10_000_000_000, SpeedScale.intervalAt(-1f))
        val justBelow = SpeedScale.intervalAt(SpeedScale.UNLIMITED_FROM - 0.001f)
        assertTrue(justBelow in 1_000_000..1_100_000, "the fastest pace that is a pace: $justBelow")
    }

    @Test
    fun theScaleIsLogarithmicMonotoneAndTidy() {
        var previous = Long.MAX_VALUE
        for (i in 0..960) {
            val interval = SpeedScale.intervalAt(i / 1000f)
            assertTrue(interval <= previous, "at $i: $interval after $previous")
            assertTrue(interval > 0)
            val digits = interval.toString().trimEnd('0')
            assertTrue(digits.length <= 2, "$interval is not a number to show anybody")
            previous = interval
        }
        // Halfway along the track is halfway between the ends in orders of magnitude: 10 s … 1 ms → 100 ms.
        assertEquals(100_000_000, SpeedScale.intervalAt(SpeedScale.UNLIMITED_FROM / 2))
    }

    @Test
    fun aSettingFromTheStreamFindsItsPlaceOnTheTrack() {
        assertEquals(1f, SpeedScale.positionOf(0))
        assertEquals(1f, SpeedScale.positionOf(-1))
        assertEquals(0f, SpeedScale.positionOf(10_000_000_000))
        assertEquals(0f, SpeedScale.positionOf(Long.MAX_VALUE), "slower than the slider goes (set in the build script): at its slow end")
        assertTrue(SpeedScale.positionOf(1) < SpeedScale.UNLIMITED_FROM + 0.0001f, "faster than the slider goes is still not 'no limit'")
        for (interval in listOf(10_000_000_000, 2_000_000_000, 500_000_000, 33_000_000, 1_000_000)) {
            assertEquals(interval, SpeedScale.intervalAt(SpeedScale.positionOf(interval)), "there and back")
        }
    }

    // ------------------------------------------------------------------ the log

    private fun event(seq: Long) = Event(seq = seq, nodeId = 1, kind = EventKind.RESUMED)
    private fun change(after: Long, steps: Int = 0) = PaceDef(afterSeq = after, steps = steps)

    private fun EventLogItems.rendered(): List<String> = (0 until size).map { index ->
        when (val item = get(index)) {
            is EventLogItems.Item.Of -> "#${item.event.seq}"
            is EventLogItems.Item.Pace -> "pace${item.index}"
        }
    }

    @Test
    fun aChangeOfTheGateGoesBehindTheEventItWasMadeAfter() {
        val events = (1L..5L).map(::event)
        assertEquals(listOf("#1", "#2", "#3", "#4", "#5"), EventLogItems(events, emptyList()).rendered())
        assertEquals(listOf("pace0", "#1", "#2", "pace1", "#3", "#4", "#5", "pace2"), EventLogItems(events, listOf(change(0), change(2), change(5))).rendered())
        assertEquals(listOf("pace0", "pace1"), EventLogItems(emptyList(), listOf(change(0), change(0))).rendered(), "a program that starts paused has settings before it has events")
        assertEquals(listOf("#1", "#2", "pace0", "pace1", "pace2", "#3", "#4", "#5"), EventLogItems(events, listOf(change(2), change(2, steps = 1), change(2))).rendered(), "several at one place keep the order of the stream")
        assertEquals(listOf("#1", "#2", "#3", "#4", "#5", "pace0"), EventLogItems(events, listOf(change(99))).rendered(), "made after an event that has not arrived yet: at the end for now")
        assertEquals(listOf("#1", "#2", "#3", "pace0", "pace1", "#4", "#5"), EventLogItems(events, listOf(change(3), change(1))).rendered(), "the stream's order wins over a number that says otherwise")
    }

    @Test
    fun eventsAreFoundInALogThatHasChangesInIt() {
        val events = listOf(1L, 2L, 4L, 5L, 9L).map(::event) // sequence numbers may have gaps
        val log = EventLogItems(events, listOf(change(0), change(2), change(2), change(7), change(9)))
        assertEquals(listOf("pace0", "#1", "#2", "pace1", "pace2", "#4", "#5", "pace3", "#9", "pace4"), log.rendered())
        for ((index, e) in events.withIndex()) {
            val position = log.positionOfEvent(index)
            assertEquals(EventLogItems.Item.Of(e), log[position])
        }
        assertEquals(-1, log.positionOfEvent(-1))
        assertEquals(setOf<Any>(1L, 2L, 4L, 5L, 9L, "pace-0", "pace-1", "pace-2", "pace-3", "pace-4"), (0 until log.size).map(log::key).toSet(), "keys are unique and outlive growth")
    }

    @Test
    fun aLargeLogCostsNothingToIndex() {
        val events = (1L..200_000L).map(::event)
        val log = EventLogItems(events, (0L until 200).map { change(it * 1000) })
        assertEquals(200_200, log.size)
        assertEquals(EventLogItems.Item.Of(events.last()), log[log.size - 1])
        assertEquals(EventLogItems.Item.Pace(change(1000), 1), log[1001])
        assertEquals(log.size - 1, log.positionOfEvent(events.size - 1))
    }

    // ------------------------------------------------------------------ the view model

    private fun liveModel(vararg frames: Frame): Pair<TraceViewModel, MutableList<String>> {
        val sent = mutableListOf<String>()
        val viewModel = TraceViewModel()
        viewModel.commands = { sent += it.line }
        viewModel.snapshot = running(listOf(Frame(header = TraceHeader(paceable = true))) + frames.asList())
        return viewModel to sent
    }

    /** A snapshot of a stream that has not ended. */
    private fun running(frames: List<Frame>) = kotlinx.coroutree.model.tree.TraceStore().apply { frames.forEach(::accept) }.snapshot()

    private fun pace(scope: Long = 0, interval: Long = 0, paused: Boolean = false, reason: PaceDef.Reason = PaceDef.Reason.CONTROLLER, dropped: Boolean = false) =
        Frame(pace = PaceDef(scopeNodeId = scope, intervalNanos = interval, paused = paused, reason = reason, dropped = dropped))

    @Test
    fun thereIsSomethingToControlOnlyInARunningJvmThatHasAGate() {
        val (viewModel, sent) = liveModel(pace(reason = PaceDef.Reason.CONFIG))
        assertTrue(viewModel.paceable)

        viewModel.commands = null // a recorded trace, or the feed has ended
        assertFalse(viewModel.paceable)
        assertFalse(viewModel.handleKey(ControlKey.PAUSE_RESUME))
        viewModel.togglePause()
        viewModel.step()

        viewModel.commands = { sent += it.line }
        viewModel.snapshot = running(listOf(Frame(header = TraceHeader(paceable = false))))
        assertFalse(viewModel.paceable, "pace { enabled = false }: no gate, no controls")
        viewModel.snapshot = running(listOf(Frame(header = TraceHeader())))
        assertFalse(viewModel.paceable, "an agent that has never heard of execution control")
        viewModel.snapshot = snapshotOf(listOf(Frame(header = TraceHeader(paceable = true)))) // the stream has ended
        assertFalse(viewModel.paceable)
        viewModel.togglePause()
        assertEquals(emptyList(), sent, "nothing is sent to nobody")
    }

    @Test
    fun whatIsShownAndToggledIsWhatTheStreamSaysNotWhatWasLastClicked() {
        val (viewModel, sent) = liveModel(pace(reason = PaceDef.Reason.CONFIG))
        assertEquals(PaceSetting(0, false), viewModel.globalPace)
        viewModel.togglePause()
        viewModel.togglePause() // the answer has not come back: the program still runs, as far as anybody knows
        assertEquals(listOf("pause", "pause"), sent)
        assertEquals(false, viewModel.globalPace?.paused)

        viewModel.snapshot = running(listOf(Frame(header = TraceHeader(paceable = true)), pace(paused = true, interval = 5)))
        assertEquals(PaceSetting(5, true), viewModel.globalPace)
        viewModel.togglePause()
        assertEquals("resume", sent.last())

        // Another GUI on the same JVM resumed it meanwhile.
        viewModel.snapshot = running(listOf(Frame(header = TraceHeader(paceable = true)), pace(paused = true), pace()))
        viewModel.togglePause()
        assertEquals("pause", sent.last())
    }

    @Test
    fun spacePausesAndResumesAndTheArrowSteps() {
        val (viewModel, sent) = liveModel(pace(paused = true))
        assertTrue(viewModel.handleKey(ControlKey.STEP))
        assertTrue(viewModel.handleKey(ControlKey.PAUSE_RESUME))
        assertEquals(listOf("step 1", "resume"), sent)
    }

    @Test
    fun aSubtreeIsToggledByWhatGovernsIt() {
        val nodes = trace {
            node(1, 0)
            node(2, 1)
            node(3, 2)
            node(4, 0)
        }
        val (viewModel, sent) = liveModel(*nodes.toTypedArray(), pace(), pace(scope = 2, paused = true, interval = 9))
        assertEquals(PaceSetting(9, true), viewModel.ownPace(2))
        assertNull(viewModel.ownPace(3))
        assertEquals(PaceSetting(9, true), viewModel.governingPace(3), "a setting holds for the subtree")
        assertEquals(PaceSetting(0, false), viewModel.governingPace(4))
        assertEquals(PaceSetting(0, false), viewModel.governingPace(1))

        viewModel.togglePause(3) // paused through its parent: resuming it gives it a setting of its own
        viewModel.togglePause(4)
        viewModel.step(3, count = 2)
        viewModel.setPace(250_000_000, node = 4)
        viewModel.inherit(2)
        assertEquals(listOf("resume 3", "pause 4", "step 2 3", "pace 250000000 4", "inherit 2"), sent)
    }

    @Test
    fun theBannerIsUpWhileAProgramThatStartedPausedWaitsForSomebody() {
        val (viewModel, _) = liveModel(pace(paused = true, reason = PaceDef.Reason.CONFIG))
        assertTrue(viewModel.pausedAtStart)
        viewModel.snapshot = running(listOf(Frame(header = TraceHeader(paceable = true)), pace(paused = true, reason = PaceDef.Reason.CONFIG), pace(scope = 5, paused = true)))
        assertTrue(viewModel.pausedAtStart, "a subtree's setting is not the program's")
        viewModel.snapshot = running(listOf(Frame(header = TraceHeader(paceable = true)), pace(paused = true, reason = PaceDef.Reason.CONFIG), pace(paused = true)))
        assertFalse(viewModel.pausedAtStart, "somebody has stepped: paused, but no longer 'at start'")
        viewModel.snapshot = running(listOf(Frame(header = TraceHeader(paceable = true)), pace(reason = PaceDef.Reason.CONFIG)))
        assertFalse(viewModel.pausedAtStart)
    }

    @Test
    fun theDemoTraceTellsOfItsExecutionControl() {
        val snapshot = demoSnapshot()
        assertEquals(true, snapshot.header?.paceable)
        assertEquals(listOf(PaceDef.Reason.CONFIG) + List(4) { PaceDef.Reason.CONTROLLER }, snapshot.paceChanges.map { it.reason })
        assertEquals(PaceSetting(0, false), snapshot.pace.global)
        val receipt = snapshot.named("receipt")
        assertEquals(mapOf(receipt.id to PaceSetting(2_000_000_000, false)), snapshot.pace.nodes)
        val viewModel = TraceViewModel().also { it.snapshot = snapshot }
        assertFalse(viewModel.paceable, "a recorded trace shows what was set and offers nothing to set")
        assertEquals(snapshot.events.size + 5, viewModel.eventLog.size)
        val selected = snapshot.events[snapshot.events.size / 2]
        assertEquals(EventLogItems.Item.Of(selected), viewModel.eventLog[viewModel.logIndex(selected.seq)])
    }
}
