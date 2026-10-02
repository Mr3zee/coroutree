package kotlinx.coroutree.agent;

import kotlinx.coroutree.agent.MethodPatch.HookCall;
import kotlinx.coroutree.agent.fixtures.Shapes;
import kotlinx.coroutree.agent.fixtures.Subject;
import kotlinx.coroutree.runtime.Tagged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.util.ArrayList;
import java.util.List;

import static kotlinx.coroutree.agent.Args.arg;
import static kotlinx.coroutree.agent.Args.call;
import static kotlinx.coroutree.agent.Args.callOperands;
import static kotlinx.coroutree.agent.Args.constant;
import static kotlinx.coroutree.agent.Args.field;
import static kotlinx.coroutree.agent.Args.returnValue;
import static kotlinx.coroutree.agent.Args.self;
import static kotlinx.coroutree.agent.MethodPatch.around;
import static kotlinx.coroutree.agent.MethodPatch.beforeCall;
import static kotlinx.coroutree.agent.MethodPatch.enter;
import static kotlinx.coroutree.agent.MethodPatch.enterAndExit;
import static kotlinx.coroutree.agent.MethodPatch.exit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hook calls woven into a class the way the hook table weaves them into the JDK and kotlinx.coroutines: rewritten by
 * the transformer's own path (frames expanded, none recomputed), then loaded by a JVM that verifies it and run next
 * to the class as it was compiled. The rewritten class calls the hooks it was told to, when it was told to, and does
 * everything else exactly as before.
 */
class PatchTest {
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String THROWABLE = "Ljava/lang/Throwable;";
    private static final String SUBJECT = Lab.internalName(Subject.class);

    private final CoroutreeTransformer transformer = Lab.transformer("");

    @BeforeEach
    void forget() {
        HookLog.take();
    }

    /** The subject with one method patched, loaded and verified. */
    private Class<?> patched(MethodPatch... patches) {
        ClassPatch patch = new ClassPatch(SUBJECT);
        for (MethodPatch method : patches) patch.method(method);
        byte[] rewritten = transformer.rewrite(getClass().getClassLoader(), SUBJECT, patch, false, Lab.bytesOf(Subject.class));
        return new Lab.Loader().with(Subject.class, rewritten).verified(Subject.class);
    }

    private static Object instance(Class<?> type, String name) throws ReflectiveOperationException {
        return type.getConstructor(String.class).newInstance(name);
    }

    /** The same call on the class as compiled and on the patched one: same outcome; returns what the hooks saw of the second. */
    private static List<String> sameOutcome(Object original, Object patched, String method, Object... arguments) {
        Lab.Outcome expected = Lab.call(original, method, arguments);
        HookLog.take();
        Lab.Outcome actual = Lab.call(patched, method, arguments);
        assertEquals(expected.toString(), actual.toString(), method);
        return HookLog.taken();
    }

    private static final HookCall BLOCK_ENTER = new HookCall("blockEnter", "(I)V", constant(5));
    private static final HookCall BLOCK_EXIT = new HookCall("blockExit", "()V");

    // ------------------------------------------------------------------ around

    @Test
    void aroundCallsEnterOnceAndExitOnceOnEveryWayOutAndChangesNothingElse() {
        Class<?> patched = patched(around("sleep", "(JLjava/lang/String;)J", BLOCK_ENTER, BLOCK_EXIT));
        List<String> pair = List.of("blockEnter(5)", "blockExit()");
        assertEquals(pair, sameOutcome(Subject.class, patched, "sleep", 10L, "note"), "a normal return");
        assertEquals(pair, sameOutcome(Subject.class, patched, "sleep", 10L, null), "the other return");
        assertEquals(pair, sameOutcome(Subject.class, patched, "sleep", 13L, "note"),
            "an exception the method handles itself is its own business: its handlers keep priority over the catch-all");
        assertEquals(pair, sameOutcome(Subject.class, patched, "sleep", -1L, "note"), "the way out by exception");
    }

