package kotlinx.coroutree.agent

import com.acme.app.App
import com.acme.app.Catching
import kotlinx.coroutree.agent.fixtures.Subject
import kotlinx.coroutree.runtime.RuntimeProbe
import kotlinx.coroutree.runtime.Tagged
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream

/**
 * The transformer returns null for a class that is neither in the hook table nor a project class with something to
 * instrument. It never throws into class loading, whatever the bytes are.
 */
internal class CoroutreeTransformerTest {
    private val transformer = Lab.transformer("include=com.acme")

    @Test
    fun aClassThatIsNeitherInTheTableNorTheProjectsIsLeftAlone() {
        assertNull(transformer.transform(APP, "org/lib/Thing", null, null, Lab.bytesOf(Subject::class.java)))
        assertNull(transformer.transform(APP, null, null, null, Lab.bytesOf(Subject::class.java)), "a class without a name: a hidden class, a lambda")
        // The bootstrap loader (null) holds no project classes.
        assertNull(transformer.transform(null, Lab.internalName(Catching::class.java), null, null, Lab.bytesOf(Catching::class.java)))
        // A project class without a catch block has nothing to instrument.
        assertNull(transformer.transform(APP, Lab.internalName(App::class.java), null, null, Lab.bytesOf(App::class.java)))
        assertNotNull(transformer.transform(APP, Lab.internalName(Catching::class.java), null, null, Lab.bytesOf(Catching::class.java)))
        // Without `include`, no class is a project class for the transformer, because the project would otherwise be every class there is.
        assertNull(Lab.transformer("").transform(APP, Lab.internalName(Catching::class.java), null, null, Lab.bytesOf(Catching::class.java)))
        assertNull(Lab.transformer("include=com.acme,exclude=com.acme.app").transform(APP, Lab.internalName(Catching::class.java), null, null, Lab.bytesOf(Catching::class.java)))
    }

    @Test
    fun bytesThatAreNoClassNeverThrowIntoClassLoading() {
        val real = Lab.bytesOf(Catching::class.java)
        val broken = listOf(
            ByteArray(0),
            byteArrayOf(1, 2, 3),
            "not a class file at all, just text of some length".toByteArray(Charsets.US_ASCII),
            real.copyOf(real.size / 2),
            real.copyOf(12),
        )
        for (bytes in broken) {
            val said = stderrOf {
                // A project class, a library class and a class of the hook table.
                assertNull(transformer.transform(APP, Lab.internalName(Catching::class.java), null, null, bytes))
                assertNull(transformer.transform(APP, "org/lib/Thing", null, null, bytes))
                assertNull(transformer.transform(null, "java/lang/Thread", Thread::class.java, null, bytes))
            }
            assertTrue("can't instrument com.acme.app.Catching" in said, "and it is said, ${bytes.size} bytes: $said")
            assertTrue("can't instrument java.lang.Thread" in said, said)
        }
    }

    @Test
    fun aJdkClassOfTheTableIsRewrittenWhenRetransformedAndAClassThatWouldChangeShapeIsNot() {
        // JDK classes are loaded before the agent and retransformed, and a retransformation can't add a field.
        val thread = Lab.bytesOf(null, "java/lang/Thread")
        assertNotNull(transformer.transform(null, "java/lang/Thread", Thread::class.java, null, thread))
        assertNotNull(transformer.transform(null, "java/lang/Thread", null, null, thread))

        val jobSupport = Lab.bytesOf(APP, JOB_SUPPORT)
        val said = stderrOf {
            assertNull(transformer.transform(APP, JOB_SUPPORT, Any::class.java, null, jobSupport), "the tag is a field: it cannot be added to a class that is already loaded")
        }
        assertTrue("kotlinx.coroutines.JobSupport was loaded before the agent" in said, said)
    }

