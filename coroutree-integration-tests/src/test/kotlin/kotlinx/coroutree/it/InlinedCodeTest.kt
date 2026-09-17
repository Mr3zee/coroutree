package kotlinx.coroutree.it

import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.tree.qualified
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the golden tree of `InlineFunctions` does not show: the frames that stand for one frame of the JVM. */
class InlinedCodeTest {
    private val snapshot = SampleRuns["InlineFunctions"].snapshot

    private fun stack(ids: List<Int>, depth: Int) = ids.take(depth).map { snapshot.frame(it)!!.qualified }

    @Test
    fun aFrameOfInlinedCodeIsTheBodyOfTheInlineFunctionAndTheCallSite() {
        val nested = snapshot.nodes.values.single { it.info.name == "nested" }
        val launched = nested.events.single { it.kind == EventKind.LAUNCHED }
        // launchNamed inside launchAndPause inside main. The source map knows the innermost and the outermost;
        // the one in between is read off the compiler's marker variables.
        assertEquals(
            listOf(
                "inline samples.InlineFunctionsKt.launchNamed(InlineFunctions.kt:29)",
                "inline samples.InlineFunctionsKt.launchAndPause(InlineFunctions.kt:34)",
                "samples.InlineFunctionsKt\$main\$1.invokeSuspend(InlineFunctions.kt:21)",
            ),
            stack(launched.stack, launched.stack.size).dropWhile { !it.startsWith("inline ") }.take(3),
        )
        assertEquals("inline samples.InlineFunctionsKt.launchNamed(InlineFunctions.kt:29)", snapshot.frame(nested.info.siteFrame)!!.qualified)
    }

    /** After a suspension point the state machine opens the markers of the inline calls it is in anew; they are still the same calls. */
    @Test
    fun inlineCallsSurviveSuspensionPoints() {
        val main = snapshot.nodes.values.single { it.info.construct == "runBlocking" }
        val inPause = main.events.filter { it.kind == EventKind.SUSPENDED }[2]
        assertEquals(
            listOf(
                "inline samples.InlineFunctionsKt.pause(InlineFunctions.kt:39)",
                "inline samples.InlineFunctionsKt.launchAndPause(InlineFunctions.kt:35)",
                "samples.InlineFunctionsKt\$main\$1.invokeSuspend(InlineFunctions.kt:21)",
            ),
            stack(inPause.stack, 3),
        )
    }

    /** A library's classes hold inlined code as well, and stand in stacks; kotlinx.coroutines resumes continuations through some. */
    @Test
    fun framesOfLibraryClassesAreMappedToo() {
        val inLibraryClasses = snapshot.events
            .flatMap { event -> event.stack.map { snapshot.frame(it)!! }.zipWithNext() }
            .filter { (body, next) -> body.inlined && !next.inlined && next.className.startsWith("kotlinx.coroutines.") }
        assertTrue(inLibraryClasses.isNotEmpty(), "no inlined frame inside a kotlinx.coroutines method in any stack")
    }

    /** Started by hand without `include=`, the agent takes no class for the project's and reads every source map the cheap way. */
    @Test
    fun sourceMapsAreReadWithoutKnowingWhatTheProjectIs() {
        val run = runUnderAgent("samples.InlineFunctionsKt", agentOptions = mapOf("include" to ""), runName = "InlineFunctions-no-include")
        GoldenTreeTest.check("InlineFunctions", run, mayUpdate = false)
    }

    @Test
    fun aSuspensionInALibrarysInlineFunctionIsBothThereAndAtTheProjectsCall() {
        val main = snapshot.nodes.values.single { it.info.construct == "runBlocking" }
        val inWithLock = main.events.filter { it.kind == EventKind.SUSPENDED }.last()
        val frames = stack(inWithLock.stack, 2)
        assertEquals("inline kotlinx.coroutines.sync.MutexKt.withLock(Mutex.kt)", frames[0].replace(Regex(":\\d+"), ""))
        assertEquals("samples.InlineFunctionsKt\$main\$1.invokeSuspend(InlineFunctions.kt:25)", frames[1])
    }
}
