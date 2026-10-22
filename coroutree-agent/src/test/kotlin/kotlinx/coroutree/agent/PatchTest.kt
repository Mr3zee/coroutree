package kotlinx.coroutree.agent

import kotlinx.coroutree.agent.MethodPatch.HookCall
import kotlinx.coroutree.agent.fixtures.Shapes
import kotlinx.coroutree.agent.fixtures.Subject
import kotlinx.coroutree.runtime.Tagged
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode

/**
 * Hook calls woven into a class the way the hook table weaves them into the JDK and kotlinx.coroutines: rewritten by
 * the transformer's own path (frames expanded, none recomputed), then loaded by a JVM that verifies it and run next
 * to the class as it was compiled. The rewritten class calls the hooks it was told to, when it was told to, and does
 * everything else exactly as before.
 */
internal class PatchTest {
    private val transformer = Lab.transformer("")

    @BeforeEach
    fun forget() {
        HookLog.take()
    }

    /** The subject with one method patched, loaded and verified. */
    private fun patched(vararg patches: MethodPatch): Class<*> {
        val patch = ClassPatch(SUBJECT)
        for (method in patches) patch.method(method)
        val rewritten = transformer.rewrite(javaClass.classLoader, SUBJECT, patch, false, Lab.bytesOf(Subject::class.java))
        return Lab.Loader().with(Subject::class.java, rewritten).verified(Subject::class.java)
    }

    // ------------------------------------------------------------------ around

    @Test
    fun aroundCallsEnterOnceAndExitOnceOnEveryWayOutAndChangesNothingElse() {
        val patched = patched(MethodPatch.around("sleep", "(JLjava/lang/String;)J", BLOCK_ENTER, BLOCK_EXIT))
        val pair = listOf("blockEnter(5)", "blockExit()")
        assertEquals(pair, sameOutcome(Subject::class.java, patched, "sleep", 10L, "note"), "a normal return")
        assertEquals(pair, sameOutcome(Subject::class.java, patched, "sleep", 10L, null), "the other return")
        assertEquals(pair, sameOutcome(Subject::class.java, patched, "sleep", 13L, "note"), "an exception the method handles itself is its own business: its handlers keep priority over the catch-all")
        assertEquals(pair, sameOutcome(Subject::class.java, patched, "sleep", -1L, "note"), "the way out by exception")
    }

    @Test
    fun theExceptionThatLeavesAPatchedMethodIsTheVeryOneThatWasThrown() {
        val patched = patched(MethodPatch.around("run", "(Ljava/lang/Runnable;)V", null, HookCall("continuationCreated", "($OBJECT)V", Args.self())))
        val subject = instance(patched, "vt")
        val failure = IllegalStateException("task failed")
        val outcome = Lab.call(subject, "run", Runnable {
            HookLog.mark("task")
            throw failure
        })
        assertSame(failure, outcome.thrown)
        // No enter hook (VirtualThread.run has none); the exit hook gets `this`, the one thing the handler's frame knows.
        val calls = HookLog.take()
        assertEquals(3, calls.size)
        assertEquals("running vt", calls[0][0])
        assertEquals("task", calls[1][0])
        assertEquals("continuationCreated", calls[2][0])
        assertSame(subject, calls[2][1])

        Lab.call(subject, "run", Runnable { HookLog.mark("task") })
        assertEquals(listOf("running vt()", "task()", "continuationCreated($subject)"), HookLog.taken(), "and after the body on a normal return")
    }

    @Test
    fun aroundAnInstanceMethodWithSeveralReturns() {
        val patched = patched(MethodPatch.around("read", "([B)I", BLOCK_ENTER, BLOCK_EXIT))
        val original = Subject("s")
        val subject = instance(patched, "s")
        val pair = listOf("blockEnter(5)", "blockExit()")
        assertEquals(pair, sameOutcome(original, subject, "read", byteArrayOf(1, 0, 2)))
        assertEquals(pair, sameOutcome(original, subject, "read", ByteArray(0)))
        assertEquals(pair, sameOutcome(original, subject, "read", null))
    }

    // ------------------------------------------------------------------ enter, exit and the values they hand over

