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
import kotlinx.coroutree.gui.ide.IdeOpener
import kotlinx.coroutree.gui.source.FeedStatus
import kotlinx.coroutree.gui.source.SessionInfo
import kotlinx.coroutree.gui.source.TraceSource
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.SourceFile
import kotlinx.coroutree.model.SourceIndex
import kotlinx.coroutree.model.SourceModule
import kotlinx.coroutree.model.StackFrameDef
import kotlinx.coroutree.model.TraceHeader
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What outlives a single trace: which one is open, the build directory on offer, the way to the IDE, the note at the bottom. */
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
        val app = AppState(backgroundScope, IdeOpener(), initialDir = null)
        assertNull(app.message)
        app.show("first")
        advanceTimeBy(4_000)
        assertEquals("first", app.message)
        app.show("second")
        advanceTimeBy(4_000) // the first note's time is up; it must not take the second one with it
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
            val app = AppState(scope, IdeOpener(), initialDir = null)
            assertNull(app.current)
            app.close() // nothing open: nothing happens

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

    @Test
    fun theBuildDirectoryOnOfferIsRememberedWhenChosenAndOnlyOfferedWhileItExists() {
        val remembered = ArrayList<File>()
        val gone = File(temp, "was-here")
        val app = AppState(scope, IdeOpener(), initialDir = gone, rememberDir = remembered::add)
        assertEquals(gone, app.dir)
        assertNull(app.coroutreeDir(), "last time's directory has been cleaned away")
        assertTrue(remembered.isEmpty(), "what was only restored is not written back")

        val chosen = File(temp, "build/coroutree").apply { mkdirs() }
        app.chooseDir(chosen)
        assertEquals(chosen, app.dir)
        assertEquals(chosen, app.coroutreeDir()?.dir)
        assertEquals(listOf(chosen), remembered)
    }

    @Test
    fun aFrameOpensInTheIdeIfItIsProjectCodeAndSaysSoIfItIsNot() {
        val source = File(temp, "app/src/Main.kt").apply { parentFile.mkdirs(); writeText("fun main() {}") }
        val marker = File(temp, "opened.txt")
        val app = AppState(scope, IdeOpener("/bin/sh -c 'echo \"$1:$2\" > \"$0\"' ${marker.path} {path} {line}"), initialDir = null)
        val projectFrame = StackFrameDef(1, "demo.MainKt", "main", "Main.kt", 3)
        val libraryFrame = StackFrameDef(2, "kotlinx.coroutines.BuildersKt", "launch", "Builders.kt", 1)

        app.openInIde(projectFrame)
        assertEquals("demo.MainKt is not part of the project sources", app.message, "no trace is open: nothing is known about sources")

        app.open(TraceSource.Demo(live = false))
        val index = SourceIndex(listOf(SourceModule(":app", File(temp, "app").path, listOf(SourceFile("demo", "Main.kt", "src/Main.kt"), SourceFile("demo", "Gone.kt", "src/Gone.kt")))))
        app.current!!.viewModel.snapshot = snapshotOf(listOf(Frame(header = TraceHeader(sourceIndex = index))))

        app.openInIde(libraryFrame)
        assertEquals("kotlinx.coroutines.BuildersKt is not part of the project sources", app.message)

        app.show("")
        app.openInIde(projectFrame)
        assertEquals("Opened src/Main.kt:3 in the IDE", app.awaitNote())
        assertEquals("${source.path}:3", marker.readText().trim())

        // In the index, but not on this machine (a trace recorded elsewhere).
        app.show("")
        app.openInIde(StackFrameDef(3, "demo.GoneKt", "f", "Gone.kt", 9))
        val missing = app.awaitNote()
        assertTrue("No such file" in missing && "Gone.kt" in missing, missing)
        assertNotNull(app.current, "the trace stays open whatever the IDE says")
    }
}
