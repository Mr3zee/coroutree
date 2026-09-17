package kotlinx.coroutree.it

import kotlinx.coroutree.gui.view.GraphBuilder
import kotlinx.coroutree.gui.view.GraphOptions
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.gui.view.graph.InvariantChecker
import kotlinx.coroutree.gui.view.graph.LayoutEngine
import kotlinx.coroutree.model.TraceReader
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.TraceStore
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The drawing invariant of the graph (DESIGN §6.2) on what the agent really records: every sample of the corpus is
 * laid out the way the GUI would — at several moments of the run, as a live session would show it, under every
 * combination of the toolbar switches and with every node selected in turn — and each of those drawings goes through
 * the invariant checker. Randomly generated forests and the demo trace are checked in coroutree-gui; this is the part
 * that takes a run under the agent.
 */
class GraphInvariantCorpusTest {
    private val checker = InvariantChecker()

    @TestFactory
    fun samples(): List<DynamicTest> = GoldenTreeTest.SAMPLES.map { sample ->
        dynamicTest(sample) {
            var drawings = 0
            for ((moment, snapshot) in moments(sample)) {
                for (library in listOf(false, true)) for (labels in listOf(false, true)) {
                    val options = GraphOptions(showLibrary = library, labels = labels)
                    for (selected in listOf<Long?>(null) + snapshot.nodes.keys) {
                        check(snapshot, options, selected, "$sample at $moment, library=$library, labels=$labels, selected=$selected")
                        drawings++
                    }
                }
            }
            assertTrue(drawings >= 16, "$sample was drawn $drawings times")
        }
    }

    /**
     * The trace after a quarter, a half and three quarters of its frames, and whole. Only the unfinished ones have
     * coroutines that are running on a thread, and with them the runs-on edge of the selection.
     */
    private fun moments(sample: String): List<Pair<String, TraceSnapshot>> {
        val frames = TraceReader(SampleRuns[sample].traceFile.inputStream()).use { it.frames().toList() }
        return listOf(1, 2, 3).map { quarter ->
            "$quarter/4" to TraceStore().apply { frames.take(frames.size * quarter / 4).forEach(::accept) }.snapshot()
        } + ("the end" to SampleRuns[sample].snapshot)
    }

    /** No sample may go unnoticed for having nothing to draw, and between them they draw every kind of edge. */
    @Test
    fun theCorpusDrawsEveryKindOfCrossLink() {
        val kinds = HashSet<EdgeKind>()
        val everything = GraphOptions(showLibrary = true)
        for (sample in GoldenTreeTest.SAMPLES) {
            assertTrue(GraphBuilder.build(SampleRuns[sample].snapshot).model.nodes.size >= 2, "$sample has a graph to draw")
            for ((_, snapshot) in moments(sample)) {
                for (selected in listOf<Long?>(null) + snapshot.nodes.keys) GraphBuilder.build(snapshot, everything, selected).model.links.mapTo(kinds) { it.kind }
            }
        }
        assertEquals(EdgeKind.entries.toSet(), kinds)
    }

    /** A real trace that is wide: thousands of coroutines under a handful of scopes, the shape DESIGN §11.9 worries about. */
    @Test
    fun aWideRealTrace() {
        val run = runUnderAgent("samples.StressKt", programArgs = listOf("3000", "2"), runName = "Stress-graph")
        assertEquals(0, run.exitCode, run.output)
        val snapshot = run.snapshot
        assertTrue(snapshot.nodes.size > 3000, "${snapshot.nodes.size} nodes")
        check(snapshot, GraphOptions(), null, "Stress")
        check(snapshot, GraphOptions(showLibrary = true), snapshot.nodes.keys.max(), "Stress with the library")
    }

    private fun check(snapshot: TraceSnapshot, options: GraphOptions, selected: Long?, what: String) {
        val graph = GraphBuilder.build(snapshot, options, selected)
        val layout = LayoutEngine.layout(graph.model)
        assertEquals(graph.model.nodes.size, layout.boxes.size, what)
        assertEquals(snapshot.nodes.size, graph.model.nodes.size + graph.hiddenNodes, what)
        val violations = checker.check(layout.toDrawing())
        if (violations.isNotEmpty()) fail("The graph of $what violates the drawing invariant (DESIGN §6.2):\n" + violations.joinToString("\n"))
    }
}