    @Test
    fun anEnterHookGetsTheParametersItNamesWhateverTheirSlots() {
        val patched = patched(
            // long and double take two slots each: the fourth parameter of an instance method is in slot 6.
            MethodPatch.enter("mix", "(J${OBJECT}DLjava/lang/String;)Ljava/lang/String;", HookCall("coroutineCreated", "($OBJECT$OBJECT$OBJECT)V", Args.self(), Args.arg(1), Args.arg(3))),
            // A static method has no `this` in slot 0.
            MethodPatch.enter("mixStatic", "(IJ$THROWABLE)Ljava/lang/String;", HookCall("exceptionCaught", "($THROWABLE)V", Args.arg(2)))
        )
        val original = Subject("s")
        val subject = instance(patched, "s")
        assertEquals(listOf("coroutineCreated($subject, b, d)"), sameOutcome(original, subject, "mix", 7L, "b", 0.25, "d"))
        assertEquals(listOf("exceptionCaught(IllegalStateException:boom)"), sameOutcome(Subject::class.java, patched, "mixStatic", 1, 2L, IllegalStateException("boom")))
    }

    @Test
    fun anIntegerParameterIsHandedOverAsOne() {
        val patched = patched(MethodPatch.enter("mixStatic", "(IJ$THROWABLE)Ljava/lang/String;", HookCall("blockEnter", "(I)V", Args.arg(0))))
        assertEquals(listOf("blockEnter(42)"), sameOutcome(Subject::class.java, patched, "mixStatic", 42, 2L, IllegalStateException("boom")))
    }

    @Test
    fun anExitHookOnAConstructorSeesTheObjectOnceItIsInitialized() {
        val patched = patched(MethodPatch.exit("<init>", "(Ljava/lang/String;)V", HookCall("jobCreated", "($OBJECT$OBJECT)V", Args.self(), Args.arg(0))))
        val subject = instance(patched, "job")
        var calls = HookLog.take()
        assertEquals(1, calls.size)
        assertSame(subject, calls[0][1])
        assertEquals("job", calls[0][2])

        // The constructor that delegates to the hooked one: one object, one call.
        val byDefault = patched.getConstructor().newInstance()
        calls = HookLog.take()
        assertEquals(1, calls.size)
        assertSame(byDefault, calls[0][1])
        assertEquals("default", calls[0][2])

        // A constructor that throws creates nothing.
        assertThrows<ReflectiveOperationException> { instance(patched, null) }
        assertEquals(emptyList<String>(), HookLog.taken())
    }

    @Test
    fun anExitHookGetsTheValueAboutToBeReturnedAndTheCallerStillGetsIt() {
        val patched = patched(MethodPatch.exit("create", "($OBJECT)$OBJECT", HookCall("continuationCreated", "($OBJECT)V", Args.returnValue())))
        val subject = instance(patched, "s")
        val outcome = Lab.call(subject, "create", "seed")
        assertEquals("made of seed", outcome.value)
        val calls = HookLog.take()
        assertEquals(1, calls.size)
        assertSame(outcome.value, calls[0][1])

        assertNull(Lab.call(subject, "create", null).value)
        assertEquals(listOf("continuationCreated(null)"), HookLog.taken(), "at the other return too")
    }

    @Test
    fun enterAndExitWithArgumentsReadFromFieldsAndGettersAsTheHooksOfChildCancelledAre() {
        val fromField = Args.field(SUBJECT, "field", OBJECT)
        val fromGetter = Args.call(Args.self(), Opcodes.INVOKEVIRTUAL, SUBJECT, "context", "()$OBJECT")
        val patched = patched(
            MethodPatch.enterAndExit(
                "childCancelled", "($THROWABLE)Z",
                HookCall("childCancelled", "($OBJECT$OBJECT$THROWABLE)V", fromField, fromGetter, Args.arg(0)),
                HookCall("childCancelledResult", "(Z$OBJECT$OBJECT$THROWABLE)V", Args.returnValue(), fromField, fromGetter, Args.arg(0))
            )
        )
        val original = Subject("p")
        val subject = instance(patched, "p")
        assertEquals(
            listOf(
                "childCancelled(field-value, context of p, IllegalStateException:x)",
                "childCancelledResult(true, field-value, context of p, IllegalStateException:x)"
            ),
            sameOutcome(original, subject, "childCancelled", IllegalStateException("x"))
        )
        assertEquals(
            listOf(
                "childCancelled(field-value, context of p, RuntimeException:y)",
                "childCancelledResult(false, field-value, context of p, RuntimeException:y)"
            ),
            sameOutcome(original, subject, "childCancelled", RuntimeException("y"))
        )
    }

    @Test
    fun theValueAboutToBeReturnedCanOnlyBeHandedOverWhenItIsOneSlotWide() {
        val hook = HookCall("continuationCreated", "($OBJECT)V", Args.returnValue())
        // A long or a double cannot be duplicated the way one slot is; the patch says so when it is applied, not at run time.
        assertThrows<IllegalStateException> { patched(MethodPatch.exit("wide", "()J", hook)) }
        assertThrows<IllegalStateException> { patched(MethodPatch.exit("ratio", "()D", hook)) }
        assertThrows<IllegalStateException>("nothing is returned") { patched(MethodPatch.exit("quiet", "($OBJECT)V", hook)) }
        assertThrows<IllegalStateException>("there is no value when leaving by exception") { patched(MethodPatch.around("create", "($OBJECT)$OBJECT", null, hook)) }
    }

