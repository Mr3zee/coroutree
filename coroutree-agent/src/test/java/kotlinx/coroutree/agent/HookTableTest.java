package kotlinx.coroutree.agent;

import kotlinx.coroutree.agent.MethodPatch.HookCall;
import kotlinx.coroutree.runtime.Hooks;
import kotlinx.coroutree.runtime.Tagged;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hook table is the agent's whole coupling to the internals of the JDK, of the Kotlin standard library and of
 * kotlinx.coroutines. Here it is held against the real things: the {@code Hooks} class it calls into, the JDK the
 * tests run on, and the kotlinx.coroutines the project is built with.
 */
class HookTableTest {
    private static final ClassLoader APP = HookTableTest.class.getClassLoader();

    private final HookTable table = new HookTable();
    private final CoroutreeTransformer transformer = Lab.transformer("");

    private static boolean isJdk(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("sun/") || internalName.startsWith("jdk/");
    }

    private List<String> classes(boolean jdk) {
        List<String> names = new ArrayList<>();
        for (String name : table.classNames()) if (isJdk(name) == jdk) names.add(name);
        names.sort(null);
        return names;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    /** Every hook call the table can weave in, by reading the table itself: optional entries too, whatever this JDK has. */
    private List<String> hookCallsOfTheTable() throws ReflectiveOperationException {
        Field methods = ClassPatch.class.getDeclaredField("methods");
        Field onEnter = MethodPatch.class.getDeclaredField("onEnter"), onExit = MethodPatch.class.getDeclaredField("onExit");
        Field hook = HookCall.class.getDeclaredField("hook"), descriptor = HookCall.class.getDeclaredField("hookDescriptor");
        for (Field field : new Field[] {methods, onEnter, onExit, hook, descriptor}) field.setAccessible(true);
        List<String> calls = new ArrayList<>();
        for (String className : table.classNames()) {
            for (Object patch : (List<?>) methods.get(table.get(className))) {
                for (Field side : new Field[] {onEnter, onExit}) {
                    Object call = side.get(patch);
                    if (call != null) calls.add(hook.get(call) + (String) descriptor.get(call));
                }
            }
        }
        return calls;
    }

    @Test
    void everyEntryCallsAHookThatExistsWithThatSignatureAndEveryHookHasAnEntry() throws Exception {
        Set<String> hooks = new TreeSet<>();
        for (Method method : Hooks.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())) hooks.add(method.getName() + Type.getMethodDescriptor(method));
        }
        Set<String> called = new TreeSet<>(hookCallsOfTheTable());
        assertFalse(called.isEmpty());
        Set<String> unknown = new TreeSet<>(called);
        unknown.removeAll(hooks);
        assertEquals(Set.of(), unknown, "the table calls hooks that Hooks does not have: a NoSuchMethodError inside Thread.start or JobSupport");

