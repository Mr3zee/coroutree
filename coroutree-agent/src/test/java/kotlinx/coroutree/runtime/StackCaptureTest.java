package kotlinx.coroutree.runtime;

import com.acme.app.App;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the agent reads off a stack (TRACE_FORMAT, "Source site"): the site of a new node is the innermost frame
 * outside the JDK, the Kotlin standard library and kotlinx.coroutines; the construct is the API that frame called.
 */
class StackCaptureTest {
    /** {@code Class.method} frames, innermost first; a leading {@code ~} marks the body of an inline function. */
    private static StackFrameRef[] stack(String... frames) {
        StackFrameRef[] result = new StackFrameRef[frames.length];
        for (int i = 0; i < frames.length; i++) {
            boolean inlined = frames[i].startsWith("~");
            String frame = inlined ? frames[i].substring(1) : frames[i];
            int dot = frame.lastIndexOf('.');
            result[i] = new StackFrameRef(frame.substring(0, dot), frame.substring(dot + 1), "File.kt", 10 + i, inlined);
        }
        return result;
    }

    private static final String START = "java.lang.Thread.start";

    @Test
    void theSiteIsTheInnermostFrameOutsideTheConcurrencyRuntimes() {
        Object[][] cases = {
            {1, new String[] {"kotlinx.coroutines.BuildersKt.launch", "com.acme.MainKt.main"}},
            {0, new String[] {"com.acme.MainKt.main", "java.lang.Thread.run"}},
            {3, new String[] {"jdk.internal.misc.Unsafe.park", "java.util.concurrent.locks.LockSupport.park", "sun.nio.ch.Net.poll", "org.acme.Server.accept"}},
            {2, new String[] {"kotlin.coroutines.ContinuationKt.startCoroutine", "javax.swing.Timer.start", "Main.main"}},
            {1, new String[] {"kotlinx.coroutree.runtime.Hooks.threadStart", "kotlinx.serialization.json.Json.decode"}},
            // Prefixes are packages, not strings.
            {0, new String[] {"kotlinx.coroutinesx.Thing.run"}},
            {0, new String[] {"javafx.application.Platform.runLater"}},
            {0, new String[] {"kotlinpoet.Gen.run"}},
            {-1, new String[] {"kotlinx.coroutines.DefaultExecutor.run", "java.lang.Thread.run"}},
            {-1, new String[] {}},
        };
        for (Object[] testCase : cases) {
            String[] frames = (String[]) testCase[1];
            assertEquals(testCase[0], StackCapture.siteIndex(stack(frames)), String.join(" < ", frames));
        }
    }

    @Test
    void theConstructIsTheApiTheSiteCalledNamedAsItIsWritten() {
        String site = "com.acme.MainKt.main";
        Object[][] cases = {
            {"launch", new String[] {"kotlinx.coroutines.BuildersKt__Builders_commonKt.launch", "kotlinx.coroutines.BuildersKt.launch", site}},
            {"launch", new String[] {"kotlinx.coroutines.BuildersKt.launch$default", site}},
            {"withContext", new String[] {"kotlinx.coroutines.BuildersKt.withContext", site}},
            // Kotlin names factory functions like the type they return.
            {"Job()", new String[] {"kotlinx.coroutines.JobKt.Job", site}},
            {"SupervisorJob()", new String[] {"kotlinx.coroutines.SupervisorKt.SupervisorJob$default", site}},
            {"CoroutineScope()", new String[] {"kotlinx.coroutines.CoroutineScopeKt.CoroutineScope", site}},
            // The name in the source, where @JvmName gave the function another one in bytecode.
            {"runBlocking", new String[] {"kotlinx.coroutines.BuildersKt.runBlockingK", site}},
            {"suspend fun main", new String[] {"kotlin.coroutines.jvm.internal.RunSuspendKt.runSuspend", site}},
            {"startCoroutine", new String[] {"kotlin.coroutines.ContinuationKt.startCoroutine", site}},
            {"thread", new String[] {START, "kotlin.concurrent.ThreadsKt.thread", site}},
            {"Thread.start", new String[] {START, site}},
            {"Thread.ofVirtual().start", new String[] {"java.lang.VirtualThread.start", "java.lang.ThreadBuilders$VirtualThreadBuilder.start", site}},
            {"Thread.ofPlatform().start", new String[] {START, "java.lang.ThreadBuilders$PlatformThreadBuilder.start", site}},
            {"Thread.start", new String[] {"java.lang.VirtualThread.start", site}},
            {"Thread.startVirtualThread", new String[] {"java.lang.VirtualThread.start", "java.lang.Thread.startVirtualThread", site}},
            {"ThreadPoolExecutor.execute", new String[] {START, "java.util.concurrent.ThreadPoolExecutor.addWorker", "java.util.concurrent.ThreadPoolExecutor.execute", site}},
            {"CompletableFuture.supplyAsync", new String[] {"java.util.concurrent.CompletableFuture.supplyAsync", site}},
            // A constructor is no name to go by: what was constructed says it better.
            {"fallback", new String[] {"kotlinx.coroutines.JobImpl.<init>", site}},
            // A library's inline function between the site and the API, its name unknown.
            {"launch", new String[] {"kotlinx.coroutines.BuildersKt.launch", "~kotlinx.coroutines.flow.FlowKt.", site}},
            // Nothing below the site: the site is where the hook was called.
            {"fallback", new String[] {site, "java.lang.Thread.run"}},
        };
        for (Object[] testCase : cases) {
            StackFrameRef[] stack = stack((String[]) testCase[1]);
            assertEquals(testCase[0], StackCapture.construct(stack, StackCapture.siteIndex(stack), "fallback"), String.join(" < ", (String[]) testCase[1]));
        }
    }

