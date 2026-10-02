package kotlinx.coroutree.model

import kotlinx.coroutree.model.tree.AppendLog
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.TraceStore
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `TraceSnapshot`: "Immutable view of a trace at one moment. Safe to hand to another thread." The store shares what it
 * can between snapshots (prefix views of append-only logs, nodes that did not change), so the promise is worth
 * checking against a trace that keeps growing rather than trusting.
 */
class SnapshotIsolationTest {
    /** Everything a snapshot says, ids and all. */
    private fun TraceSnapshot.dump(): String = buildString {
        appendLine("header=$header complete=$complete roots=$roots")
        appendLine("events=${events.map { it.seq }}")
        appendLine("diagnostics=$diagnostics")
        appendLine("frames=${(0..40).map { frame(it)?.className }}")
        appendLine("pace=$pace changes=$paceChanges")
        for (id in nodes.keys.sorted()) {
            val node = node(id)!!
            appendLine("$id: ${node.info} ${node.state} placeholder=${node.placeholder} runsOn=${node.runsOn}")
            appendLine("   children=${node.children} events=${node.events.map { it.seq }}")
            appendLine("   links=${node.links} diff=${node.contextDiff}")
        }
    }

    /** A trace with no meaning and every feature: nodes defined late, references to anybody, frames of every kind in between. */
    private fun randomTrace(seed: Long, nodes: Int = 60, events: Int = 700): List<Frame> {
        val random = Random(seed)
        val kinds = EventKind.entries - EventKind.LAUNCHED - EventKind.DISCOVERED - EventKind.UNSPECIFIED
        val definitions = (1L..nodes).shuffled(random).toMutableList()
        var frameId = 0
        return script {
            header(TraceHeader(formatVersion = 1))
            repeat(events) {
                when (random.nextInt(12)) {
                    0, 1 -> definitions.removeLastOrNull()?.let { id ->
                        // Parents have smaller ids (no cycles) but may be defined later, or never.
                        launched(id, parent = random.nextInt(id.toInt()).toLong(), creator = random.nextInt(nodes + 1).toLong())
                    }
                    2 -> frame(++frameId, "demo.C$frameId")
                    3 -> frames += Frame(diagnostic = Diagnostic(Diagnostic.Severity.INFO, "d$it"))
                    4 -> frames += Frame(pace = PaceDef(scopeNodeId = random.nextInt(4).toLong(), intervalNanos = it.toLong(), dropped = random.nextInt(4) == 0))
                    else -> event(
                        node = 1L + random.nextInt(nodes),
                        kind = kinds[random.nextInt(kinds.size)],
                        other = if (random.nextBoolean()) 0 else 1L + random.nextInt(nodes),
                        thread = random.nextInt(4).toLong(),
                    ) {
                        copy(
                            finalState = NodeState.FAILED,
                            contextDiff = listOf(ContextChange(ContextElementKind.NAME, "CoroutineName", newValue = "n$it", added = true)),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aSnapshotSaysTheSameForEverWhileTheStoreMovesOn() {
        for (seed in 1L..3L) {
            val frames = randomTrace(seed)
            val store = TraceStore()
            val taken = frames.map { frame ->
                store.accept(frame)
                store.snapshot().let { it to it.dump() }
            }
            store.endOfStream()
            store.snapshot()
            taken.forEachIndexed { index, (snapshot, dumpThen) ->
                assertEquals(dumpThen, snapshot.dump(), "seed $seed: the snapshot taken after frame $index of ${frames.size}")
            }
        }
    }

    @Test
    fun takingSnapshotsOnTheWayDoesNotChangeWhereTheStoreArrives() {
        // Snapshots are built from what changed since the previous one; a node that changed and was not noticed would
        // be stale in every later snapshot. Compare with a store nobody looked at until the end.
        for (seed in 1L..10L) {
            val frames = randomTrace(seed)
            val unobserved = TraceStore().apply { frames.forEach(::accept) }
            val observed = TraceStore()
            val random = Random(seed)
            for (frame in frames) {
                observed.accept(frame)
                if (random.nextInt(3) == 0) observed.snapshot()
            }
            assertEquals(unobserved.snapshot().dump(), observed.snapshot().dump(), "seed $seed")
            unobserved.endOfStream()
            observed.endOfStream()
            assertEquals(unobserved.snapshot().dump(), observed.snapshot().dump(), "seed $seed, at the end of the stream")
            assertTrue(observed.snapshot().complete)
        }
    }

    @Test
    fun everyViewOfAnAppendLogStaysThePrefixItWas() {
        // Chunks double (8, 16, 32, …) and the directory of chunks grows too: both boundaries are crossed many times here.
        val log = AppendLog<Int>()
        assertEquals(emptyList(), log.view())
        val total = 20_000
        val views = ArrayList<List<Int>>()
        for (i in 0 until total) {
            log.add(i)
            if (i < 2_100 || i % 997 == 0) views += log.view()
        }
        for (view in views) {
            val size = view.size
            assertEquals(view.last(), size - 1, "a view of $size elements ends where it ended")
            if (size <= 300 || size % 997 == 1) assertEquals((0 until size).toList(), view)
            // What was appended later is there, in the same arrays, and is not the view's to give.
            assertFailsWith<IndexOutOfBoundsException> { view[size] }
            assertFailsWith<IndexOutOfBoundsException> { view[-1] }
        }
        assertEquals((0 until total).toList(), log.view())
    }
}