    // ------------------------------------------------------------------ before a call

    @Test
    fun aHookInFrontOfACallGetsItsReceiverAndArgumentAndTheCallStillHappens() {
        val patch = MethodPatch.beforeCall(
            "resumeWith", "($OBJECT)V", "java/util/function/Consumer", "accept", "($OBJECT)V",
            HookCall("continuationCompleted", "($OBJECT$OBJECT$OBJECT)V", Args.callOperands(), Args.self())
        )
        val patched = patched(patch)
        val subject = instance(patched, "frame")
        val sink = patched.getField("sink").get(subject)

        Lab.call(subject, "resumeWith", "a")
        assertEquals(listOf("continuationCompleted($sink, s:a, $subject)", "accepted(s:a)", "resumed()"), HookLog.taken())

        // Every call the method makes to it, wherever in the method.
        Lab.call(subject, "resumeWith", 42)
        assertEquals(
            listOf(
                "continuationCompleted($sink, 42, $subject)", "accepted(42)",
                "continuationCompleted($sink, twice, $subject)", "accepted(twice)", "resumed()"
            ),
            HookLog.taken()
        )
    }

    // ------------------------------------------------------------------ a class patch as a whole

    @Test
    fun aRequiredMethodThatCannotBePatchedIsNamedAndAnOptionalOneIsNot() {
        val hook = HookCall("continuationCreated", "($OBJECT)V", Args.arg(0))
        val shapes = ClassNode()
        ClassReader(Lab.bytesOf(Shapes::class.java)).accept(shapes, ClassReader.EXPAND_FRAMES)
        var missing = ClassPatch(Lab.internalName(Shapes::class.java))
            .method(MethodPatch.enter("gone", "($OBJECT)V", hook))
            .method(MethodPatch.enter("goneOptional", "($OBJECT)V", hook).optional())
            .method(MethodPatch.enter("later", "($OBJECT)V", hook)) // abstract in this version of the class
            .method(MethodPatch.enter("elsewhere", "($OBJECT)V", hook)) // native
            .method(MethodPatch.enter("elsewhere", "($OBJECT)V", hook).optional())
            .method(MethodPatch.enter("overloaded", "(I)Ljava/lang/String;", hook)) // the name is there, the signature is not
            .method(MethodPatch.enter("overloaded", "(Ljava/lang/String;)Ljava/lang/String;", hook))
            .apply(shapes)
        assertEquals(
            listOf("gone(Ljava/lang/Object;)V", "later(Ljava/lang/Object;)V", "elsewhere(Ljava/lang/Object;)V", "overloaded(I)Ljava/lang/String;"),
            missing
        )

        // A method that does not make the call a hook was to go in front of is as good as a method that is not there.
        val subject = ClassNode()
        ClassReader(Lab.bytesOf(Subject::class.java)).accept(subject, ClassReader.EXPAND_FRAMES)
        val operands = HookCall("continuationCompleted", "($OBJECT$OBJECT$OBJECT)V", Args.callOperands(), Args.self())
        missing = ClassPatch(SUBJECT)
            .method(MethodPatch.beforeCall("quiet", "($OBJECT)V", "java/util/function/Consumer", "accept", "($OBJECT)V", operands))
            .method(MethodPatch.beforeCall("resumeWith", "($OBJECT)V", "java/util/function/Consumer", "accept", "($OBJECT)V", operands))
            .apply(subject)
        assertEquals(listOf("quiet(Ljava/lang/Object;)V"), missing)
    }

    @Test
    fun onlyTheOverloadThatWasNamedIsPatched() {
        val patch = ClassPatch(Lab.internalName(Shapes::class.java))
            .method(MethodPatch.enter("overloaded", "(Ljava/lang/String;)Ljava/lang/String;", HookCall("continuationCreated", "($OBJECT)V", Args.arg(0))))
        val node = ClassNode()
        ClassReader(transformer.rewrite(javaClass.classLoader, patch.className, patch, false, Lab.bytesOf(Shapes::class.java))).accept(node, 0)
        val hooked = node.methods.flatMap { method ->
            method.instructions.filter { it is MethodInsnNode && it.owner == MethodPatch.HOOKS }.map { method.name + method.desc }
        }
        assertEquals(listOf("overloaded(Ljava/lang/String;)Ljava/lang/String;"), hooked)
    }

