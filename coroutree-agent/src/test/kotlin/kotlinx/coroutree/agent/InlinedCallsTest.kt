package kotlinx.coroutree.agent

import kotlinx.coroutree.runtime.RuntimeProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodNode
import java.util.TreeMap

/**
 * [InlinedCalls] read off the marker variables that the Kotlin compiler leaves for debuggers. The methods here are
 * written instruction by instruction in the layout the compiler uses: the call's line, then the marker's span under it.
 */
internal class InlinedCallsTest {
    /** One method, as lines, instructions and spans of marker variables. */
    private class Code(name: String) {
        val method = MethodNode(Opcodes.ACC_STATIC, name, "()V", null, null)
        private val open = HashMap<String, LabelNode>()
        private val slots = HashMap<String, Int>()

        fun line(line: Int): Code {
            val label = LabelNode()
            method.instructions.add(label)
            method.instructions.add(LineNumberNode(line, label))
            method.instructions.add(InsnNode(Opcodes.NOP))
            return this
        }

        /** Adds one more instruction on the current line. */
        fun more(): Code {
            method.instructions.add(InsnNode(Opcodes.NOP))
            return this
        }

        fun open(variable: String): Code {
            val start = LabelNode()
            method.instructions.add(start)
            open[variable] = start
            return this
        }

        fun close(variable: String): Code {
            val end = LabelNode()
            method.instructions.add(end)
            val slot = slots.getOrPut(variable) { slots.size }
            method.localVariables.add(LocalVariableNode(variable, "I", null, open.remove(variable), end, slot))
            return this
        }
    }

    @Test
    fun codeInAnInlinedBodyIsInThatFunctionCalledFromTheLineBeforeItsSpan() {
        val main = Code("main").line(10).open(RETRY).line(36).line(37).close(RETRY).line(11)
        assertEquals(mapOf(36 to "retry@10", 37 to "retry@10"), inlined(classOf(main)), "the method's own lines 10 and 11 are in no inline function")
    }

    @Test
    fun codeInlinedThroughSeveralFunctionsNamesThemInnermostFirstEachWithTheLineOfItsCall() {
        // The copy of retry (36..38) is called at 18, and the copy of withLock (53..54) at 37.
        val main = Code("main").line(18).open(RETRY).line(36).line(37).open(WITH_LOCK).line(53).line(54).close(WITH_LOCK).line(38).close(RETRY).line(19)
        assertEquals(
            mapOf(36 to "retry@18", 37 to "retry@18", 38 to "retry@18", 53 to "withLock@37 < retry@18", 54 to "withLock@37 < retry@18"),
            inlined(classOf(main))
        )
    }

    @Test
    fun aLambdaBelongsToWhoeverPassedItNotToTheFunctionItWasInlinedInto() {
        // main passes a lambda (its own line 15) to retry: the lambda's code is inside the copy of retry but is main's.
        val main = Code("main").line(14).open(RETRY).line(36).open(LAMBDA_OF_RETRY).line(15).close(LAMBDA_OF_RETRY).line(37).close(RETRY)
        assertEquals(mapOf(36 to "retry@14", 37 to "retry@14"), inlined(classOf(main)))

        // The same inside the copy of outer: the lambda was written in outer (line 61 of its copy), so it is outer's code.
        val nested = Code("main").line(14).open(OUTER).line(60).open(RETRY).line(36).open(LAMBDA_OF_RETRY).line(61)
            .close(LAMBDA_OF_RETRY).close(RETRY).close(OUTER)
        assertEquals(mapOf(60 to "outer@14", 36 to "retry@60 < outer@14", 61 to "outer@14"), inlined(classOf(nested)))
    }

    @Test
    fun aMethodWithLambdasOnlyOrWithNoVariableTableSaysNothing() {
        val lambdaOnly = Code("main").line(10).open("\$i\$a$-let-MainKt\$main$1").line(11).close("\$i\$a$-let-MainKt\$main$1")
        assertEquals(emptyMap<Int, String>(), inlined(classOf(lambdaOnly)), "a lambda this method passed is this method's code, whatever it was passed to")

        val stripped = Code("main").line(10).line(36)
        stripped.method.localVariables = null // compiled without debug information
        assertEquals(emptyMap<Int, String>(), inlined(classOf(stripped)))
    }

    @Test
    fun theDeepestInstructionOfALineSaysWhereTheLineIs() {
        // Line 53 also has instructions outside the span of withLock, before it and after it.
        val main = Code("main").line(18).open(RETRY).line(36).line(53).line(36).open(WITH_LOCK).line(53).close(WITH_LOCK).line(53).close(RETRY)
        assertEquals("withLock@36 < retry@18", inlined(classOf(main))[53])
    }

