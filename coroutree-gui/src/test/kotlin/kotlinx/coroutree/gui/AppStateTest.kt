package kotlinx.coroutree.gui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutree.gui.demo.DemoTrace
import kotlinx.coroutree.gui.ide.IdeOpener
import kotlinx.coroutree.gui.ide.RunningIde
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.SessionRegistry
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.SourceFile
import kotlinx.coroutree.model.SourceIndex
import kotlinx.coroutree.model.SourceModule
import kotlinx.coroutree.model.StackFrameDef
import kotlinx.coroutree.model.TraceHeader
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.title
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What outlives a single trace: which one is open, the registry on offer, the way to the IDE, the note at the bottom. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val temp: File = Files.createTempDirectory("coroutree-app").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        temp.deleteRecursively()
    }

    /** The note that an answer from the IDE leaves, once it has come. */
    private fun AppState.awaitNote(): String = runBlocking {
        withTimeout(10_000) {
            while (message.isNullOrEmpty()) delay(5)
            message!!
        }
    }

    /** AppState.message: "Transient, self-dismissing note". */
    @Test
    fun aNoteGoesAwayByItselfAndANewerOneStartsItsOwnTime() = runTest {
        val app = AppState(backgroundScope, IdeOpener(), SessionRegistry(temp))
        assertNull(app.message)
        app.show("first")
        advanceTimeBy(4_000)
        assertEquals("first", app.message)
        app.show("second")
        advanceTimeBy(4_000) // the first note's time is up. It must not take the second one with it
        runCurrent()
        assertEquals("second", app.message)
        advanceTimeBy(1_001)
        runCurrent()
        assertNull(app.message)
    }

    @Test
    fun openingAnotherTraceHangsUpOnTheSessionThatWasOpen() {
        val hungUp = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        val input = client.getInputStream()
                        // The token, then nothing until the other side is gone.
                        input.readAllBytes()
                        hungUp.countDown()
                    }
                }
            }
            val app = AppState(scope, IdeOpener(), SessionRegistry(temp))
            assertNull(app.current)
            app.close() // nothing open: nothing happens
            app.disconnect()
            app.reconnect()

            val session = TraceSource.Session(SessionInfo(null, 1, server.localPort, "secret", null, ":run", "b", "Main", 1, ended = false))
            app.open(session)
            val first = assertNotNull(app.current)
            assertEquals(session, first.feed.source)
            runBlocking { withTimeout(10_000) { first.feed.status.first { it == FeedStatus.LIVE } } }

            app.open(TraceSource.Demo(live = false))
            val second = assertNotNull(app.current)
            assertNotSame(first, second)
            assertNotSame(first.viewModel, second.viewModel, "a trace on screen has its own selection and viewport")
            assertTrue(hungUp.await(10, TimeUnit.SECONDS), "the JVM was left with a GUI that no longer looks at it")

            app.close()
            assertNull(app.current)
        }
    }

    /** Disconnect leaves the JVM alone and the trace on screen. Reconnect attaches to the same JVM with the same view. */
    @Test
    fun disconnectingKeepsTheTraceOnScreenAndReconnectingKeepsTheView() {
        val connections = LinkedBlockingQueue<String>()
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    while (true) server.accept().use { client ->
                        val frames = DemoTrace.frames()
                        client.getOutputStream().apply { write(traceBytes(frames.take(frames.indexOfFirst { it.event != null } + 1))); flush() }
                        connections += client.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                        client.getInputStream().readAllBytes() // until the GUI hangs up
                        connections += "hung up"
                    }
                }
            }
            val app = AppState(scope, IdeOpener(), SessionRegistry(temp))
            app.open(TraceSource.Session(SessionInfo(null, 1, server.localPort, "secret", null, ":run", "b", "Main", 1, ended = false, paceable = true)))
            val first = assertNotNull(app.current)
            runBlocking { withTimeout(10_000) { first.feed.status.first { it == FeedStatus.LIVE } } }
            assertEquals("secret", connections.poll(10, TimeUnit.SECONDS))
            runBlocking { withTimeout(10_000) { first.feed.snapshot.first { it.nodes.isNotEmpty() } } }
            first.viewModel.snapshot = first.feed.snapshot.value
            first.viewModel.selectNode(first.viewModel.snapshot.nodes.keys.first())

            app.disconnect()
            runBlocking { withTimeout(10_000) { first.feed.status.first { it == FeedStatus.DISCONNECTED } } }
            assertEquals("hung up", connections.poll(10, TimeUnit.SECONDS))
            assertSame(first, app.current, "the trace is still on screen")
            assertNotNull(first.feed.snapshot.value.header, "as far as it was seen")

            app.reconnect()
            val second = assertNotNull(app.current)
            assertNotSame(first, second)
            assertSame(first.viewModel, second.viewModel, "selection and viewport survive")
            assertEquals(first.feed.source, second.feed.source)
            assertTrue(first.shows(TraceSnapshot.EMPTY, FeedStatus.CONNECTING), "a trace opened fresh is shown from its first snapshot")
            assertFalse(second.shows(TraceSnapshot.EMPTY, FeedStatus.CONNECTING), "the view keeps what it has until the replay has caught up")
            assertFalse(second.shows(TraceSnapshot.EMPTY, FeedStatus.LIVE))
            assertTrue(second.shows(first.feed.snapshot.value, FeedStatus.LIVE), "caught up")
            assertTrue(second.shows(TraceSnapshot.EMPTY, FeedStatus.FAILED), "a feed that is over has shown all it will")
            runBlocking { withTimeout(10_000) { second.feed.status.first { it == FeedStatus.LIVE } } }
            assertEquals("secret", connections.poll(10, TimeUnit.SECONDS), "the same JVM, from the first byte")

            app.close()
            assertEquals("hung up", connections.poll(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aFrameOpensAtItsLineIfItIsProjectCodeAndByClassNameIfItIsNot() {
        val source = File(temp, "app/src/Main.kt").apply { parentFile.mkdirs(); writeText("fun main() {}") }
        val commands = mutableListOf<List<String>>()
        val idea = RunningIde("IntelliJ IDEA", "2026.3", File(temp, "idea"), listOf(temp))
        val app = AppState(scope, IdeOpener(runningIdes = { listOf(idea) }, run = { commands += it; IdeOpener.Run(0, "") }), SessionRegistry(temp))
        val projectFrame = StackFrameDef(1, "demo.MainKt", "main", "Main.kt", 3)
        val lambdaFrame = StackFrameDef(2, "kotlinx.coroutines.BuildersKt${'$'}launch${'$'}1", "invokeSuspend", "Builders.kt", 1)

        app.openInIde(projectFrame)
        assertNull(app.message, "no trace is open: nothing to go to")
        assertEquals(emptyList(), commands)

        app.open(TraceSource.Demo(live = false))
        val index = SourceIndex(listOf(SourceModule(":app", File(temp, "app").path, listOf(SourceFile("demo", "Main.kt", "src/Main.kt"), SourceFile("demo", "Gone.kt", "src/Gone.kt")))))
        app.current!!.viewModel.snapshot = snapshotOf(listOf(Frame(header = TraceHeader(sourceIndex = index, projectDir = temp.path))))

        app.openInIde(projectFrame)
        assertEquals("Opened src/Main.kt:3 in IntelliJ IDEA 2026.3", app.awaitNote())
        assertEquals(listOf(idea.launcher.path, "--line", "3", source.path), commands.single())

        app.show("")
        commands.clear()
        app.openInIde(lambdaFrame)
        assertEquals("Opened kotlinx.coroutines.BuildersKt in IntelliJ IDEA 2026.3", app.awaitNote(), "the top-level class of the lambda")
        assertEquals(listOf(idea.launcher.path, "jetbrains://idea/navigate/reference?project=${temp.name}&fqn=kotlinx.coroutines.BuildersKt"), commands.single())

        // In the index, but not on this machine (a trace recorded elsewhere).
        app.show("")
        app.openInIde(StackFrameDef(3, "demo.GoneKt", "f", "Gone.kt", 9))
        val missing = app.awaitNote()
        assertTrue("No such file" in missing && "Gone.kt" in missing, missing)
        assertNotNull(app.current, "the trace stays open whatever the IDE says")
    }

    @Test
    fun aNodeOpensAtItsSiteAndOneWithoutASiteSaysSo() {
        val commands = mutableListOf<List<String>>()
        val app = AppState(scope, IdeOpener(runningIdes = { emptyList() }, run = { commands += it; IdeOpener.Run(0, "") }), SessionRegistry(temp))
        app.open(TraceSource.Demo(live = false))
        val snapshot = demoSnapshot()
        app.current!!.viewModel.snapshot = snapshot

        app.openNodeInIde(snapshot.named("payment").id)
        assertEquals(IdeOpener.NO_IDE, app.awaitNote(), "no IDE runs: the note says so instead of nothing happening")

        app.show("")
        val siteless = snapshot.nodes.values.first { it.info.siteFrame == 0 }
        app.openNodeInIde(siteless.id)
        assertEquals("${siteless.title} has no source location in the trace", app.message)
        assertEquals(emptyList(), commands)
    }
}
