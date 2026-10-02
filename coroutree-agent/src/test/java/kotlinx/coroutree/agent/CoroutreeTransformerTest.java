package kotlinx.coroutree.agent;

import com.acme.app.App;
import com.acme.app.Catching;
import kotlinx.coroutree.agent.fixtures.Subject;
import kotlinx.coroutree.runtime.RuntimeProbe;
import kotlinx.coroutree.runtime.Tagged;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the transformer does with a class the JVM hands it: nothing (null) unless the class is in the hook table or is
 * the project's and has something to instrument; and never an exception into class loading, whatever the bytes are.
 */
class CoroutreeTransformerTest {
    private static final ClassLoader APP = CoroutreeTransformerTest.class.getClassLoader();
    private static final String JOB_SUPPORT = "kotlinx/coroutines/JobSupport";

    private final CoroutreeTransformer transformer = Lab.transformer("include=com.acme");

    /** What the agent said on stderr meanwhile: its diagnostics are echoed there. */
    private static String stderrOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        String said = captured.toString(StandardCharsets.UTF_8);
        original.print(said);
        return said;
    }

    /** A minimal class file, optionally with a source map and with a {@code suspend fun main} declared on a line. */
    private static byte[] classFile(String internalName, String smap, int suspendMainLine) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitSource("Main.kt", smap);
        if (suspendMainLine > 0) {
            MethodVisitor main = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", null, null);
            main.visitCode();
            Label start = new Label();
            main.visitLabel(start);
            main.visitLineNumber(suspendMainLine, start);
            main.visitInsn(Opcodes.ACONST_NULL);
            Label next = new Label();
            main.visitLabel(next);
            main.visitLineNumber(suspendMainLine + 3, next);
            main.visitInsn(Opcodes.ARETURN);
            main.visitMaxs(1, 1);
            main.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String smap(String className) {
        String files = "*F\n+ 1 Main.kt\n" + className + "\n+ 2 Util.kt\ncom/acme/util/UtilKt\n";
        return "SMAP\nMain.kt\nKotlin\n*S Kotlin\n" + files + "*L\n23#2,3:36\n*S KotlinDebug\n" + files + "*L\n14#1:36,3\n*E\n";
    }

    private static int framesAt36(String className) {
        return RuntimeProbe.logicalFrames(className, "run", "Main.kt", 36).size();
    }

    @Test
    void aClassThatIsNeitherInTheTableNorTheProjectsIsLeftAlone() {
        assertNull(transformer.transform(APP, "org/lib/Thing", null, null, Lab.bytesOf(Subject.class)));
        assertNull(transformer.transform(APP, null, null, null, Lab.bytesOf(Subject.class)), "a class without a name: a hidden class, a lambda");
        // Project classes come from an application class loader; the bootstrap loader never holds them.
        assertNull(transformer.transform(null, Lab.internalName(Catching.class), null, null, Lab.bytesOf(Catching.class)));
        // A project class with nothing to instrument is not rewritten for the sake of it.
        assertNull(transformer.transform(APP, Lab.internalName(App.class), null, null, Lab.bytesOf(App.class)));
        assertNotNull(transformer.transform(APP, Lab.internalName(Catching.class), null, null, Lab.bytesOf(Catching.class)));
        // Without project packages nothing is the project's: "everything" is not something to instrument.
        assertNull(Lab.transformer("").transform(APP, Lab.internalName(Catching.class), null, null, Lab.bytesOf(Catching.class)));
        assertNull(Lab.transformer("include=com.acme,exclude=com.acme.app").transform(APP, Lab.internalName(Catching.class), null, null, Lab.bytesOf(Catching.class)));
    }

    @Test
    void bytesThatAreNoClassNeverThrowIntoClassLoading() {
        byte[] real = Lab.bytesOf(Catching.class);
        byte[][] broken = {
            new byte[0],
            new byte[] {1, 2, 3},
            "not a class file at all, just text of some length".getBytes(StandardCharsets.US_ASCII),
            Arrays.copyOf(real, real.length / 2),
            Arrays.copyOf(real, 12),
        };
        for (byte[] bytes : broken) {
            String said = stderrOf(() -> {
                // The project's, a library's, and one from the hook table.
                assertNull(transformer.transform(APP, Lab.internalName(Catching.class), null, null, bytes));
                assertNull(transformer.transform(APP, "org/lib/Thing", null, null, bytes));
                assertNull(transformer.transform(null, "java/lang/Thread", Thread.class, null, bytes));
            });
            assertTrue(said.contains("cannot instrument com.acme.app.Catching"), "and it is said, " + bytes.length + " bytes: " + said);
            assertTrue(said.contains("cannot instrument java.lang.Thread"), said);
        }
    }

    @Test
    void aJdkClassOfTheTableIsRewrittenWhenRetransformedAndAClassThatWouldChangeShapeIsNot() {
        // The JDK's classes were loaded long before the agent; they are retransformed, which allows no new field.
        byte[] thread = Lab.bytesOf(null, "java/lang/Thread");
        assertNotNull(transformer.transform(null, "java/lang/Thread", Thread.class, null, thread));
        assertNotNull(transformer.transform(null, "java/lang/Thread", null, null, thread));

        byte[] jobSupport = Lab.bytesOf(APP, JOB_SUPPORT);
        String said = stderrOf(() -> assertNull(transformer.transform(APP, JOB_SUPPORT, Object.class, null, jobSupport),
            "the tag is a field: it cannot be added to a class that is already loaded"));
        assertTrue(said.contains("kotlinx.coroutines.JobSupport was loaded before the agent"), said);
    }

    @Test
    void theSourceMapOfEveryApplicationClassIsRememberedEvenIfTheClassIsNotChanged() {
        // Any class may be in a stack, and a Kotlin one with inlined code in it.
        assertNull(transformer.transform(APP, "org/lib/a/Lib", null, null, classFile("org/lib/a/Lib", smap("org/lib/a/Lib"), 0)));
        assertEquals(2, framesAt36("org.lib.a.Lib"));
        assertNull(transformer.transform(APP, "com/acme/a/Project", null, null, classFile("com/acme/a/Project", smap("com/acme/a/Project"), 0)));
        assertEquals(2, framesAt36("com.acme.a.Project"));

        // Not for the JDK's and the agent's own classes: they hold no inlined Kotlin, and are loaded by the thousand.
        assertNull(transformer.transform(null, "org/lib/a/Boot", null, null, classFile("org/lib/a/Boot", smap("org/lib/a/Boot"), 0)));
        assertEquals(1, framesAt36("org.lib.a.Boot"));
        assertNull(transformer.transform(APP, "java/fake/Jdk", null, null, classFile("java/fake/Jdk", smap("java/fake/Jdk"), 0)));
        assertEquals(1, framesAt36("java.fake.Jdk"));

        // A class without a source map, or with something else in its place, says nothing and breaks nothing.
        assertNull(transformer.transform(APP, "org/lib/a/Plain", null, null, classFile("org/lib/a/Plain", null, 0)));
        assertNull(transformer.transform(APP, "org/lib/a/Odd", null, null, classFile("org/lib/a/Odd", "SMAP\nMain.kt\nKotlin\n*S Kotlin\n*F\n+ x y\n*L\nq#w:e\n", 0)));
        assertEquals(1, framesAt36("org.lib.a.Plain"));
        assertEquals(1, framesAt36("org.lib.a.Odd"));
    }

    @Test
    void theMainBehindSuspendFunMainIsOnTheLineOfTheDeclaration() {
        // The compiler's main(String[]), where the coroutine is created, has no line numbers at all.
        transformer.transform(APP, "org/lib/b/ServerKt", null, null, classFile("org/lib/b/ServerKt", null, 16));
        transformer.transform(APP, "com/acme/b/ServerKt", null, null, classFile("com/acme/b/ServerKt", null, 21));
        assertEquals(List.of("org.lib.b.ServerKt.main(Server.kt:16)"), RuntimeProbe.logicalFrames("org.lib.b.ServerKt", "main", "Server.kt", -1));
        assertEquals(List.of("com.acme.b.ServerKt.main(Server.kt:21)"), RuntimeProbe.logicalFrames("com.acme.b.ServerKt", "main", "Server.kt", -1));
        assertEquals(List.of("org.lib.b.ServerKt.main(Server.kt:40)"), RuntimeProbe.logicalFrames("org.lib.b.ServerKt", "main", "Server.kt", 40), "a frame that has a line keeps it");
    }

    // ------------------------------------------------------------------ kotlinx.coroutines of whatever version

    private static ClassLoader coroutinesOfVersion(String version) {
        return new ClassLoader(APP) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (!name.equals("META-INF/kotlinx_coroutines_core.version")) return super.getResourceAsStream(name);
                return version == null ? null : new ByteArrayInputStream(version.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    @Test
    void aVersionOfCoroutinesOutsideTheTestedRangeIsSaidAndInstrumentedAllTheSame() {
        byte[] jobSupport = Lab.bytesOf(APP, JOB_SUPPORT);
        int[] tested = HookTable.TESTED_COROUTINES;
        String lowest = tested[0] + "." + tested[1], highest = tested[2] + "." + tested[3];
        Object[][] cases = {
            {lowest + ".0", null},
            {highest + ".0\n", null},
            {highest + ".99-SNAPSHOT", null},
            {tested[0] + "." + (tested[1] - 1) + ".9", "outside the range"},
            {tested[2] + "." + (tested[3] + 1) + ".0", "outside the range"},
            {(tested[2] + 1) + ".0.0-RC", "outside the range"},
            {"banana", "Unrecognized"},
            {"1", "Unrecognized"},
            {null, "Cannot tell the version"},
        };
        for (Object[] testCase : cases) {
            String version = (String) testCase[0];
            String said = stderrOf(() -> assertNotNull(transformer.transform(coroutinesOfVersion(version), JOB_SUPPORT, null, null, jobSupport), "version " + version));
            if (testCase[1] == null) {
                assertEquals("", said, "version " + version + " is one the agent was tested with");
            } else {
                assertTrue(said.contains((String) testCase[1]), "version " + version + ": " + said);
                assertTrue(said.contains(lowest) && said.contains(highest), "the range that was tested is named: " + said);
            }
        }
    }

    @Test
    void aLibraryWhoseInternalsAreNotWhatTheTableExpectsIsReportedLoudlyNotSilentlyMistraced() throws Exception {
        // A kotlinx.coroutines of some other time: JobSupport is there, the methods the hooks go into are not.
        ClassWriter other = new ClassWriter(0);
        other.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, JOB_SUPPORT, null, "java/lang/Object", null);
        MethodVisitor constructor = other.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        other.visitEnd();

        byte[][] rewritten = new byte[1][];
        String said = stderrOf(() -> rewritten[0] = transformer.transform(coroutinesOfVersion("1.3.0"), JOB_SUPPORT, null, null, other.toByteArray()));
        assertTrue(said.contains("Unsupported version of kotlinx.coroutines.JobSupport"), said);
        for (String method : new String[] {"cancel", "parentCancelled", "notifyCancelling", "makeCompletingOnce", "completeStateFinalization"}) {
            assertTrue(said.contains(method), "every missing method is named, " + method + ": " + said);
        }
        // What can be done is done: the class still loads, and its jobs can still carry a node.
        Class<?> loaded = new Lab.Loader().with("kotlinx.coroutines.JobSupport", rewritten[0]).verified("kotlinx.coroutines.JobSupport");
        assertTrue(Tagged.class.isAssignableFrom(loaded));
        Tagged job = (Tagged) loaded.getConstructor().newInstance();
        job.coroutree$tag("its node");
        assertEquals("its node", job.coroutree$tag());
    }
}
