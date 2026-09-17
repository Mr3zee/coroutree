package kotlinx.coroutree.it

import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.tree.qualified
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The native monitor probe: both ways of getting it into the JVM, and doing without. */
class MonitorProbeTest {
    private fun AgentRun.monitorBlocks() = snapshot.events.filter { it.kind == EventKind.THREAD_BLOCKED && it.blockReason == BlockReason.MONITOR }

    @Test
    fun contentionIsReportedWhereItHappensAndOnTheCoroutineThatRanIntoIt() {
        assumeTrue(MonitorProbeLibrary.file != null, "no monitor probe for this platform in the agent under test")
        val run = SampleRuns["MonitorContention"]
        val snapshot = run.snapshot

        val contender = snapshot.nodes.values.single { it.info.name == "contender" }
        val blocked = run.monitorBlocks().single { it.nodeId == contender.id }
        val where = assertNotNull(snapshot.frame(blocked.stack.first()))
        assertTrue(where.qualified.startsWith("samples.MonitorContentionKt"), "the stack starts at the synchronized block: ${where.qualified}")
        assertEquals("MonitorContention.kt", where.fileName)

        val coroutine = snapshot.nodes.values.single { it.info.name == "contending coroutine" }
        assertTrue(run.monitorBlocks().any { it.otherNodeId == coroutine.id }, "the coroutine that was running on the blocked thread is named")
    }

    /** Without -agentpath the agent loads the probe itself. Same trace; on JDK 24+ the JVM adds its native-access warning. */
    @Test
    fun agentLoadsTheProbeItselfWhenNobodyPassedAgentPath() {
        assumeTrue(MonitorProbeLibrary.file != null, "no monitor probe for this platform in the agent under test")
        val run = runUnderAgent("samples.MonitorContentionKt", runName = "MonitorContention-load", monitorProbeAsAgentPath = false)
        GoldenTreeTest.check("MonitorContention", run, mayUpdate = false)
    }

    @Test
    fun canBeSwitchedOff() {
        val run = runUnderAgent("samples.MonitorContentionKt", runName = "MonitorContention-off", agentOptions = mapOf("monitor" to "false"))
        assertEquals(0, run.exitCode, run.output)
        assertEquals(emptyList(), run.monitorBlocks())
        assertEquals(emptyList(), run.snapshot.diagnostics.filter { it.severity != Diagnostic.Severity.INFO })
    }
}
