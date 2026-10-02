package kotlinx.coroutree.it

import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.tree.TraceStore
import org.junit.jupiter.api.Timeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The agent in programs and under options that do not go the easy way: a JVM that leaves in the middle of everything,
 * options that make no sense, and a program that makes and drops objects by the thousand, none of which the agent may
 * keep (CLAUDE.md: "Do not hold application objects (Jobs, Threads, Throwables, context elements) longer than the job does").
 */
@Timeout(180)
class AgentRobustnessTest {
    @Test
    fun systemExitMidFlightLeavesACompleteTraceOfAnUnfinishedProgram() {
        val run = runUnderAgent("samples.ExitMidFlightKt", programArgs = listOf("exit"), runName = "ExitMidFlight-exit")
        assertEquals(7, run.exitCode, run.output)
        val snapshot = run.snapshot
        assertEquals(emptyList(), snapshot.diagnostics.filter { it.severity != Diagnostic.Severity.INFO }.map { it.message })
        assertEquals((1L..snapshot.events.size).toList(), snapshot.events.map { it.seq }, "the shutdown hook writes out everything that happened")

        fun state(name: String) = snapshot.nodes.values.single { it.info.name == name }.state
        assertEquals(NodeState.BLOCKED, state("main"), "blocked in runBlocking to the end")
        assertEquals(NodeState.BLOCKED, state("sleeper"))
        assertEquals(NodeState.SUSPENDED, state("waiting"))
        assertFalse(state("ticker").isFinal)
        assertFalse(state("quitter").isFinal, "the coroutine that called System.exit never came back from it")
        // Nothing is made up for the occasion: nobody finished, nobody was cancelled.
        val unfinished = setOf("main", "sleeper", "waiting", "ticker", "quitter")
        val ended = snapshot.events.filter { it.kind == EventKind.FINISHED || it.kind == EventKind.CANCELLING }.mapNotNull { snapshot.node(it.nodeId)?.info?.name }
        assertTrue(ended.none { it in unfinished }, ended.toString())
        assertTrue(snapshot.nodes.values.single { it.info.name == "ticker" }.events.count { it.kind == EventKind.RESUMED } >= 10)
    }

    /** TRACE_FORMAT: "A trace is append-only and its writer may die at any moment." Runtime.halt runs no shutdown hook. */
    @Test
    fun haltMidFlightLeavesAReadableTraceThatIsAtMostAMomentBehind() {
        val run = runUnderAgent("samples.ExitMidFlightKt", programArgs = listOf("halt"), runName = "ExitMidFlight-halt")
        assertEquals(7, run.exitCode, run.output)
        val snapshot = run.traceFile.inputStream().use(TraceStore::read) // must not throw, whatever the last frame looks like
        assertNotNull(snapshot.header)
        // Everything was started 200 ms before the end, and the writer flushes whenever it runs dry.
        for (name in listOf("sleeper", "ticker", "waiting", "quitter")) {
            assertTrue(snapshot.nodes.values.any { it.info.name == name && !it.placeholder }, "$name is not in the trace")
        }
        val seqs = snapshot.events.map { it.seq }
        assertEquals(seqs.sorted().distinct(), seqs, "in order, none twice")
        // Threads race between taking a number and handing the event over, so the very end may have a hole. Not the rest.
        val firstGap = seqs.withIndex().firstOrNull { (index, seq) -> seq != index + 1L }?.index ?: seqs.size
        assertTrue(seqs.size - firstGap < 64, "a hole in the sequence ${seqs.size - firstGap} events before the end")
        assertTrue(snapshot.nodes.values.single { it.info.name == "ticker" }.events.count { it.kind == EventKind.RESUMED } >= 5)
    }