    @Test
    void theExceptionThatLeavesAPatchedMethodIsTheVeryOneThatWasThrown() throws Exception {
        Class<?> patched = patched(around("run", "(Ljava/lang/Runnable;)V", null, new HookCall("continuationCreated", "(" + OBJECT + ")V", self())));
        Object subject = instance(patched, "vt");
        IllegalStateException failure = new IllegalStateException("task failed");
        Lab.Outcome outcome = Lab.call(subject, "run", (Runnable) () -> {
            HookLog.mark("task");
            throw failure;
        });
        assertSame(failure, outcome.thrown);
        // No enter hook (VirtualThread.run has none); the exit hook gets `this`, the one thing the handler's frame knows.
        List<Object[]> calls = HookLog.take();
        assertEquals(3, calls.size());
        assertEquals("running vt", calls.get(0)[0]);
        assertEquals("task", calls.get(1)[0]);
        assertEquals("continuationCreated", calls.get(2)[0]);
        assertSame(subject, calls.get(2)[1]);

        Lab.call(subject, "run", (Runnable) () -> HookLog.mark("task"));
        assertEquals(List.of("running vt()", "task()", "continuationCreated(" + subject + ")"), HookLog.taken(), "and after the body on a normal return");
    }

    @Test
    void aroundAnInstanceMethodWithSeveralReturns() throws Exception {
        Class<?> patched = patched(around("read", "([B)I", BLOCK_ENTER, BLOCK_EXIT));
        Object original = new Subject("s"), subject = instance(patched, "s");
        List<String> pair = List.of("blockEnter(5)", "blockExit()");
        assertEquals(pair, sameOutcome(original, subject, "read", (Object) new byte[] {1, 0, 2}));
        assertEquals(pair, sameOutcome(original, subject, "read", (Object) new byte[0]));
        assertEquals(pair, sameOutcome(original, subject, "read", (Object) null));
    }

    // ------------------------------------------------------------------ enter, exit and the values they hand over

    @Test
    void anEnterHookGetsTheParametersItNamesWhateverTheirSlots() throws Exception {
        Class<?> patched = patched(
            // long and double take two slots each: the fourth parameter of an instance method is in slot 6.
            enter("mix", "(J" + OBJECT + "DLjava/lang/String;)Ljava/lang/String;", new HookCall("coroutineCreated", "(" + OBJECT + OBJECT + OBJECT + ")V", self(), arg(1), arg(3))),
            // A static method has no `this` in slot 0.
            enter("mixStatic", "(IJ" + THROWABLE + ")Ljava/lang/String;", new HookCall("exceptionCaught", "(" + THROWABLE + ")V", arg(2))));
        Object original = new Subject("s"), subject = instance(patched, "s");
        assertEquals(List.of("coroutineCreated(" + subject + ", b, d)"), sameOutcome(original, subject, "mix", 7L, "b", 0.25, "d"));
        assertEquals(List.of("exceptionCaught(IllegalStateException:boom)"), sameOutcome(Subject.class, patched, "mixStatic", 1, 2L, new IllegalStateException("boom")));
    }

    @Test
    void anIntegerParameterIsHandedOverAsOne() {
        Class<?> patched = patched(enter("mixStatic", "(IJ" + THROWABLE + ")Ljava/lang/String;", new HookCall("blockEnter", "(I)V", arg(0))));
        assertEquals(List.of("blockEnter(42)"), sameOutcome(Subject.class, patched, "mixStatic", 42, 2L, new IllegalStateException("boom")));
    }