    @Test
    fun aSpanThatBeginsAgainAfterASuspensionPointIsTheSameCall() {
        // A suspending method is a state machine. After a suspension point the compiler sets the markers of the open
        // spans again, under the method's first line (5), and the spans start again.
        val suspending = Code("invokeSuspend").line(5).line(18).open(RETRY).line(36).close(RETRY).line(5).open(RETRY).line(37).close(RETRY)
        assertEquals(mapOf(36 to "retry@18", 37 to "retry@18"), inlined(classOf(suspending)))

        // A span that starts on the method's first line without an earlier span of the same marker is called from that line.
        val first = Code("run").line(5).open(RETRY).line(36).close(RETRY)
        assertEquals(mapOf(36 to "retry@5"), inlined(classOf(first)))
    }

    @Test
    fun everyMethodOfTheClassContributesItsLines() {
        val main = Code("main").line(10).open(RETRY).line(36).close(RETRY)
        val other = Code("other").line(20).open(WITH_LOCK).line(52).close(WITH_LOCK)
        val plain = Code("plain").line(30)
        assertEquals(mapOf(36 to "retry@10", 52 to "withLock@20"), inlined(classOf(main, other, plain)))
    }

    /**
     * End to end: the transformer reads a class file with a source map and marker variables, and a stack frame in it
     * comes out as TRACE_FORMAT.md ("Inlined code") states:
     * - the innermost inlined body, named
     * - in a project class, one frame for each inline function the body was inlined through, at the line of its call
     * - last, the call site
     */
    @Test
    fun aFrameInAProjectClassNamesEveryInlineFunctionItIsInAndInALibraryClassTheInnermostBodyOnly() {
        val smapFiles = "*F\n+ 1 Main.kt\ncom/acme/%s\n+ 2 Util.kt\ncom/acme/util/UtilKt\n+ 3 Mutex.kt\nkotlinx/coroutines/sync/MutexKt\n"
        val smap = "SMAP\nMain.kt\nKotlin\n*S Kotlin\n" + smapFiles + "*L\n1#1,35:1\n23#2,3:36\n117#3,10:52\n*S KotlinDebug\n" + smapFiles + "*L\n18#1:36,3\n18#1:52,10\n*E\n"
        val transformer = Lab.transformer("include=com.acme.project")
        for (pkg in listOf("project", "library")) {
            val main = Code("main").line(18).open(RETRY).line(36).line(37).open(WITH_LOCK).line(53).close(WITH_LOCK).line(38).close(RETRY)
            val node = classOf(main)
            node.name = "com/acme/$pkg/MainKt"
            node.sourceFile = "Main.kt"
            node.sourceDebug = smap.replace("%s", "$pkg/MainKt")
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            node.accept(writer)
            // Neither class is changed: nothing to hook, no catch block. The transformer only reads them.
            assertNull(transformer.transform(javaClass.classLoader, node.name, null, null, writer.toByteArray()))
        }
        assertEquals(
            listOf(
                "inline kotlinx.coroutines.sync.MutexKt.withLock(Mutex.kt:118)",
                "inline com.acme.util.UtilKt.retry(Util.kt:24)",
                "com.acme.project.MainKt.main(Main.kt:18)"
            ),
            RuntimeProbe.logicalFrames("com.acme.project.MainKt", "main", "Main.kt", 53)
        )
        assertEquals(
            listOf("inline com.acme.util.UtilKt.retry(Util.kt:25)", "com.acme.project.MainKt.main(Main.kt:18)"),
            RuntimeProbe.logicalFrames("com.acme.project.MainKt", "main", "Main.kt", 38)
        )
        // Of a class outside the project's packages the transformer reads only the source map. So the inline function
        // has no name, and the function it was inlined through gets no frame.
        assertEquals(
            listOf("inline kotlinx.coroutines.sync.MutexKt.(Mutex.kt:118)", "com.acme.library.MainKt.main(Main.kt:18)"),
            RuntimeProbe.logicalFrames("com.acme.library.MainKt", "main", "Main.kt", 53)
        )
    }

    companion object {
        private const val RETRY = "\$i\$f\$retry"
        private const val WITH_LOCK = "\$i\$f\$withLock"
        private const val OUTER = "\$i\$f\$outer"
        private const val LAMBDA_OF_RETRY = "\$i\$a$-retry-MainKt\$main$1"

        private fun classOf(vararg methods: Code): ClassNode {
            val node = ClassNode()
            node.version = Opcodes.V17
            node.access = Opcodes.ACC_PUBLIC
            node.name = "com/acme/MainKt"
            node.superName = "java/lang/Object"
            for (code in methods) {
                code.method.instructions.add(InsnNode(Opcodes.RETURN))
                node.methods.add(code.method)
            }
            return node
        }

        private fun inlined(node: ClassNode): Map<Int, String> =
            InlinedCalls.of(node).entries.associateTo(TreeMap()) { it.key to RuntimeProbe.describe(it.value) }
    }
}
