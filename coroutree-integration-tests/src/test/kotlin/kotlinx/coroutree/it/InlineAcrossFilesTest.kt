package kotlinx.coroutree.it

import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.HandledBy
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.qualified
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The logical stacks of `InlineAcrossFiles` (TRACE_FORMAT, "Inlined code"): what its golden tree shows of them is one
 * frame each, the site. Here: the frames a JVM frame of inlined code stands for when the inline function was written
 * in another file, is a member, is called through another inline function, or is not inlined at all (from Java).
 */
class InlineAcrossFilesTest {
    private val snapshot = SampleRuns["InlineAcrossFiles"].snapshot

    private fun node(name: String): NodeSnapshot = snapshot.nodes.values.single { it.info.name == name }
    private fun NodeSnapshot.launched(): Event = events.single { it.kind == EventKind.LAUNCHED && it.nodeId == id }
    private fun frames(ids: List<Int>) = ids.map { snapshot.frame(it)!! }

    /** The frames from the first one of the project on, as many as [count]. */
    private fun projectFrames(ids: List<Int>, count: Int) =
        frames(ids).dropWhile { !it.className.startsWith("samples.") }.take(count).map { it.qualified }

    @Test
    fun anInlineFunctionOfAnotherFileIsFoundInItsOwnFile() {
        val launched = node("elsewhere").launched()
        assertEquals(
            listOf(
                "inline samples.InlineHelpersKt.launchLabelled(InlineHelpers.kt:13)",
                "samples.InlineAcrossFilesKt\$main\$1.invokeSuspend(InlineAcrossFiles.kt:17)",
            ),
            projectFrames(launched.stack, 2),
        )
        val site = snapshot.frame(node("elsewhere").info.siteFrame)!!
        assertTrue(site.inlined)
        assertEquals("InlineHelpers.kt", site.fileName)
        assertEquals(Origin.PROJECT, node("elsewhere").info.origin)
    }

    @Test
    fun anInlineMemberThatCallsAnInlineFunctionIsTheFrameInBetween() {
        assertEquals(
            listOf(
                "inline samples.InlineHelpersKt.launchLabelled(InlineHelpers.kt:13)",
                "inline samples.Labeller.launchIn(InlineHelpers.kt:31)",
                "samples.InlineAcrossFilesKt\$main\$1.invokeSuspend(InlineAcrossFiles.kt:25)",
            ),
            projectFrames(node("a member").launched().stack, 3),
        )
    }

    /** A lambda inlined into `twice`, also twice over, is code of the method it was written in, at its own line. */
    @Test
    fun codeOfALambdaHandedToAnInlineFunctionStaysWhereItWasWritten() {
        for ((name, line) in listOf("lambda-1" to 15, "lambda-2" to 15, "deep" to 21)) {
            val first = frames(node(name).launched().stack).first { it.className.startsWith("samples.") }
            assertEquals("samples.InlineAcrossFilesKt\$main\$1.invokeSuspend(InlineAcrossFiles.kt:$line)", first.qualified, name)
        }
        val thread = node("from a lambda")
        assertEquals("thread", thread.info.construct)
        assertEquals(Origin.PROJECT, thread.info.origin)
        assertEquals("InlineAcrossFiles.kt:33", snapshot.frame(thread.info.siteFrame)!!.let { "${it.fileName}:${it.line}" })
    }

    /** To Java an inline function is a method: real frames with real lines, nothing to map and nothing marked inlined. */
    @Test
    fun calledFromJavaAnInlineFunctionIsAnOrdinaryFrame() {
        assertEquals(
            listOf(
                "samples.InlineHelpersKt.launchLabelled(InlineHelpers.kt:13)",
                "samples.JavaInlineCaller.launchFromJava(JavaInlineCaller.java:12)",
                "samples.InlineAcrossFilesKt\$main\$1.invokeSuspend(InlineAcrossFiles.kt:35)",
            ),
            projectFrames(node("from java").launched().stack, 3),
        )
    }

    /** A suspension inside an inline function that was inlined into the copy of another inline function's lambda. */
    @Test
    fun aSuspensionInAnInlineFunctionInsideACrossinlineLambda() {
        val suspended = node("elsewhere").events.single { it.kind == EventKind.SUSPENDED }
        val project = frames(suspended.stack).dropWhile { !it.className.startsWith("samples.") }
        assertEquals("inline samples.InlineHelpersKt.pauseBriefly(InlineHelpers.kt:23)", project[0].qualified)
        // Where pauseBriefly() is called: line 17 of the sample, in a class that is a copy of launchLabelled's lambda.
        assertEquals("InlineAcrossFiles.kt:17", "${project[1].fileName}:${project[1].line}")
        assertTrue(!project[1].inlined, project[1].qualified)
    }

    /** The stack of an exception goes through the same mapping as every other stack. */
    @Test
    fun anExceptionThrownInInlinedCodeWasThrownWhereTheCodeWasWritten() {
        val caught = snapshot.events.single { it.kind == EventKind.EXCEPTION_HANDLED && it.handledBy == HandledBy.CATCH }
        assertEquals(
            listOf(
                "inline samples.InlineHelpersKt.failing(InlineHelpers.kt:26)",
                "samples.InlineAcrossFilesKt\$main\$1.invokeSuspend(InlineAcrossFiles.kt:28)",
            ),
            projectFrames(caught.exception!!.stack, 2),
        )
        assertEquals("samples.InlineAcrossFilesKt\$main\$1.invokeSuspend(InlineAcrossFiles.kt:29)", projectFrames(caught.stack, 1).single())
    }

    @Test
    fun noFrameOfTheSampleHasALineItsFileDoesNotHave() {
        val lengths = mapOf("InlineAcrossFiles.kt" to 36, "InlineHelpers.kt" to 32, "JavaInlineCaller.java" to 14)
        val stacks = snapshot.events.flatMap { event -> listOf(event.stack, event.exception?.stack.orEmpty()) }
        for (frame in stacks.flatten().distinct().map { snapshot.frame(it)!! }) {
            val length = lengths[frame.fileName] ?: continue
            // 0 is "unknown": the bridge main(String[]) the compiler adds has no line numbers.
            assertTrue(frame.line in 0..length, "a synthetic line number got through: ${frame.qualified}")
        }
    }
}
