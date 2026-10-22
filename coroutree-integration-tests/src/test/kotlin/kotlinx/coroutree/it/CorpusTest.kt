package kotlinx.coroutree.it

import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.HandledBy
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.tree.CrossLink
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Properties of the corpus as a whole, and of the agent across library versions. */
class CorpusTest {
    @Test
    fun corpusExercisesEverythingTheAgentReports() {
        val snapshots = GoldenTreeTest.SAMPLES.map { SampleRuns[it].snapshot }
        val events = snapshots.flatMap { it.events }
        val nodes = snapshots.flatMap { it.nodes.values }

        // A child's failure reaches its parent as EXCEPTION_PROPAGATED, never as CANCELLATION_PROPAGATED child to parent.
        assertEquals(EventKind.entries.toSet() - EventKind.UNSPECIFIED, events.map { it.kind }.toSet())
        // LiveStreamTest covers IO. MONITOR needs the native probe, which a build has for its own platform only.
        val unreported = setOfNotNull(BlockReason.UNSPECIFIED, BlockReason.IO, BlockReason.MONITOR.takeIf { MonitorProbeLibrary.file == null })
        assertEquals(BlockReason.entries.toSet() - unreported, events.map { it.blockReason }.toSet() - BlockReason.UNSPECIFIED)
        assertEquals(HandledBy.entries.toSet() - HandledBy.UNSPECIFIED, events.map { it.handledBy }.toSet() - HandledBy.UNSPECIFIED)
        // TASK nodes come with M3 (executors) and M6 (StructuredTaskScope).
        assertEquals(NodeKind.entries.toSet() - setOf(NodeKind.UNSPECIFIED, NodeKind.TASK), nodes.map { it.info.kind }.toSet())
        assertTrue(nodes.map { it.state }.toSet().containsAll(setOf(NodeState.ACTIVE, NodeState.COMPLETED, NodeState.FAILED, NodeState.CANCELLED)))
        assertEquals(CrossLink.Kind.entries.toSet(), nodes.flatMap { it.links }.map { it.kind }.toSet())
    }

    @Test
    fun sequenceNumbersAreDenseAndTimeMovesForward() {
        for (sample in GoldenTreeTest.SAMPLES) {
            val events = SampleRuns[sample].snapshot.events
            assertEquals((1L..events.size).toList(), events.map { it.seq }, sample)
            assertTrue(events.all { it.timeNanos >= 0 && it.threadId != 0L }, sample)
        }
    }

    /** The hooks depend on library internals. HookTable.TESTED_COROUTINES defines the tested version range. */
    @TestFactory
    fun treesAreTheSameWithOlderCoroutines(): List<DynamicTest> =
        OTHER_VERSIONS.flatMap { version ->
            VERSION_SENSITIVE_SAMPLES.map { sample ->
                dynamicTest("$sample with kotlinx.coroutines $version") {
                    val run = runUnderAgent("samples.${sample}Kt", classpath = TestEnvironment.samplesClasspathWith(version), runName = "$sample-$version")
                    GoldenTreeTest.check(sample, run, mayUpdate = false)
                }
            }
        }

    /**
     * Gradle enables assertions, which activates kotlinx.coroutines debug mode. It adds coroutine ids to the context
     * and copies exceptions with recovered stacks. These library changes must stay out of the trace.
     */
    @TestFactory
    fun treesAreTheSameInCoroutinesDebugMode(): List<DynamicTest> =
        VERSION_SENSITIVE_SAMPLES.map { sample ->
            dynamicTest("$sample with -ea") {
                GoldenTreeTest.check(sample, runUnderAgent("samples.${sample}Kt", jvmArgs = listOf("-ea"), runName = "$sample-ea"), mayUpdate = false)
            }
        }

    private companion object {
        val OTHER_VERSIONS = listOf("1.10.2", "1.9.0")

        // The ones that lean on the hooks into JobSupport internals and the builders' stack shapes.
        val VERSION_SENSITIVE_SAMPLES = listOf(
            "StructuredConcurrency", "ContextAndDispatchers", "ExceptionPropagation", "SupervisorAndHandler",
            "DeferredHeldException", "Cancellation", "Timeouts", "StartModes", "ScopeEdgeCases", "BlockingInCoroutine",
            "SuspendMain", "InlineFunctions",
            "AsyncFailures", "CancellationCorners", "ContextNesting", "HandlerPlacement", "UnstructuredJobs", "StartAndYield",
            "ProduceAndSelect", "BlockingOnDispatchers", "InlineAcrossFiles",
            // Not HostileObjects: in debug mode the library itself copies exceptions and reads the message of one whose
            // message throws. What happens then is the program's and the library's affair, and no golden of ours.
        )
    }
}
