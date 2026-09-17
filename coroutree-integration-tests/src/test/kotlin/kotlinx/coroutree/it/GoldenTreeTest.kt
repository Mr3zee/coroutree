package kotlinx.coroutree.it

import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.TreeRenderer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Runs every program of the sample corpus under the agent and compares the tree it produced with
 * `samples/golden/dynamic/<Sample>.txt`. Run with `-PupdateGoldens` to rewrite the goldens, then review the diff.
 *
 * What is compared is what is the same in every run: the structure, states, context diffs and each node's own
 * events in order. The runtime's own threads and pools are left out: how many workers a dispatcher starts, and
 * when, is up to the machine.
 */
class GoldenTreeTest {
    @TestFactory
    fun samples(): List<DynamicTest> = SAMPLES.map { sample ->
        dynamicTest(sample) { check(sample, SampleRuns[sample], mayUpdate = true) }
    }

    companion object {
        /**
         * The corpus, minus `Interactive`, which runs for as long as the user likes and has no one tree, and `Stress`.
         * `MonitorContention` only where the agent under test has a native monitor probe for this machine.
         */
        val SAMPLES = listOfNotNull(
            "MonitorContention".takeIf { MonitorProbeLibrary.file != null },
            "StructuredConcurrency",
            "ContextAndDispatchers",
            "ExceptionPropagation",
            "SupervisorAndHandler",
            "DeferredHeldException",
            "Cancellation",
            "Timeouts",
            "StartModes",
            "ScopeEdgeCases",
            "Threads",
            "VirtualThreads",
            "BlockingInCoroutine",
            "DispatcherThreads",
            "MixedJavaKotlin",
        )

        fun check(sample: String, run: AgentRun, mayUpdate: Boolean) {
            val snapshot = run.snapshot
            val problems = snapshot.diagnostics.filter { it.severity != Diagnostic.Severity.INFO }
            if (problems.isNotEmpty()) fail("The agent reported problems:\n" + problems.joinToString("\n") { it.message } + "\n\n" + run.output)

            val actual = renderer(snapshot, keepMonitorContention = sample == "MonitorContention").render(snapshot)
            val golden = File(TestEnvironment.goldenDir, "$sample.txt")
            if (mayUpdate && TestEnvironment.updateGoldens) {
                golden.parentFile.mkdirs()
                golden.writeText(actual)
                return
            }
            if (!golden.exists()) fail("No golden tree for $sample. Run with -PupdateGoldens and review ${golden.path}. Actual tree:\n$actual")
            assertEquals(golden.readText(), actual, "Tree of $sample differs from ${golden.path}. Program output:\n${run.output}")
        }

        private fun isRuntimeInfrastructure(node: NodeSnapshot): Boolean = when (node.info.kind) {
            NodeKind.POOL -> true
            NodeKind.THREAD -> node.info.origin != Origin.PROJECT && node.info.name != "main"
            else -> false
        }

        /**
         * Threads also run into each other on monitors that are not the program's: two threads that load or
         * initialize classes at the same moment contend on the JVM's locks for that, and the innermost frame is then
         * whatever line of the program happened to touch the class first. True, reported, and different in every run.
         * So golden trees leave monitor contention out, except in the one sample that is about it, and there they
         * keep what happens in the sample's own code.
         */
        private fun renderer(snapshot: TraceSnapshot, keepMonitorContention: Boolean): TreeRenderer {
            val incidental = HashSet<Long>()
            val blockedIncidentally = HashSet<Long>()
            for (event in snapshot.events) {
                if (event.kind == EventKind.THREAD_BLOCKED && event.blockReason == BlockReason.MONITOR) {
                    val where = event.stack.firstOrNull()?.let(snapshot::frame)
                    if (!keepMonitorContention || where == null || !where.className.startsWith("samples.")) {
                        incidental += event.seq
                        blockedIncidentally += event.nodeId
                    }
                } else if (event.kind == EventKind.THREAD_UNBLOCKED && blockedIncidentally.remove(event.nodeId)) {
                    incidental += event.seq
                }
            }
            return TreeRenderer(includeNode = { !isRuntimeInfrastructure(it) }, includeEvent = { it.seq !in incidental })
        }
    }
}

/** One run per sample per test JVM, shared by the test classes that look at the corpus from different angles. */
object SampleRuns {
    private val runs = ConcurrentHashMap<String, AgentRun>()

    operator fun get(sample: String): AgentRun = runs.computeIfAbsent(sample) { runUnderAgent("samples.${it}Kt") }
}
