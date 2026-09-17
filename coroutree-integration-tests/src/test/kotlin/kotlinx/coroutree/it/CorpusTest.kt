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
    /**
     * The corpus exists to exercise everything the agent can report. If a kind of event, node or link stops showing
     * up in any sample, either the agent lost a hook or the corpus lost a case — both worth a red test.
     */
    @Test
    fun corpusExercisesEverythingTheAgentReports() {
        val snapshots = GoldenTreeTest.SAMPLES.map { SampleRuns[it].snapshot }
        val events = snapshots.flatMap { it.events }
        val nodes = snapshots.flatMap { it.nodes.values }

        // CANCELLATION_PROPAGATED child → parent is static-mode vocabulary: dynamically it is an EXCEPTION_PROPAGATED.
        assertEquals(EventKind.entries.toSet() - EventKind.UNSPECIFIED, events.map { it.kind }.toSet())
        // IO is covered by LiveStreamTest; MONITOR takes the native probe, which a build has for its own platform at best.
        val unreported = setOfNotNull(BlockReason.UNSPECIFIED, BlockReason.IO, BlockReason.MONITOR.takeIf { MonitorProbeLibrary.file == null })
        assertEquals(BlockReason.entries.toSet() - unreported, events.map { it.blockReason }.toSet() - BlockReason.UNSPECIFIED)
        assertEquals(HandledBy.entries.toSet() - HandledBy.UNSPECIFIED, events.map { it.handledBy }.toSet() - HandledBy.UNSPECIFIED)
        // TASK is M2 (executors, StructuredTaskScope).
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

    /** The hook table names library internals; see HookTable.TESTED_COROUTINES for the range this keeps honest. */
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
     * With assertions enabled — which is how Gradle runs tests — kotlinx.coroutines is in debug mode: it numbers
     * coroutines through an extra context element and rethrows exceptions as copies with recovered stack traces.
     * None of that is the program's doing, and none of it may show.
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
        )
    }
}
