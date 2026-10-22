package kotlinx.coroutree.runtime

import com.acme.app.App
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the agent reads off a stack (TRACE_FORMAT, "Source site"). The site of a new node is the innermost frame
 * outside the JDK, the Kotlin standard library, kotlinx.coroutines and the agent's runtime. The construct is the
 * API that the site called.
 */
internal class StackCaptureTest {
    @Test
    fun theSiteIsTheInnermostFrameOutsideTheConcurrencyRuntimes() {
        val cases = listOf(
            1 to listOf("kotlinx.coroutines.BuildersKt.launch", "com.acme.MainKt.main"),
            0 to listOf("com.acme.MainKt.main", "java.lang.Thread.run"),
            3 to listOf("jdk.internal.misc.Unsafe.park", "java.util.concurrent.locks.LockSupport.park", "sun.nio.ch.Net.poll", "org.acme.Server.accept"),
            2 to listOf("kotlin.coroutines.ContinuationKt.startCoroutine", "javax.swing.Timer.start", "Main.main"),
            1 to listOf("kotlinx.coroutree.runtime.Hooks.threadStart", "kotlinx.serialization.json.Json.decode"),
            // The prefixes are package names, not string prefixes.
            0 to listOf("kotlinx.coroutinesx.Thing.run"),
            0 to listOf("javafx.application.Platform.runLater"),
            0 to listOf("kotlinpoet.Gen.run"),
            -1 to listOf("kotlinx.coroutines.DefaultExecutor.run", "java.lang.Thread.run"),
            -1 to emptyList(),
        )
        for ((index, frames) in cases) {
            assertEquals(index, StackCapture.siteIndex(stack(frames)), frames.joinToString(" < "))
        }
    }

    @Test
    fun theConstructIsTheApiTheSiteCalledNamedAsItIsWritten() {
        val site = "com.acme.MainKt.main"
        val cases = listOf(
            "launch" to listOf("kotlinx.coroutines.BuildersKt__Builders_commonKt.launch", "kotlinx.coroutines.BuildersKt.launch", site),
            "launch" to listOf("kotlinx.coroutines.BuildersKt.launch\$default", site),
            "withContext" to listOf("kotlinx.coroutines.BuildersKt.withContext", site),
            // Kotlin names factory functions like the type they return.
            "Job()" to listOf("kotlinx.coroutines.JobKt.Job", site),
            "SupervisorJob()" to listOf("kotlinx.coroutines.SupervisorKt.SupervisorJob\$default", site),
            "CoroutineScope()" to listOf("kotlinx.coroutines.CoroutineScopeKt.CoroutineScope", site),
            // The name in the source, where @JvmName gave the function another one in bytecode.
            "runBlocking" to listOf("kotlinx.coroutines.BuildersKt.runBlockingK", site),
            "suspend fun main" to listOf("kotlin.coroutines.jvm.internal.RunSuspendKt.runSuspend", site),
            "startCoroutine" to listOf("kotlin.coroutines.ContinuationKt.startCoroutine", site),
            "thread" to listOf(START, "kotlin.concurrent.ThreadsKt.thread", site),
            "Thread.start" to listOf(START, site),
            "Thread.ofVirtual().start" to listOf("java.lang.VirtualThread.start", "java.lang.ThreadBuilders\$VirtualThreadBuilder.start", site),
            "Thread.ofPlatform().start" to listOf(START, "java.lang.ThreadBuilders\$PlatformThreadBuilder.start", site),
            "Thread.start" to listOf("java.lang.VirtualThread.start", site),
            "Thread.startVirtualThread" to listOf("java.lang.VirtualThread.start", "java.lang.Thread.startVirtualThread", site),
            "ThreadPoolExecutor.execute" to listOf(START, "java.util.concurrent.ThreadPoolExecutor.addWorker", "java.util.concurrent.ThreadPoolExecutor.execute", site),
            "CompletableFuture.supplyAsync" to listOf("java.util.concurrent.CompletableFuture.supplyAsync", site),
            // A constructor names no construct, so the fallback stands.
            "fallback" to listOf("kotlinx.coroutines.JobImpl.<init>", site),
            // An inline frame without a name between the site and the API is skipped.
            "launch" to listOf("kotlinx.coroutines.BuildersKt.launch", "~kotlinx.coroutines.flow.FlowKt.", site),
            // No frame under the site: the hook was called from the site itself.
            "fallback" to listOf(site, "java.lang.Thread.run"),
        )
        for ((construct, frames) in cases) {
            val stack = stack(frames)
            assertEquals(construct, StackCapture.construct(stack, StackCapture.siteIndex(stack), "fallback"), frames.joinToString(" < "))
        }
    }

