package kotlinx.coroutree.it

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutree.gui.source.CoroutreeDir
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.source.TraceFeed
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.NodeState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The agent and the GUI meet for real: a program runs under the agent the way the Gradle plugin would start it, the
 * GUI's own discovery finds its session in the build directory, and the GUI's own feed follows it to the end.
 */
class GuiFollowsLiveJvmTest {
    @Test
    fun openLatestFindsTheRunningJvmAndFollowsItUntilItExits() {
        val buildDir = File(TestEnvironment.workDir, "GuiLive/coroutree").apply { deleteRecursively(); mkdirs() }
        val process = ProcessBuilder(
            TestEnvironment.java,
            "-javaagent:${TestEnvironment.agentJar}=trace.dir=$buildDir/traces/b1,sessions.dir=$buildDir/sessions/b1,include=samples,task.path=:run,build.id=b1",
            "-cp", TestEnvironment.samplesClasspath,
            "samples.InteractiveKt",
        ).directory(buildDir).redirectErrorStream(true).redirectOutput(File(buildDir, "output.txt")).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var source: TraceSource? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (source !is TraceSource.Session && System.nanoTime() < deadline) {
                Thread.sleep(20)
                source = CoroutreeDir(buildDir).latest() // a trace file at first: the agent opens it before it starts listening
            }
            assertIs<TraceSource.Session>(source, "the session of the running JVM was not found in $buildDir")
            assertEquals(process.pid(), source.info.pid)

            val feed = TraceFeed(source, scope)
            runBlocking {
                withTimeout(30_000) {
                    val blocked = feed.snapshot.first { snapshot ->
                        snapshot.events.any { it.kind == EventKind.THREAD_BLOCKED && it.blockReason == BlockReason.IO }
                    }
                    assertEquals(FeedStatus.LIVE, feed.status.value)
                    val prompt = blocked.nodes.values.single { it.info.name == "prompt" }
                    assertEquals(NodeState.BLOCKED, prompt.state, "the coroutine reading stdin is seen blocked while it is blocked")

                    process.outputStream.use { it.write("done\n".toByteArray()) }
                    feed.status.first { it == FeedStatus.ENDED }
                    val last = feed.snapshot.first { it.complete }
                    assertTrue(last.nodes.values.filter { it.info.origin == kotlinx.coroutree.model.Origin.PROJECT }.all { it.state.isFinal })
                    assertEquals(NodeState.COMPLETED, last.nodes.values.single { it.info.name == "prompt" }.state)
                    assertEquals(NodeState.CANCELLED, last.nodes.values.single { it.info.name == "ticker" }.state)
                }
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))

            // Afterwards the same directory offers the recorded trace instead.
            assertIs<TraceSource.TraceFile>(CoroutreeDir(buildDir).latest())
        } finally {
            scope.cancel()
            process.destroyForcibly()
        }
    }
}