    @Test
    void aThreadIsTheProjectsOnlyWhenTheSiteCalledAThreadStartingApiDirectly() {
        String site = "com.acme.MainKt.main";
        Object[][] cases = {
            {true, new String[] {START, site}},
            {true, new String[] {START, "kotlin.concurrent.ThreadsKt.thread", site}},
            {true, new String[] {START, "kotlin.concurrent.ThreadsKt.thread$default", site}},
            {true, new String[] {"java.lang.VirtualThread.start", "java.lang.ThreadBuilders$VirtualThreadBuilder.start", site}},
            {true, new String[] {"java.lang.VirtualThread.start", "java.lang.Thread.startVirtualThread", site}},
            // Inlined into a thread-starting API: part of it.
            {true, new String[] {START, "~kotlin.concurrent.ThreadsKt.thread", "kotlin.concurrent.ThreadsKt.thread", site}},
            // A timer thread behind delay, an executor growing, the JDK's own helpers: somebody's side effect.
            {false, new String[] {START, "kotlinx.coroutines.DefaultExecutor.createThreadSync", "kotlinx.coroutines.DelayKt.delay", site}},
            {false, new String[] {START, "java.util.concurrent.ThreadPoolExecutor.addWorker", "java.util.concurrent.ThreadPoolExecutor.execute", site}},
            {false, new String[] {START, "java.lang.VirtualThread.<clinit>", "java.lang.ThreadBuilders$VirtualThreadBuilder.start", site}},
            {false, new String[] {"java.lang.Thread.run", site}},
            // No runtime frame under the site at all, and no site at all.
            {false, new String[] {site, START}},
            {false, new String[] {START, "java.lang.Thread.run"}},
        };
        for (Object[] testCase : cases) {
            StackFrameRef[] stack = stack((String[]) testCase[1]);
            assertEquals(testCase[0], StackCapture.startedDirectlyBySite(stack, StackCapture.siteIndex(stack)), String.join(" < ", (String[]) testCase[1]));
        }
    }

    @Test
    void aCapturedStackStartsAtTheProgramsFrameAndHasAtMostAsManyFramesAsAsked() {
        List<String> stack = App.capturedStack(4);
        assertEquals(4, stack.size());
        assertEquals("com.acme.app.App.capturedStack", stack.get(0), "the agent's own frames are cut off the top");
        assertEquals("kotlinx.coroutree.runtime.StackCaptureTest.aCapturedStackStartsAtTheProgramsFrameAndHasAtMostAsManyFramesAsAsked", stack.get(1),
            "and only off the top: a frame of that package further down is somebody's caller");
        assertEquals(List.of("com.acme.app.App.capturedStack"), App.capturedStack(1));
        List<String> whole = new ArrayList<>();
        App.deep(5, () -> whole.addAll(App.capturedStack(10_000)));
        assertTrue(whole.size() > 8 && whole.size() < 10_000, "a stack shorter than the limit is the whole stack: " + whole.size());
    }

    @Test
    void theLimitOfAStackCountsLogicalFramesAndAnInlineBodyIsOne() {
        // Line 36 of this class is the copy of an inline function's body, called at line 14.
        SourceMaps.register("com.acme.limit.MainKt", "SMAP\nMain.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Main.kt\ncom/acme/limit/MainKt\n+ 2 Util.kt\ncom/acme/limit/UtilKt\n"
            + "*L\n1#1,35:1\n23#2,3:36\n*S KotlinDebug\n*F\n+ 1 Main.kt\ncom/acme/limit/MainKt\n*L\n14#1:36,3\n*E\n", Map.of());
        StackTraceElement inlined = new StackTraceElement("com.acme.limit.MainKt", "main", "Main.kt", 36);
        StackTraceElement plain = new StackTraceElement("com.acme.limit.MainKt", "main", "Main.kt", 20);
        assertEquals(5, StackFrameRef.of(new StackTraceElement[] {inlined, inlined, plain}, 10).length, "the body and the call site, twice, and one frame as it is");
        StackFrameRef[] cut = StackFrameRef.of(new StackTraceElement[] {plain, inlined, inlined, plain}, 2);
        assertEquals(2, cut.length, "never more than the limit, even when the last JVM frame taken is two");
        assertFalse(cut[0].inlined);
        assertTrue(cut[1].inlined);
        assertEquals(0, StackFrameRef.of(new StackTraceElement[0], 5).length);
    }

    @Test
    void framesAreInternedByValue() {
        StackFrameRef frame = new StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12);
        assertEquals(frame, new StackFrameRef(new String("com.acme.MainKt"), new String("main"), new String("Main.kt"), 12));
        assertEquals(frame.hashCode(), new StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12).hashCode());
        assertNotEquals(frame, new StackFrameRef("com.acme.MainKt", "main", "Main.kt", 13));
        assertNotEquals(frame, new StackFrameRef("com.acme.MainKt", "main", "Main.kt", 12, true), "the body of an inline function is another frame than the method's own line");
        assertNotEquals(frame, new StackFrameRef("com.acme.MainKt", "main", "Other.kt", 12));
        assertNotEquals(frame, new StackFrameRef("com.acme.MainKt", "main2", "Main.kt", 12));
        assertNotEquals(frame, new StackFrameRef("com.acme.Main", "main", "Main.kt", 12));
        // Native and unknown lines are both "unknown", 0; a missing file name is an empty one.
        assertEquals(new StackFrameRef("C", "m", "", 0), new StackFrameRef("C", "m", null, -2));
    }
}