    @Test
    fun theSourceMapOfEveryApplicationClassIsRememberedEvenIfTheClassIsNotChanged() {
        // Any class can be in a captured stack with inlined code in it, so the source map of an unchanged class is read too.
        assertNull(transformer.transform(APP, "org/lib/a/Lib", null, null, classFile("org/lib/a/Lib", smap("org/lib/a/Lib"), 0)))
        assertEquals(2, framesAt36("org.lib.a.Lib"))
        assertNull(transformer.transform(APP, "com/acme/a/Project", null, null, classFile("com/acme/a/Project", smap("com/acme/a/Project"), 0)))
        assertEquals(2, framesAt36("com.acme.a.Project"))

        // Not for a bootstrap class, a JDK class or an agent class: they hold no inlined Kotlin, and the JVM loads thousands of them.
        assertNull(transformer.transform(null, "org/lib/a/Boot", null, null, classFile("org/lib/a/Boot", smap("org/lib/a/Boot"), 0)))
        assertEquals(1, framesAt36("org.lib.a.Boot"))
        assertNull(transformer.transform(APP, "java/fake/Jdk", null, null, classFile("java/fake/Jdk", smap("java/fake/Jdk"), 0)))
        assertEquals(1, framesAt36("java.fake.Jdk"))

        // A class without a source map, or with other text in its place, adds no frame and throws nothing.
        assertNull(transformer.transform(APP, "org/lib/a/Plain", null, null, classFile("org/lib/a/Plain", null, 0)))
        assertNull(transformer.transform(APP, "org/lib/a/Odd", null, null, classFile("org/lib/a/Odd", "SMAP\nMain.kt\nKotlin\n*S Kotlin\n*F\n+ x y\n*L\nq#w:e\n", 0)))
        assertEquals(1, framesAt36("org.lib.a.Plain"))
        assertEquals(1, framesAt36("org.lib.a.Odd"))
    }

    @Test
    fun theMainBehindSuspendFunMainIsOnTheLineOfTheDeclaration() {
        // The `main(String[])` that the compiler generates for `suspend fun main` creates the coroutine and has no line
        // numbers. Its frame gets the first line of the suspending `main`.
        transformer.transform(APP, "org/lib/b/ServerKt", null, null, classFile("org/lib/b/ServerKt", null, 16))
        transformer.transform(APP, "com/acme/b/ServerKt", null, null, classFile("com/acme/b/ServerKt", null, 21))
        assertEquals(listOf("org.lib.b.ServerKt.main(Server.kt:16)"), RuntimeProbe.logicalFrames("org.lib.b.ServerKt", "main", "Server.kt", -1))
        assertEquals(listOf("com.acme.b.ServerKt.main(Server.kt:21)"), RuntimeProbe.logicalFrames("com.acme.b.ServerKt", "main", "Server.kt", -1))
        assertEquals(listOf("org.lib.b.ServerKt.main(Server.kt:40)"), RuntimeProbe.logicalFrames("org.lib.b.ServerKt", "main", "Server.kt", 40), "a frame that has a line keeps it")
    }

    @Test
    fun aVersionOfCoroutinesOutsideTheTestedRangeIsSaidAndInstrumentedAllTheSame() {
        val jobSupport = Lab.bytesOf(APP, JOB_SUPPORT)
        val tested = HookTable.TESTED_COROUTINES
        val lowest = "${tested[0]}.${tested[1]}"
        val highest = "${tested[2]}.${tested[3]}"
        val cases = listOf<Pair<String?, String?>>(
            "$lowest.0" to null,
            "$highest.0\n" to null,
            "$highest.99-SNAPSHOT" to null,
            "${tested[0]}.${tested[1] - 1}.9" to "outside the tested range",
            "${tested[2]}.${tested[3] + 1}.0" to "outside the tested range",
            "${tested[2] + 1}.0.0-RC" to "outside the tested range",
            "banana" to "Unrecognized",
            "1" to "Unrecognized",
            null to "Can't find the version",
        )
        for ((version, complaint) in cases) {
            val said = stderrOf {
                assertNotNull(transformer.transform(coroutinesOfVersion(version), JOB_SUPPORT, null, null, jobSupport), "version $version")
            }
            if (complaint == null) {
                assertEquals("", said, "version $version is one the agent was tested with")
            } else {
                assertTrue(complaint in said, "version $version: $said")
                assertTrue(lowest in said && highest in said, "the range that was tested is named: $said")
            }
        }
    }

