package kotlinx.coroutree.agent

import kotlinx.coroutree.agent.MethodPatch.HookCall
import kotlinx.coroutree.runtime.Hooks
import kotlinx.coroutree.runtime.Tagged
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.BasicVerifier
import java.lang.reflect.Modifier
import java.util.TreeSet
import java.util.concurrent.CancellationException

/**
 * The hook table is the agent's whole coupling to the internals of the JDK, of the Kotlin standard library and of
 * kotlinx.coroutines. Here it is held against the real things: the `Hooks` class it calls into, the JDK the
 * tests run on, and the kotlinx.coroutines the project is built with.
 */
internal class HookTableTest {
    private val table = HookTable()
    private val transformer = Lab.transformer("")

    private fun classes(jdk: Boolean): List<String> = table.classNames().filter { isJdk(it) == jdk }.sorted()

    /** Every hook call the table can weave in, by reading the table itself: optional entries too, whatever this JDK has. */
    private fun hookCallsOfTheTable(): List<String> {
        val methods = ClassPatch::class.java.getDeclaredField("methods")
        val onEnter = MethodPatch::class.java.getDeclaredField("onEnter")
        val onExit = MethodPatch::class.java.getDeclaredField("onExit")
        val hook = HookCall::class.java.getDeclaredField("hook")
        val descriptor = HookCall::class.java.getDeclaredField("hookDescriptor")
        for (field in listOf(methods, onEnter, onExit, hook, descriptor)) field.isAccessible = true
        return table.classNames().flatMap { className ->
            (methods.get(table.get(className)) as List<*>).flatMap { patch ->
                listOf(onEnter, onExit).mapNotNull { side -> side.get(patch) }.map { call -> "${hook.get(call)}${descriptor.get(call)}" }
            }
        }
    }

    @Test
    fun everyEntryCallsAHookThatExistsWithThatSignatureAndEveryHookHasAnEntry() {
        val hooks = Hooks::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }
            .mapTo(TreeSet()) { it.name + Type.getMethodDescriptor(it) }
        val called = TreeSet(hookCallsOfTheTable())
        assertFalse(called.isEmpty())
        assertEquals(emptySet<String>(), called - hooks, "the table calls hooks that Hooks does not have: a NoSuchMethodError inside Thread.start or JobSupport")

