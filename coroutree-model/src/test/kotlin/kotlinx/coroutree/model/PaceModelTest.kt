package kotlinx.coroutree.model

import kotlinx.coroutree.model.tree.PaceSetting
import kotlinx.coroutree.model.tree.PaceState
import kotlinx.coroutree.model.tree.TraceStore
import kotlinx.coroutree.model.tree.describe
import kotlinx.coroutree.model.tree.paceLabel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Execution control as the model sees it: the settings of the gate folded from the stream, and how they are worded. */
class PaceModelTest {
    private fun pace(scope: Long = 0, interval: Long = 0, paused: Boolean = false, steps: Int = 0, reason: PaceDef.Reason = PaceDef.Reason.CONTROLLER, dropped: Boolean = false, after: Long = 0) =
        Frame(pace = PaceDef(timeNanos = 1, afterSeq = after, scopeNodeId = scope, intervalNanos = interval, paused = paused, steps = steps, reason = reason, dropped = dropped))

    private fun node(id: Long, parent: Long, name: String = "") =
        Frame(event = Event(seq = id, nodeId = id, kind = EventKind.LAUNCHED, node = NodeInfo(id = id, kind = NodeKind.COROUTINE, construct = "launch", name = name, parentId = parent)))

    @Test
    fun aTraceThatSaysNothingAboutAGateHasNone() {
        val snapshot = TraceStore().apply { accept(node(1, 0)) }.snapshot()
        assertSame(PaceState.NONE, snapshot.pace)
        assertNull(snapshot.pace.global)
        assertTrue(snapshot.paceChanges.isEmpty())
        assertNull(snapshot.pace.governing(1) { null })
    }

    @Test
    fun settingsAreFoldedInTheOrderOfTheStream() {
        val store = TraceStore()
        store.accept(pace(interval = 5, reason = PaceDef.Reason.CONFIG))
        assertEquals(PaceSetting(5, false), store.snapshot().pace.global)
        val before = store.snapshot()

        store.accept(pace(paused = true, interval = 5))
        store.accept(pace(scope = 7, interval = 9))
        store.accept(pace(scope = 8, paused = true, steps = 3))
        assertEquals(PaceState(PaceSetting(5, true), mapOf(7L to PaceSetting(9, false), 8L to PaceSetting(0, true))), store.snapshot().pace)
        assertEquals(PaceSetting(5, false), before.pace.global, "a snapshot that was taken does not change")
        assertEquals(1, before.paceChanges.size)

        store.accept(pace(scope = 7, dropped = true))
        store.accept(pace(scope = 8, dropped = true, reason = PaceDef.Reason.NODE_FINISHED))
        store.accept(pace(scope = 99, dropped = true)) // dropping what was never set is nothing
        store.accept(pace(reason = PaceDef.Reason.FAIL_OPEN, interval = 5))
        val snapshot = store.snapshot()
        assertEquals(PaceState(PaceSetting(5, false)), snapshot.pace)
        assertEquals(8, snapshot.paceChanges.size)
        assertTrue(snapshot.pace.global!!.isOpen.not())
        assertTrue(PaceSetting().isOpen)
    }

    @Test
    fun theInnermostSettingOnTheWayToTheRootGoverns() {
        val parents = mapOf(4L to 3L, 3L to 2L, 2L to 1L, 1L to 0L)
        val state = PaceState(PaceSetting(1, false), mapOf(2L to PaceSetting(2, true), 4L to PaceSetting(4, false)))
        assertEquals(PaceSetting(4, false), state.governing(4, parents::get))
        assertEquals(PaceSetting(2, true), state.governing(3, parents::get))
        assertEquals(PaceSetting(2, true), state.governing(2, parents::get))
        assertEquals(PaceSetting(1, false), state.governing(1, parents::get))
        assertEquals(PaceSetting(1, false), state.governing(77, parents::get), "a node nobody knows goes by the global setting")
        val loop = mapOf(1L to 2L, 2L to 1L)
        assertEquals(PaceSetting(1, false), PaceState(PaceSetting(1, false)).governing(1, loop::get).also { }, "a trace that is wrong about parents does not hang the reader")
    }