    @Test
    fun optionsThatMakeNoSenseAreWarningsInTheTraceAndTheProgramRuns() {
        val dir = File(TestEnvironment.workDir, "NonsenseOptions").apply { deleteRecursively(); mkdirs() }
        val trace = File(dir, "trace.ctrace")
        val process = ProcessBuilder(
            TestEnvironment.java,
            "-javaagent:${TestEnvironment.agentJar}=trace.file=$trace,live=false,include=samples,nonsense,pace.events.per.second=fast," +
                "stack.depth=-3,config=${File(dir, "no-such.properties")}",
            "-cp", TestEnvironment.samplesClasspath, "samples.StructuredConcurrencyKt",
        ).directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "output.txt")).start()
        val output = { File(dir, "output.txt").readText() }
        assertEquals(0, process.waitFor(), output())
        assertTrue("total = 3" in output(), output())

        val snapshot = trace.inputStream().use(TraceStore::read)
        assertEquals(emptyList(), snapshot.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }.map { it.message })
        val warnings = snapshot.diagnostics.filter { it.severity == Diagnostic.Severity.WARNING }.map { it.message }
        for (about in listOf("'nonsense'", "pace.events.per.second=fast", "stack.depth=-3", "no-such.properties")) {
            assertEquals(1, warnings.count { about in it }, "one warning about $about in $warnings")
            assertTrue(warnings.single { about in it } in output(), "and on the console")
        }
        // What could be understood was: the trace went where it was told, with the project's packages, unpaced.
        assertEquals(listOf("samples"), snapshot.header?.includePackages)
        assertEquals(false, snapshot.header?.paceable)
        assertEquals(setOf("greeting", "printer", "left", "right"), snapshot.nodes.values.map { it.info.name }.filter { it.isNotEmpty() && it != "main" }.toSet())
    }

    /** With a gate in the JVM (the runtime's nodes then link to their parents and are known by id), and without one. */
    @Test
    fun whatAProgramHasDroppedIsNotKeptAliveByTheAgentWithAGate() = nothingIsKept("ShortLived-gate", mapOf("live" to "true"))

    @Test
    fun whatAProgramHasDroppedIsNotKeptAliveByTheAgentWithoutAGate() = nothingIsKept("ShortLived-no-gate", mapOf("live" to "false"))

    private fun nothingIsKept(runName: String, options: Map<String, String>) {
        val coroutines = 6000
        val threads = 300
        val started = startUnderAgent(
            "samples.ShortLivedKt", runName = runName, agentOptions = options, keepStdinOpen = true,
            programArgs = listOf(coroutines.toString(), threads.toString()),
        )
        try {
            val deadline = System.nanoTime() + 90_000_000_000
            while ("idle" !in started.output) {
                assertTrue(started.process.isAlive && System.nanoTime() < deadline, "the program did not get to rest:\n${started.output}\n${started.threadDump()}")
                Thread.sleep(50)
            }
            Thread.sleep(500) // the writer has written what was queued, which is the last that refers to anything
            // The histogram counts live objects: taking it collects everything else first.
            val histogram = started.jcmd("GC.class_histogram")
            fun instances(className: String): Int =
                histogram.lineSequence().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size >= 4 && it[3] == className }?.get(1)?.toInt() ?: 0
            assertTrue(instances("java.lang.String") > 100, "not a histogram:\n${histogram.take(2000)}")

            // The program's objects. A dispatcher's idle workers may still have their last task at hand: a handful, not thousands.
            for (className in listOf("samples.Baggage", "samples.ShortLivedFailure", "samples.ShortLivedThread", "kotlinx.coroutines.StandaloneCoroutine", "kotlinx.coroutines.DeferredCoroutine")) {
                val left = instances(className)
                assertTrue(left <= 32, "$left instances of $className are still alive after $coroutines coroutines and $threads threads have come and gone")
            }
            // And the agent's own: a node lives as long as what it stands for.
            val jobNodes = instances("kotlinx.coroutree.runtime.JobNode")
            assertTrue(jobNodes <= 64, "$jobNodes JobNodes are still alive")

            started.process.outputStream.use { it.write("\n".toByteArray()) }
            val run = started.await(60)
            assertEquals(0, run.exitCode, run.output)
            assertTrue("done" in run.output, run.output)
            val snapshot = run.snapshot
            assertEquals(emptyList(), snapshot.diagnostics.filter { it.severity != Diagnostic.Severity.INFO }.map { it.message })
            assertEquals((1L..snapshot.events.size).toList(), snapshot.events.map { it.seq })
            // The trace has them all, each with its end.
            val launched = snapshot.nodes.values.filter { it.info.construct == "launch" }
            assertEquals(coroutines + 10, launched.size)
            assertEquals(
                mapOf(NodeState.FAILED to (coroutines + 10 + 3) / 4, NodeState.CANCELLED to (coroutines + 10) / 4),
                launched.groupingBy { it.state }.eachCount().filterKeys { it != NodeState.COMPLETED },
            )
            val shortLived = snapshot.nodes.values.filter { it.info.name.startsWith("short-lived-") }
            assertEquals(threads + 2, shortLived.size)
            assertTrue(shortLived.all { it.state.isFinal })
        } finally {
            started.process.destroyForcibly()
        }
    }
}