    @Test
    void anExitHookOnAConstructorSeesTheObjectOnceItIsInitialized() throws Exception {
        Class<?> patched = patched(exit("<init>", "(Ljava/lang/String;)V", new HookCall("jobCreated", "(" + OBJECT + OBJECT + ")V", self(), arg(0))));
        Object subject = instance(patched, "job");
        List<Object[]> calls = HookLog.take();
        assertEquals(1, calls.size());
        assertSame(subject, calls.get(0)[1]);
        assertEquals("job", calls.get(0)[2]);

        // The constructor that delegates to the hooked one: one object, one call.
        Object byDefault = patched.getConstructor().newInstance();
        calls = HookLog.take();
        assertEquals(1, calls.size());
        assertSame(byDefault, calls.get(0)[1]);
        assertEquals("default", calls.get(0)[2]);

        // A constructor that throws creates nothing.
        assertThrows(ReflectiveOperationException.class, () -> instance(patched, null));
        assertEquals(List.of(), HookLog.taken());
    }

    @Test
    void anExitHookGetsTheValueAboutToBeReturnedAndTheCallerStillGetsIt() throws Exception {
        Class<?> patched = patched(exit("create", "(" + OBJECT + ")" + OBJECT, new HookCall("continuationCreated", "(" + OBJECT + ")V", returnValue())));
        Object subject = instance(patched, "s");
        Lab.Outcome outcome = Lab.call(subject, "create", "seed");
        assertEquals("made of seed", outcome.value);
        List<Object[]> calls = HookLog.take();
        assertEquals(1, calls.size());
        assertSame(outcome.value, calls.get(0)[1]);

        assertNull(Lab.call(subject, "create", (Object) null).value);
        assertEquals(List.of("continuationCreated(null)"), HookLog.taken(), "at the other return too");
    }

    @Test
    void enterAndExitWithArgumentsReadFromFieldsAndGettersAsTheHooksOfChildCancelledAre() throws Exception {
        MethodPatch.Arg fromField = field(SUBJECT, "field", OBJECT);
        MethodPatch.Arg fromGetter = call(self(), Opcodes.INVOKEVIRTUAL, SUBJECT, "context", "()" + OBJECT);
        Class<?> patched = patched(enterAndExit("childCancelled", "(" + THROWABLE + ")Z",
            new HookCall("childCancelled", "(" + OBJECT + OBJECT + THROWABLE + ")V", fromField, fromGetter, arg(0)),
            new HookCall("childCancelledResult", "(Z" + OBJECT + OBJECT + THROWABLE + ")V", returnValue(), fromField, fromGetter, arg(0))));
        Object original = new Subject("p"), subject = instance(patched, "p");
        assertEquals(
            List.of("childCancelled(field-value, context of p, IllegalStateException:x)", "childCancelledResult(true, field-value, context of p, IllegalStateException:x)"),
            sameOutcome(original, subject, "childCancelled", new IllegalStateException("x")));
        assertEquals(
            List.of("childCancelled(field-value, context of p, RuntimeException:y)", "childCancelledResult(false, field-value, context of p, RuntimeException:y)"),
            sameOutcome(original, subject, "childCancelled", new RuntimeException("y")));
    }

    @Test
    void theValueAboutToBeReturnedCanOnlyBeHandedOverWhenItIsOneSlotWide() {
        HookCall hook = new HookCall("continuationCreated", "(" + OBJECT + ")V", returnValue());
        // A long or a double cannot be duplicated the way one slot is; the patch says so when it is applied, not at run time.
        assertThrows(IllegalStateException.class, () -> patched(exit("wide", "()J", hook)));
        assertThrows(IllegalStateException.class, () -> patched(exit("ratio", "()D", hook)));
        assertThrows(IllegalStateException.class, () -> patched(exit("quiet", "(" + OBJECT + ")V", hook)), "nothing is returned");
        assertThrows(IllegalStateException.class, () -> patched(around("create", "(" + OBJECT + ")" + OBJECT, null, hook)), "there is no value when leaving by exception");
    }

    // ------------------------------------------------------------------ before a call