        // New hook = one entry in HookTable + one method in Hooks. The one hook that has no entry is the one CatchPatch
        // puts into every catch block of the project's classes.
        Set<String> uncalled = new TreeSet<>(hooks);
        uncalled.removeAll(called);
        assertEquals(Set.of("exceptionCaught(Ljava/lang/Throwable;)V"), uncalled);
    }

    @Test
    void noClassOfTheJdkChangesShape() {
        // They are retransformed, and a retransformed class can get neither a field nor a method. Only the job's tag
        // changes a shape, and kotlinx.coroutines is patched while it is being loaded.
        List<String> shaped = new ArrayList<>();
        for (String name : table.classNames()) if (table.get(name).changesShape()) shaped.add(name);
        assertEquals(List.of(HookTable.JOB_SUPPORT), shaped);
        assertFalse(classes(true).isEmpty());
        assertFalse(classes(false).isEmpty());
    }

    @Test
    void theJdkTheTestsRunOnHasEveryRequiredMethodAndItsRewrittenClassesAreSound() throws Exception {
        for (String name : classes(true)) {
            byte[] original = Lab.bytesOf(null, name);
            assertEquals(List.of(), table.get(name).apply(read(original)), "required methods missing in " + name + " of JDK " + Runtime.version());

            byte[] rewritten = transformer.rewrite(null, name, table.get(name), false, original);
            assertNotNull(rewritten, name);
            ClassNode node = read(rewritten);
            assertEquals(read(original).fields.size(), node.fields.size(), name);
            assertEquals(read(original).methods.size(), node.methods.size(), name);
            int hooked = 0;
            for (MethodNode method : node.methods) {
                boolean callsHooks = false;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode made && made.owner.equals(MethodPatch.HOOKS)) callsHooks = true;
                }
                if (!callsHooks) continue;
                hooked++;
                // These classes cannot be loaded a second time to let the JVM verify them. What the inserted code does
                // to the operand stack and the local variables can still be checked, instruction by instruction.
                try {
                    new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
                } catch (AnalyzerException e) {
                    throw new AssertionError(name + "." + method.name + method.desc + " is not sound after rewriting", e);
                }
            }
            assertTrue(hooked > 0, "nothing was hooked in " + name);
        }
    }

    /**
     * The Kotlin standard library and kotlinx.coroutines as the agent would leave them: every class of the table is
     * rewritten on its way into a class loader of its own, and the JVM verifies it there against its old frames.
     */
    private final class RewrittenLibraries extends ClassLoader {
        final Set<String> rewritten = new HashSet<>();

        RewrittenLibraries() {
            super(APP);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!name.startsWith("kotlin.") && !name.startsWith("kotlinx.coroutines.")) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                String internalName = name.replace('.', '/');
                byte[] bytes;
                try (InputStream in = APP.getResourceAsStream(internalName + ".class")) {
                    if (in == null) throw new ClassNotFoundException(name);
                    bytes = in.readAllBytes();
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
                ClassPatch patch = table.get(internalName);
                if (patch != null) {
                    bytes = transformer.rewrite(this, internalName, patch, false, bytes);
                    rewritten.add(internalName);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
    }

    @Test
    void theCoroutinesTheProjectIsBuiltWithHaveEveryRequiredMethodAndVerifyAfterRewriting() throws Exception {
        RewrittenLibraries libraries = new RewrittenLibraries();
        for (String name : classes(false)) {
            assertEquals(List.of(), table.get(name).apply(read(Lab.bytesOf(APP, name))), "required methods missing in " + name);
            // Linked and initialised: a class whose inserted code or hand-written frame the verifier rejects fails here.
            Class<?> loaded = Class.forName(name.replace('/', '.'), true, libraries);
            assertTrue(loaded.getClassLoader() == libraries && libraries.rewritten.contains(name), name);
        }

        // And they still work. A job of the rewritten library: created, cancelled and completed through the hooked
        // methods (tracing is off, the hooks return at their first check), carrying the tag the runtime hangs its node on.
        Class<?> jobType = libraries.loadClass("kotlinx.coroutines.Job");
        Object job = libraries.loadClass("kotlinx.coroutines.JobKt").getMethod("Job", jobType).invoke(null, (Object) null);
        assertTrue(job instanceof Tagged, "JobSupport implements Tagged once the agent has seen it");
        Tagged tagged = (Tagged) job;
        tagged.coroutree$tag("the node");
        assertEquals("the node", tagged.coroutree$tag());
        Object child = libraries.loadClass("kotlinx.coroutines.JobKt").getMethod("Job", jobType).invoke(null, job);
        jobType.getMethod("cancel", CancellationException.class).invoke(job, (Object) null);
        assertEquals(true, jobType.getMethod("isCancelled").invoke(job));
        assertEquals(true, jobType.getMethod("isCancelled").invoke(child), "the parent took its child along");
        assertEquals(true, jobType.getMethod("isCompleted").invoke(job));
    }

    @Test
    void theTestedRangeOfCoroutinesEndsAtTheVersionTheProjectIsBuiltWith() throws Exception {
        String version;
        try (InputStream in = APP.getResourceAsStream("META-INF/kotlinx_coroutines_core.version")) {
            assertNotNull(in, "kotlinx-coroutines-core says its version, which is what the agent reads at run time");
            version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        String[] parts = version.split("[.-]");
        int[] tested = HookTable.TESTED_COROUTINES;
        assertEquals(4, tested.length);
        assertTrue(tested[0] < tested[2] || tested[0] == tested[2] && tested[1] <= tested[3], "from … to");
        assertEquals(parts[0] + "." + parts[1], tested[2] + "." + tested[3],
            "the corpus runs with kotlinx.coroutines " + version + " (the version catalog): the range the agent claims is extended together with it, never alone");
    }
}