        // New hook = one entry in HookTable + one method in Hooks. The one hook that has no entry is the one CatchPatch
        // puts into every catch block of the project's classes.
        assertEquals(setOf("exceptionCaught(Ljava/lang/Throwable;)V"), hooks - called)
    }

    @Test
    fun noClassOfTheJdkChangesShape() {
        // They are retransformed, and a retransformed class can get neither a field nor a method. Only the job's tag
        // changes a shape, and kotlinx.coroutines is patched while it is being loaded.
        val shaped = table.classNames().filter { table.get(it).changesShape() }
        assertEquals(listOf(HookTable.JOB_SUPPORT), shaped)
        assertFalse(classes(true).isEmpty())
        assertFalse(classes(false).isEmpty())
    }

    @Test
    fun theJdkTheTestsRunOnHasEveryRequiredMethodAndItsRewrittenClassesAreSound() {
        for (name in classes(true)) {
            val original = Lab.bytesOf(null, name)
            assertEquals(emptyList<Any>(), table.get(name).apply(read(original)), "required methods missing in $name of JDK ${Runtime.version()}")

            val rewritten = transformer.rewrite(null, name, table.get(name), false, original)
            assertNotNull(rewritten, name)
            val node = read(rewritten!!)
            assertEquals(read(original).fields.size, node.fields.size, name)
            assertEquals(read(original).methods.size, node.methods.size, name)
            var hooked = 0
            for (method in node.methods) {
                if (method.instructions.none { it is MethodInsnNode && it.owner == MethodPatch.HOOKS }) continue
                hooked++
                // These classes cannot be loaded a second time to let the JVM verify them. What the inserted code does
                // to the operand stack and the local variables can still be checked, instruction by instruction.
                try {
                    Analyzer<BasicValue>(BasicVerifier()).analyze(node.name, method)
                } catch (e: AnalyzerException) {
                    throw AssertionError("$name.${method.name}${method.desc} is not sound after rewriting", e)
                }
            }
            assertTrue(hooked > 0, "nothing was hooked in $name")
        }
    }

    /**
     * The Kotlin standard library and kotlinx.coroutines as the agent would leave them: every class of the table is
     * rewritten on its way into a class loader of its own, and the JVM verifies it there against its old frames.
     */
    private inner class RewrittenLibraries : ClassLoader(APP) {
        val rewritten = HashSet<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                if (!name.startsWith("kotlin.") && !name.startsWith("kotlinx.coroutines.")) return super.loadClass(name, resolve)
                findLoadedClass(name)?.let { return it }
                val internalName = name.replace('.', '/')
                var bytes = (APP.getResourceAsStream("$internalName.class") ?: throw ClassNotFoundException(name)).use { it.readAllBytes() }
                val patch = table.get(internalName)
                if (patch != null) {
                    bytes = transformer.rewrite(this, internalName, patch, false, bytes)
                    rewritten.add(internalName)
                }
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
    }

    @Test
    fun theCoroutinesTheProjectIsBuiltWithHaveEveryRequiredMethodAndVerifyAfterRewriting() {
        val libraries = RewrittenLibraries()
        for (name in classes(false)) {
            assertEquals(emptyList<Any>(), table.get(name).apply(read(Lab.bytesOf(APP, name))), "required methods missing in $name")
            // Linked and initialised: a class whose inserted code or hand-written frame the verifier rejects fails here.
            val loaded = Class.forName(name.replace('/', '.'), true, libraries)
            assertTrue(loaded.classLoader === libraries && name in libraries.rewritten, name)
        }

        // And they still work. A job of the rewritten library: created, cancelled and completed through the hooked
        // methods (tracing is off, the hooks return at their first check), carrying the tag the runtime hangs its node on.
        val jobType = libraries.loadClass("kotlinx.coroutines.Job")
        val jobFactory = libraries.loadClass("kotlinx.coroutines.JobKt").getMethod("Job", jobType)
        val job = jobFactory.invoke(null, null)
        assertTrue(job is Tagged, "JobSupport implements Tagged once the agent has seen it")
        val tagged = job as Tagged
        tagged.`coroutree$tag`("the node")
        assertEquals("the node", tagged.`coroutree$tag`())
        val child = jobFactory.invoke(null, job)
        jobType.getMethod("cancel", CancellationException::class.java).invoke(job, null)
        assertEquals(true, jobType.getMethod("isCancelled").invoke(job))
        assertEquals(true, jobType.getMethod("isCancelled").invoke(child), "the parent took its child along")
        assertEquals(true, jobType.getMethod("isCompleted").invoke(job))
    }

    @Test
    fun theTestedRangeOfCoroutinesEndsAtTheVersionTheProjectIsBuiltWith() {
        val stream = APP.getResourceAsStream("META-INF/kotlinx_coroutines_core.version")
        assertNotNull(stream, "kotlinx-coroutines-core says its version, which is what the agent reads at run time")
        val version = stream!!.use { it.readAllBytes().toString(Charsets.UTF_8).trim() }
        val parts = version.split('.', '-')
        val tested = HookTable.TESTED_COROUTINES
        assertEquals(4, tested.size)
        assertTrue(tested[0] < tested[2] || tested[0] == tested[2] && tested[1] <= tested[3], "from … to")
        assertEquals(
            "${parts[0]}.${parts[1]}", "${tested[2]}.${tested[3]}",
            "the corpus runs with kotlinx.coroutines $version (the version catalog): the range the agent claims is extended together with it, never alone"
        )
    }

    companion object {
        private val APP: ClassLoader = HookTableTest::class.java.classLoader

        private fun isJdk(internalName: String): Boolean =
            internalName.startsWith("java/") || internalName.startsWith("sun/") || internalName.startsWith("jdk/")

        private fun read(bytes: ByteArray): ClassNode {
            val node = ClassNode()
            ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES)
            return node
        }
    }
}
