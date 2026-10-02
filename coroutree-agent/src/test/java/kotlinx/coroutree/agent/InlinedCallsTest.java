package kotlinx.coroutree.agent;

import kotlinx.coroutree.runtime.RuntimeProbe;
import kotlinx.coroutree.runtime.SourceMaps;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which inline functions the code at a line was inlined through, read off the marker variables the Kotlin compiler
 * leaves for debuggers: {@code $i$f$name} spans the inlined body of {@code name}, {@code $i$a$-name-…} an inlined
 * lambda that was passed to {@code name}. The methods here are written out instruction by instruction, in the layout
 * the compiler uses: the call's line, the marker set under it, then the span.
 */
class InlinedCallsTest {
    private static final String RETRY = "$i$f$retry", WITH_LOCK = "$i$f$withLock", OUTER = "$i$f$outer";
    private static final String LAMBDA_OF_RETRY = "$i$a$-retry-MainKt$main$1";

    /** One method, as lines, instructions and spans of marker variables. */
    private static final class Code {
        final MethodNode method;
        private final Map<String, LabelNode> open = new HashMap<>();
        private final Map<String, Integer> slots = new HashMap<>();

        Code(String name) {
            method = new MethodNode(Opcodes.ACC_STATIC, name, "()V", null, null);
        }

        Code line(int line) {
            LabelNode label = new LabelNode();
            method.instructions.add(label);
            method.instructions.add(new LineNumberNode(line, label));
            method.instructions.add(new InsnNode(Opcodes.NOP));
            return this;
        }

        /** One more instruction of the line in force. */
        Code more() {
            method.instructions.add(new InsnNode(Opcodes.NOP));
            return this;
        }

        Code open(String variable) {
            LabelNode start = new LabelNode();
            method.instructions.add(start);
            open.put(variable, start);
            return this;
        }

        Code close(String variable) {
            LabelNode end = new LabelNode();
            method.instructions.add(end);
            slots.putIfAbsent(variable, slots.size());
            method.localVariables.add(new LocalVariableNode(variable, "I", null, open.remove(variable), end, slots.get(variable)));
            return this;
        }
    }