    @Test
    fun aTaggedClassCarriesWhateverTheRuntimeHangsOnItsInstances() {
        val patch = ClassPatch(SUBJECT).tagged()
        assertTrue(patch.changesShape(), "a field more: only for a class that is being loaded, never for a retransformed one")
        assertFalse(ClassPatch(SUBJECT).method(MethodPatch.around("read", "([B)I", BLOCK_ENTER, BLOCK_EXIT)).changesShape())

        val once = transformer.rewrite(javaClass.classLoader, SUBJECT, patch, false, Lab.bytesOf(Subject::class.java))
        // A class that comes by the transformer a second time (another agent in front of this one retransforms it) is tagged once.
        val twice = transformer.rewrite(javaClass.classLoader, SUBJECT, patch, false, once)
        val tagged = Lab.Loader().with(Subject::class.java, twice).verified(Subject::class.java)

        val first = instance(tagged, "a")
        val second = instance(tagged, "b")
        assertTrue(first is Tagged)
        assertNull((first as Tagged).`coroutree$tag`(), "no node yet")
        val node = Any()
        first.`coroutree$tag`(node)
        assertSame(node, first.`coroutree$tag`())
        assertNull((second as Tagged).`coroutree$tag`(), "a slot per instance")
        assertEquals(1, tagged.declaredFields.count { it.name == "coroutree\$tag" })
        assertEquals(emptyList<String>(), sameOutcome(Subject("a"), first, "mix", 1L, "b", 2.0, "d"), "and that is all that changed")
    }

    // ------------------------------------------------------------------ the rule all of it rests on

    @Test
    fun insertedCodeIsStraightLine() {
        // No branch means no new stack map frame inside existing code, which is what lets classes be rewritten without
        // recomputing frames (and so without loading classes inside a transformer). Jumps before and after: the same.
        val patch = ClassPatch(SUBJECT).tagged()
            .method(MethodPatch.around("sleep", "(JLjava/lang/String;)J", BLOCK_ENTER, BLOCK_EXIT))
            .method(MethodPatch.around("run", "(Ljava/lang/Runnable;)V", null, HookCall("continuationCreated", "($OBJECT)V", Args.self())))
            .method(
                MethodPatch.enterAndExit(
                    "childCancelled", "($THROWABLE)Z",
                    HookCall("exceptionCaught", "($THROWABLE)V", Args.arg(0)),
                    HookCall("childCancelledResult", "(Z$OBJECT$OBJECT$THROWABLE)V", Args.returnValue(), Args.self(), Args.field(SUBJECT, "field", OBJECT), Args.arg(0))
                )
            )
            .method(MethodPatch.exit("<init>", "(Ljava/lang/String;)V", HookCall("jobCreated", "($OBJECT$OBJECT)V", Args.self(), Args.arg(0))))
            .method(
                MethodPatch.beforeCall(
                    "resumeWith", "($OBJECT)V", "java/util/function/Consumer", "accept", "($OBJECT)V",
                    HookCall("continuationCompleted", "($OBJECT$OBJECT$OBJECT)V", Args.callOperands(), Args.self())
                )
            )
        val original = Lab.bytesOf(Subject::class.java)
        val rewritten = transformer.rewrite(javaClass.classLoader, SUBJECT, patch, false, original)
        assertEquals(branches(original), branches(rewritten))
        Lab.Loader().with(Subject::class.java, rewritten).verified(Subject::class.java)
    }

    companion object {
        private const val OBJECT = "Ljava/lang/Object;"
        private const val THROWABLE = "Ljava/lang/Throwable;"
        private val SUBJECT = Lab.internalName(Subject::class.java)

        private fun instance(type: Class<*>, name: String?): Any = type.getConstructor(String::class.java).newInstance(name)

        /** The same call on the class as compiled and on the patched one: same outcome; returns what the hooks saw of the second. */
        private fun sameOutcome(original: Any, patched: Any, method: String, vararg arguments: Any?): List<String> {
            val expected = Lab.call(original, method, *arguments)
            HookLog.take()
            val actual = Lab.call(patched, method, *arguments)
            assertEquals(expected.toString(), actual.toString(), method)
            return HookLog.taken()
        }

        private val BLOCK_ENTER = HookCall("blockEnter", "(I)V", Args.constant(5))
        private val BLOCK_EXIT = HookCall("blockExit", "()V")

        private fun branches(classFile: ByteArray?): Int {
            val node = ClassNode()
            ClassReader(classFile).accept(node, 0)
            return node.methods.sumOf { method ->
                method.instructions.count { it is JumpInsnNode || it is TableSwitchInsnNode || it is LookupSwitchInsnNode }
            }
        }
    }
}