    @Test
    void aHookInFrontOfACallGetsItsReceiverAndArgumentAndTheCallStillHappens() throws Exception {
        MethodPatch patch = beforeCall("resumeWith", "(" + OBJECT + ")V", "java/util/function/Consumer", "accept", "(" + OBJECT + ")V",
            new HookCall("continuationCompleted", "(" + OBJECT + OBJECT + OBJECT + ")V", callOperands(), self()));
        Class<?> patched = patched(patch);
        Object subject = instance(patched, "frame");
        Object sink = patched.getField("sink").get(subject);

        Lab.call(subject, "resumeWith", "a");
        assertEquals(List.of("continuationCompleted(" + sink + ", s:a, " + subject + ")", "accepted(s:a)", "resumed()"), HookLog.taken());

        // Every call the method makes to it, wherever in the method.
        Lab.call(subject, "resumeWith", 42);
        assertEquals(
            List.of("continuationCompleted(" + sink + ", 42, " + subject + ")", "accepted(42)",
                "continuationCompleted(" + sink + ", twice, " + subject + ")", "accepted(twice)", "resumed()"),
            HookLog.taken());
    }

    // ------------------------------------------------------------------ a class patch as a whole

    @Test
    void aRequiredMethodThatCannotBePatchedIsNamedAndAnOptionalOneIsNot() {
        HookCall hook = new HookCall("continuationCreated", "(" + OBJECT + ")V", arg(0));
        ClassNode shapes = new ClassNode();
        new ClassReader(Lab.bytesOf(Shapes.class)).accept(shapes, ClassReader.EXPAND_FRAMES);
        List<String> missing = new ClassPatch(Lab.internalName(Shapes.class))
            .method(enter("gone", "(" + OBJECT + ")V", hook))
            .method(enter("goneOptional", "(" + OBJECT + ")V", hook).optional())
            .method(enter("later", "(" + OBJECT + ")V", hook))            // abstract in this version of the class
            .method(enter("elsewhere", "(" + OBJECT + ")V", hook))        // native
            .method(enter("elsewhere", "(" + OBJECT + ")V", hook).optional())
            .method(enter("overloaded", "(I)Ljava/lang/String;", hook))   // the name is there, the signature is not
            .method(enter("overloaded", "(Ljava/lang/String;)Ljava/lang/String;", hook))
            .apply(shapes);
        assertEquals(List.of("gone(Ljava/lang/Object;)V", "later(Ljava/lang/Object;)V", "elsewhere(Ljava/lang/Object;)V", "overloaded(I)Ljava/lang/String;"), missing);

        // A method that does not make the call a hook was to go in front of is as good as a method that is not there.
        ClassNode subject = new ClassNode();
        new ClassReader(Lab.bytesOf(Subject.class)).accept(subject, ClassReader.EXPAND_FRAMES);
        HookCall operands = new HookCall("continuationCompleted", "(" + OBJECT + OBJECT + OBJECT + ")V", callOperands(), self());
        missing = new ClassPatch(SUBJECT)
            .method(beforeCall("quiet", "(" + OBJECT + ")V", "java/util/function/Consumer", "accept", "(" + OBJECT + ")V", operands))
            .method(beforeCall("resumeWith", "(" + OBJECT + ")V", "java/util/function/Consumer", "accept", "(" + OBJECT + ")V", operands))
            .apply(subject);
        assertEquals(List.of("quiet(Ljava/lang/Object;)V"), missing);
    }