    @Test
    fun aLibraryWhoseInternalsAreNotWhatTheTableExpectsIsReportedLoudlyNotSilentlyMistraced() {
        // A `JobSupport` of some other version: the class exists, the methods the hooks go into don't.
        val other = ClassWriter(0)
        other.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, JOB_SUPPORT, null, "java/lang/Object", null)
        val constructor = other.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()
        other.visitEnd()

        var rewritten: ByteArray? = null
        val said = stderrOf {
            rewritten = transformer.transform(coroutinesOfVersion("1.3.0"), JOB_SUPPORT, null, null, other.toByteArray())
        }
        assertTrue("Unsupported version of kotlinx.coroutines.JobSupport" in said, said)
        for (method in listOf("cancel", "parentCancelled", "notifyCancelling", "makeCompletingOnce", "completeStateFinalization")) {
            assertTrue(method in said, "every missing method is named, $method: $said")
        }
        // The class still loads, and it still has the tag that holds a job's node.
        val loaded = Lab.Loader().with("kotlinx.coroutines.JobSupport", rewritten).verified("kotlinx.coroutines.JobSupport")
        assertTrue(Tagged::class.java.isAssignableFrom(loaded))
        val job = loaded.getConstructor().newInstance() as Tagged
        job.`coroutree$tag`("its node")
        assertEquals("its node", job.`coroutree$tag`())
    }

    companion object {
        private val APP: ClassLoader = CoroutreeTransformerTest::class.java.classLoader
        private const val JOB_SUPPORT = "kotlinx/coroutines/JobSupport"

        /** Returns what `action` wrote to stderr. The agent echoes its diagnostics there. */
        private fun stderrOf(action: () -> Unit): String {
            val original = System.err
            val captured = ByteArrayOutputStream()
            System.setErr(PrintStream(captured, true, Charsets.UTF_8))
            try {
                action()
            } finally {
                System.setErr(original)
            }
            val said = captured.toString(Charsets.UTF_8)
            original.print(said)
            return said
        }

        /** A minimal class file, with a source map when `smap` isn't null and a `suspend fun main` when `suspendMainLine` is positive. */
        private fun classFile(internalName: String, smap: String?, suspendMainLine: Int): ByteArray {
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
            writer.visitSource("Main.kt", smap)
            if (suspendMainLine > 0) {
                val main = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "main", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", null, null)
                main.visitCode()
                val start = Label()
                main.visitLabel(start)
                main.visitLineNumber(suspendMainLine, start)
                main.visitInsn(Opcodes.ACONST_NULL)
                val next = Label()
                main.visitLabel(next)
                main.visitLineNumber(suspendMainLine + 3, next)
                main.visitInsn(Opcodes.ARETURN)
                main.visitMaxs(1, 1)
                main.visitEnd()
            }
            writer.visitEnd()
            return writer.toByteArray()
        }

        private fun smap(className: String): String {
            val files = "*F\n+ 1 Main.kt\n$className\n+ 2 Util.kt\ncom/acme/util/UtilKt\n"
            return "SMAP\nMain.kt\nKotlin\n*S Kotlin\n" + files + "*L\n23#2,3:36\n*S KotlinDebug\n" + files + "*L\n14#1:36,3\n*E\n"
        }

        private fun framesAt36(className: String): Int = RuntimeProbe.logicalFrames(className, "run", "Main.kt", 36).size

        /** The test class path with the kotlinx.coroutines version file replaced by `version`, or absent when `version` is null. */
        private fun coroutinesOfVersion(version: String?): ClassLoader = object : ClassLoader(APP) {
            override fun getResourceAsStream(name: String): InputStream? {
                if (name != "META-INF/kotlinx_coroutines_core.version") return super.getResourceAsStream(name)
                return version?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) }
            }
        }
    }
}