    private static ClassNode classOf(Code... methods) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "com/acme/MainKt";
        node.superName = "java/lang/Object";
        for (Code code : methods) {
            code.method.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(code.method);
        }
        return node;
    }

    private static Map<Integer, String> inlined(ClassNode node) {
        Map<Integer, String> result = new TreeMap<>();
        for (Map.Entry<Integer, SourceMaps.Inlined> entry : InlinedCalls.of(node).entrySet()) result.put(entry.getKey(), RuntimeProbe.describe(entry.getValue()));
        return result;
    }

    @Test
    void codeInAnInlinedBodyIsInThatFunctionCalledFromTheLineBeforeItsSpan() {
        Code main = new Code("main").line(10).open(RETRY).line(36).line(37).close(RETRY).line(11);
        assertEquals(Map.of(36, "retry@10", 37, "retry@10"), inlined(classOf(main)), "the method's own lines 10 and 11 are in no inline function");
    }

    @Test
    void codeInlinedThroughSeveralFunctionsNamesThemInnermostFirstEachWithTheLineOfItsCall() {
        // main calls retry at 18; retry's copy (36..38) calls withLock at 37; withLock's copy is 53..54.
        Code main = new Code("main").line(18).open(RETRY).line(36).line(37).open(WITH_LOCK).line(53).line(54).close(WITH_LOCK).line(38).close(RETRY).line(19);
        assertEquals(
            Map.of(36, "retry@18", 37, "retry@18", 38, "retry@18", 53, "withLock@37 < retry@18", 54, "withLock@37 < retry@18"),
            inlined(classOf(main)));
    }

    @Test
    void aLambdaBelongsToWhoeverPassedItNotToTheFunctionItWasInlinedInto() {
        // main passes a lambda (its own line 15) to retry: the lambda's code runs inside retry's copy and is main's.
        Code main = new Code("main").line(14).open(RETRY).line(36).open(LAMBDA_OF_RETRY).line(15).close(LAMBDA_OF_RETRY).line(37).close(RETRY);
        assertEquals(Map.of(36, "retry@14", 37, "retry@14"), inlined(classOf(main)));

        // The same inside outer's copy: the lambda was written in outer (line 61 of its copy), so it is outer's code.
        Code nested = new Code("main").line(14).open(OUTER).line(60).open(RETRY).line(36).open(LAMBDA_OF_RETRY).line(61).close(LAMBDA_OF_RETRY).close(RETRY).close(OUTER);
        assertEquals(Map.of(60, "outer@14", 36, "retry@60 < outer@14", 61, "outer@14"), inlined(classOf(nested)));
    }

    @Test
    void aMethodWithLambdasOnlyOrWithNoVariableTableSaysNothing() {
        Code lambdaOnly = new Code("main").line(10).open("$i$a$-let-MainKt$main$1").line(11).close("$i$a$-let-MainKt$main$1");
        assertEquals(Map.of(), inlined(classOf(lambdaOnly)), "a lambda this method passed is this method's code, whatever it was passed to");

        Code stripped = new Code("main").line(10).line(36);
        stripped.method.localVariables = null; // compiled without debug information
        assertEquals(Map.of(), inlined(classOf(stripped)));
    }

    @Test
    void theDeepestInstructionOfALineSaysWhereTheLineIs() {
        // Instructions of line 53 on either side of the span that is withLock's body, before it and after it.
        Code main = new Code("main").line(18).open(RETRY).line(36).line(53).line(36).open(WITH_LOCK).line(53).close(WITH_LOCK).line(53).close(RETRY);
        assertEquals("withLock@36 < retry@18", inlined(classOf(main)).get(53));
    }

    @Test
    void aSpanThatBeginsAgainAfterASuspensionPointIsTheSameCall() {
        // A suspending method is a state machine: after a suspension point the markers of the spans that are still open
        // are set anew, under the method's first line (5), and the spans begin again.
        Code suspending = new Code("invokeSuspend").line(5).line(18).open(RETRY).line(36).close(RETRY)
            .line(5).open(RETRY).line(37).close(RETRY);
        assertEquals(Map.of(36, "retry@18", 37, "retry@18"), inlined(classOf(suspending)));

        // A call that really is on the method's first line is called from there.
        Code first = new Code("run").line(5).open(RETRY).line(36).close(RETRY);
        assertEquals(Map.of(36, "retry@5"), inlined(classOf(first)));
    }

    @Test
    void everyMethodOfTheClassContributesItsLines() {
        Code main = new Code("main").line(10).open(RETRY).line(36).close(RETRY);
        Code other = new Code("other").line(20).open(WITH_LOCK).line(52).close(WITH_LOCK);
        Code plain = new Code("plain").line(30);
        assertEquals(Map.of(36, "retry@10", 52, "withLock@20"), inlined(classOf(main, other, plain)));
    }

    /**
     * The whole way: a class file with a source map and marker variables goes through the transformer, and a stack
     * frame in it comes out as TRACE_FORMAT ("Inlined code") says: the body, named; in project classes one frame for
     * every inline function that one was inlined through, at the line of the call; last the call site.
     */
    @Test
    void aFrameInAProjectClassNamesEveryInlineFunctionItIsInAndInALibraryClassTheInnermostBodyOnly() {
        String smapFiles = "*F\n+ 1 Main.kt\ncom/acme/%s\n+ 2 Util.kt\ncom/acme/util/UtilKt\n+ 3 Mutex.kt\nkotlinx/coroutines/sync/MutexKt\n";
        String smap = "SMAP\nMain.kt\nKotlin\n*S Kotlin\n" + smapFiles + "*L\n1#1,35:1\n23#2,3:36\n117#3,10:52\n*S KotlinDebug\n" + smapFiles + "*L\n18#1:36,3\n18#1:52,10\n*E\n";
        CoroutreeTransformer transformer = Lab.transformer("include=com.acme.project");
        for (String pkg : new String[] {"project", "library"}) {
            Code main = new Code("main").line(18).open(RETRY).line(36).line(37).open(WITH_LOCK).line(53).close(WITH_LOCK).line(38).close(RETRY);
            ClassNode node = classOf(main);
            node.name = "com/acme/" + pkg + "/MainKt";
            node.sourceFile = "Main.kt";
            node.sourceDebug = smap.replace("%s", pkg + "/MainKt");
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            // Neither class is changed: there is nothing to hook in them and no catch block. They are only read.
            assertNull(transformer.transform(getClass().getClassLoader(), node.name, null, null, writer.toByteArray()));
        }
        assertEquals(
            List.of("inline kotlinx.coroutines.sync.MutexKt.withLock(Mutex.kt:118)", "inline com.acme.util.UtilKt.retry(Util.kt:24)", "com.acme.project.MainKt.main(Main.kt:18)"),
            RuntimeProbe.logicalFrames("com.acme.project.MainKt", "main", "Main.kt", 53));
        assertEquals(
            List.of("inline com.acme.util.UtilKt.retry(Util.kt:25)", "com.acme.project.MainKt.main(Main.kt:18)"),
            RuntimeProbe.logicalFrames("com.acme.project.MainKt", "main", "Main.kt", 38));
        // A class outside the project's packages is not read beyond its source map.
        assertEquals(
            List.of("inline kotlinx.coroutines.sync.MutexKt.(Mutex.kt:118)", "com.acme.library.MainKt.main(Main.kt:18)"),
            RuntimeProbe.logicalFrames("com.acme.library.MainKt", "main", "Main.kt", 53));
    }
}