    @Test
    void onlyTheOverloadThatWasNamedIsPatched() throws Exception {
        ClassPatch patch = new ClassPatch(Lab.internalName(Shapes.class))
            .method(enter("overloaded", "(Ljava/lang/String;)Ljava/lang/String;", new HookCall("continuationCreated", "(" + OBJECT + ")V", arg(0))));
        ClassNode node = new ClassNode();
        new ClassReader(transformer.rewrite(getClass().getClassLoader(), patch.className, patch, false, Lab.bytesOf(Shapes.class))).accept(node, 0);
        List<String> hooked = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode made && made.owner.equals(MethodPatch.HOOKS)) hooked.add(method.name + method.desc);
            }
        }
        assertEquals(List.of("overloaded(Ljava/lang/String;)Ljava/lang/String;"), hooked);
    }

    @Test
    void aTaggedClassCarriesWhateverTheRuntimeHangsOnItsInstances() throws Exception {
        ClassPatch patch = new ClassPatch(SUBJECT).tagged();
        assertTrue(patch.changesShape(), "a field more: only for a class that is being loaded, never for a retransformed one");
        assertFalse(new ClassPatch(SUBJECT).method(around("read", "([B)I", BLOCK_ENTER, BLOCK_EXIT)).changesShape());

        byte[] once = transformer.rewrite(getClass().getClassLoader(), SUBJECT, patch, false, Lab.bytesOf(Subject.class));
        // A class that comes by the transformer a second time (another agent in front of this one retransforms it) is tagged once.
        byte[] twice = transformer.rewrite(getClass().getClassLoader(), SUBJECT, patch, false, once);
        Class<?> tagged = new Lab.Loader().with(Subject.class, twice).verified(Subject.class);

        Object first = instance(tagged, "a"), second = instance(tagged, "b");
        assertTrue(first instanceof Tagged);
        assertNull(((Tagged) first).coroutree$tag(), "no node yet");
        Object node = new Object();
        ((Tagged) first).coroutree$tag(node);
        assertSame(node, ((Tagged) first).coroutree$tag());
        assertNull(((Tagged) second).coroutree$tag(), "a slot per instance");
        assertEquals(1, java.util.Arrays.stream(tagged.getDeclaredFields()).filter(f -> f.getName().equals("coroutree$tag")).count());
        assertEquals(List.of(), sameOutcome(new Subject("a"), first, "mix", 1L, "b", 2.0, "d"), "and that is all that changed");
    }

    // ------------------------------------------------------------------ the rule all of it rests on

    @Test
    void insertedCodeIsStraightLine() {
        // No branch means no new stack map frame inside existing code, which is what lets classes be rewritten without
        // recomputing frames (and so without loading classes inside a transformer). Jumps before and after: the same.
        ClassPatch patch = new ClassPatch(SUBJECT).tagged()
            .method(around("sleep", "(JLjava/lang/String;)J", BLOCK_ENTER, BLOCK_EXIT))
            .method(around("run", "(Ljava/lang/Runnable;)V", null, new HookCall("continuationCreated", "(" + OBJECT + ")V", self())))
            .method(enterAndExit("childCancelled", "(" + THROWABLE + ")Z",
                new HookCall("exceptionCaught", "(" + THROWABLE + ")V", arg(0)),
                new HookCall("childCancelledResult", "(Z" + OBJECT + OBJECT + THROWABLE + ")V", returnValue(), self(), field(SUBJECT, "field", OBJECT), arg(0))))
            .method(exit("<init>", "(Ljava/lang/String;)V", new HookCall("jobCreated", "(" + OBJECT + OBJECT + ")V", self(), arg(0))))
            .method(beforeCall("resumeWith", "(" + OBJECT + ")V", "java/util/function/Consumer", "accept", "(" + OBJECT + ")V",
                new HookCall("continuationCompleted", "(" + OBJECT + OBJECT + OBJECT + ")V", callOperands(), self())));
        byte[] original = Lab.bytesOf(Subject.class);
        byte[] rewritten = transformer.rewrite(getClass().getClassLoader(), SUBJECT, patch, false, original);
        assertEquals(branches(original), branches(rewritten));
        new Lab.Loader().with(Subject.class, rewritten).verified(Subject.class);
    }

    private static int branches(byte[] classFile) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, 0);
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) count++;
            }
        }
        return count;
    }
}