    @Test
    fun aThreadIsTheProjectsOnlyWhenTheSiteCalledAThreadStartingApiDirectly() {
        val site = "com.acme.MainKt.main"
        val cases = listOf(
            true to listOf(START, site),
            true to listOf(START, "kotlin.concurrent.ThreadsKt.thread", site),
            true to listOf(START, "kotlin.concurrent.ThreadsKt.thread\$default", site),
            true to listOf("java.lang.VirtualThread.start", "java.lang.ThreadBuilders\$VirtualThreadBuilder.start", site),
            true to listOf("java.lang.VirtualThread.start", "java.lang.Thread.startVirtualThread", site),
            // Inlined into a thread-starting API: part of it.
            true to listOf(START, "~kotlin.concurrent.ThreadsKt.thread", "kotlin.concurrent.ThreadsKt.thread", site),
            // A frame of another kind between the site and the start (the timer thread of delay, a pool that grows, a JDK helper): the thread isn't the project's.
            false to listOf(START, "kotlinx.coroutines.DefaultExecutor.createThreadSync", "kotlinx.coroutines.DelayKt.delay", site),
            false to listOf(START, "java.util.concurrent.ThreadPoolExecutor.addWorker", "java.util.concurrent.ThreadPoolExecutor.execute", site),
            false to listOf(START, "java.lang.VirtualThread.<clinit>", "java.lang.ThreadBuilders\$VirtualThreadBuilder.start", site),
            false to listOf("java.lang.Thread.run", site),
            // No frame under the site, and no site.
            false to listOf(site, START),
            false to listOf(START, "java.lang.Thread.run"),
        )
        for ((direct, frames) in cases) {
            val stack = stack(frames)
            assertEquals(direct, StackCapture.startedDirectlyBySite(stack, StackCapture.siteIndex(stack)), frames.joinToString(" < "))
        }
    }

    @Test
    fun aCapturedStackStartsAtTheProgramsFrameAndHasAtMostAsManyFramesAsAsked() {
        val stack = App.capturedStack(4)
        assertEquals(4, stack.size)
        assertEquals("com.acme.app.App.capturedStack", stack[0], "the agent's own frames are cut off the top")
        assertEquals(
            "kotlinx.coroutree.runtime.StackCaptureTest.aCapturedStackStartsAtTheProgramsFrameAndHasAtMostAsManyFramesAsAsked",
            stack[1],
            "and only off the top: a frame of that package further down is somebody's caller"
        )
        assertEquals(listOf("com.acme.app.App.capturedStack"), App.capturedStack(1))
        val whole = ArrayList<String>()
        App.deep(5) { whole.addAll(App.capturedStack(10_000)) }
        assertTrue(whole.size > 8 && whole.size < 10_000, "a stack shorter than the limit is the whole stack: ${whole.size}")
    }

    @Test
    fun theLimitOfAStackCountsLogicalFramesAndAnInlineBodyIsOne() {
        // Line 36 of this class is the copy of an inline function's body, called at line 14.
        SourceMaps.register(
            "com.acme.limit.MainKt",
            "SMAP\nMain.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Main.kt\ncom/acme/limit/MainKt\n+ 2 Util.kt\ncom/acme/limit/UtilKt\n" +
                "*L\n1#1,35:1\n23#2,3:36\n*S KotlinDebug\n*F\n+ 1 Main.kt\ncom/acme/limit/MainKt\n*L\n14#1:36,3\n*E\n",
            emptyMap()
        )
        val inlined = StackTraceElement("com.acme.limit.MainKt", "main", "Main.kt", 36)
        val plain = StackTraceElement("com.acme.limit.MainKt", "main", "Main.kt", 20)
        assertEquals(5, StackFrameRef.of(arrayOf(inlined, inlined, plain), 10).size, "the body and the call site, twice, and one frame as it is")
        val cut = StackFrameRef.of(arrayOf(plain, inlined, inlined, plain), 2)
        assertEquals(2, cut.size, "never more than the limit, even when the last JVM frame taken is two")
        assertFalse(cut[0].inlined)
        assertTrue(cut[1].inlined)
        assertEquals(0, StackFrameRef.of(emptyArray(), 5).size)
    }

    @Test
    fun framesAreInternedByValue() {
        val frame = StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12)
        assertEquals(frame, StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12))
        assertEquals(frame.hashCode(), StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12).hashCode())
        assertNotEquals(frame, StackFrameRef("com.acme.MainKt", "main", "Main.kt", 13))
        assertNotEquals(frame, StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12, true), "the body of an inline function is another frame than the method's own line")
        assertNotEquals(frame, StackFrameRef("com.acme.MainKt", "main", "Other.kt", 12))
        assertNotEquals(frame, StackFrameRef("com.acme.MainKt", "main2", "Main.kt", 12))
        assertNotEquals(frame, StackFrameRef("com.acme.Main", "main", "Main.kt", 12))
        // A negative line (native or unknown) becomes 0, and a missing file name becomes an empty one.
        assertEquals(StackFrameRef("C", "m", "", 0), StackFrameRef("C", "m", null, -2))
    }

    companion object {
        private const val START = "java.lang.Thread.start"

        /** `Class.method` frames, innermost first. A leading `~` marks the body of an inline function. */
        private fun stack(frames: List<String>): Array<StackFrameRef> = Array(frames.size) { i ->
            val inlined = frames[i].startsWith("~")
            val frame = frames[i].removePrefix("~")
            val dot = frame.lastIndexOf('.')
            StackFrameRef(frame.substring(0, dot), frame.substring(dot + 1), "File.kt", 10 + i, inlined)
        }
    }
}