    @Test
    fun newFieldsSurviveTheWireAndOldReadersSkipThem() {
        val frames = listOf(
            Frame(header = TraceHeader(formatVersion = 1, paceable = true)),
            pace(scope = 3, interval = 250_000_000, paused = true, steps = 2, reason = PaceDef.Reason.CONTROLLER, after = 41),
            Frame(event = Event(seq = 1, nodeId = 3, kind = EventKind.RESUMED, heldNanos = 1_500_000_000, sameStep = true)),
            pace(scope = 3, dropped = true, reason = PaceDef.Reason.NODE_FINISHED),
        )
        val bytes = ByteArrayOutputStream().also { out -> TraceWriter(out).use { writer -> frames.forEach(writer::write) } }.toByteArray()
        assertEquals(frames, TraceReader(ByteArrayInputStream(bytes)).use { it.frames().toList() })
        val plain = Frame(event = Event(seq = 1, nodeId = 3, kind = EventKind.RESUMED))
        val plainBytes = ByteArrayOutputStream().also { out -> TraceWriter(out).use { it.write(plain) } }.toByteArray()
        val withFields = ByteArrayOutputStream().also { out -> TraceWriter(out).use { it.write(frames[2]) } }.toByteArray()
        assertTrue(withFields.size > plainBytes.size, "zero defaults are left out: a trace without a gate has not a byte of any of this")
    }

    @Test
    fun wording() {
        assertEquals("full speed", paceLabel(0))
        assertEquals("1 event/s", paceLabel(1_000_000_000))
        assertEquals("2 events/s", paceLabel(500_000_000))
        assertEquals("2.5 events/s", paceLabel(400_000_000))
        assertEquals("1000 events/s", paceLabel(1_000_000))
        assertEquals("333 events/s", paceLabel(3_000_000))
        assertEquals("1 event in 5 s", paceLabel(5_000_000_000))
        assertEquals("1 event in 2.5 s", paceLabel(2_500_000_000))
        assertEquals("1 event in 10 s", paceLabel(10_000_000_000))
        assertEquals("paused", PaceSetting(7, true).describe())
        assertEquals("full speed", PaceSetting().describe())

        val snapshot = TraceStore().apply {
            accept(node(3, 0, name = "worker"))
            endOfStream() // its sequence number is 3: held back until then
        }.snapshot()
        fun text(frame: Frame) = snapshot.describe(frame.pace!!)
        assertEquals("execution control: paused (as configured)", text(pace(paused = true, reason = PaceDef.Reason.CONFIG)))
        assertEquals("execution control: runs at full speed (as configured)", text(pace(reason = PaceDef.Reason.CONFIG)))
        assertEquals("execution control: runs at 2 events/s per sequence", text(pace(interval = 500_000_000)))
        assertEquals("execution control: paused, 1 step let through", text(pace(paused = true, steps = 1)))
        assertEquals("execution control: paused, 3 steps let through", text(pace(paused = true, steps = 3)))
        assertEquals("execution control: subtree of launch \"worker\": paused", text(pace(scope = 3, paused = true)))
        assertEquals("execution control: subtree of launch \"worker\": follows the program's setting again", text(pace(scope = 3, dropped = true)))
        assertEquals("execution control: subtree of launch \"worker\": follows the program's setting again (the node has ended)", text(pace(scope = 3, dropped = true, reason = PaceDef.Reason.NODE_FINISHED)))
        assertEquals("execution control: subtree of node #9: paused", text(pace(scope = 9, paused = true)))
        assertEquals("execution control: runs at full speed (nobody is in control any more)", text(pace(reason = PaceDef.Reason.FAIL_OPEN)))
        assertEquals("execution control: runs at full speed (the JVM is shutting down)", text(pace(reason = PaceDef.Reason.SHUTDOWN)))
    }
}
