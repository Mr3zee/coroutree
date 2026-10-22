package kotlinx.coroutree.agent;

import kotlinx.coroutree.runtime.AgentConfig;
import kotlinx.coroutree.runtime.SourceMaps;
import kotlinx.coroutree.runtime.Tracer;
import kotlinx.coroutree.runtime.Wire;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.List;

final class CoroutreeTransformer implements ClassFileTransformer {
    private final HookTable table;
    private final AgentConfig config;

    CoroutreeTransformer(HookTable table, AgentConfig config) {
        this.table = table;
        this.config = config;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain domain, byte[] bytes) {
        if (className == null) return null;
        ClassPatch patch = table.get(className);
        // The bootstrap loader (null) has no project classes.
        boolean project = loader != null && config.isProjectClassInternalName(className);
        try {
            if (patch == null && !project) {
                // Unmodified classes can still contribute inline frames to captured stacks.
                if (loader != null && !isJdkOrAgent(className)) readSourceMap(className, bytes);
                return null;
            }
            if (patch != null && patch.changesShape() && classBeingRedefined != null) {
                Tracer.diagnostic(Wire.ERROR, className.replace('/', '.') + " was loaded before the agent, so coroutines aren't traced.");
                return null;
            }
            return rewrite(loader, className, patch, project, bytes);
        } catch (Throwable e) {
            Tracer.diagnostic(Wire.ERROR, "can't instrument " + className.replace('/', '.') + ": " + e);
            return null;
        }
    }

    /** Null if there is nothing to change. */
    byte[] rewrite(ClassLoader loader, String className, ClassPatch patch, boolean project, byte[] bytes) {
        ClassNode node = new ClassNode();
        // Frames are expanded because MethodPatch.around adds one, and a method's frames must all be of one kind.
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        if (patch != null) {
            List<String> missing = patch.apply(node);
            changed = true;
            if (className.equals(HookTable.JOB_SUPPORT)) checkCoroutinesVersion(loader);
            if (!missing.isEmpty()) {
                Tracer.diagnostic(Wire.ERROR, "Unsupported version of " + className.replace('/', '.') + ": no " + String.join(", ", missing)
                    + ". The trace is incomplete or wrong.");
            }
        }
        // Read source metadata before instrumentation changes the class.
        readSourceMap(node);
        if (project) changed |= CatchPatch.apply(node);
        if (!changed) return null;
        // COMPUTE_FRAMES would load classes. The patches add no branches, so the original frames stay valid.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static final String SUSPEND_MAIN_TAIL = "Lkotlin/coroutines/Continuation;)Ljava/lang/Object;";

    static void readSourceMap(ClassNode node) {
        String className = node.name.replace('/', '.');
        if (node.sourceDebug != null) SourceMaps.register(className, node.sourceDebug, InlinedCalls.of(node));
        for (MethodNode method : node.methods) {
            if (!method.name.equals("main") || !method.desc.endsWith(SUSPEND_MAIN_TAIL)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LineNumberNode line) {
                    SourceMaps.registerSuspendMain(className, line.line);
                    break;
                }
            }
        }
    }

    /**
     * Skips method code except for suspend main line numbers. Intermediate inline calls need the full tree overload.
     */
    static void readSourceMap(String className, byte[] bytes) {
        String name = className.replace('/', '.');
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitSource(String source, String debug) {
                if (debug != null) SourceMaps.register(name, debug, null);
            }

            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                if (!methodName.equals("main") || !descriptor.endsWith(SUSPEND_MAIN_TAIL)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    private boolean seen;

                    @Override
                    public void visitLineNumber(int line, Label start) {
                        if (!seen) SourceMaps.registerSuspendMain(name, line);
                        seen = true;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
    }

    private static boolean isJdkOrAgent(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/") || internalName.startsWith("jdk/")
            || internalName.startsWith("sun/") || internalName.startsWith("com/sun/") || internalName.startsWith("kotlinx/coroutree/");
    }

    private static void checkCoroutinesVersion(ClassLoader loader) {
        String version = null;
        try (InputStream in = loader == null ? null : loader.getResourceAsStream("META-INF/kotlinx_coroutines_core.version")) {
            if (in != null) version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
        }
        int[] tested = HookTable.TESTED_COROUTINES;
        String range = tested[0] + "." + tested[1] + " to " + tested[2] + "." + tested[3];
        if (version == null) {
            Tracer.diagnostic(Wire.WARNING, "Can't find the version of kotlinx.coroutines. This agent was tested with " + range + ".");
            return;
        }
        String[] parts = version.split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            boolean below = major < tested[0] || major == tested[0] && minor < tested[1];
            boolean above = major > tested[2] || major == tested[2] && minor > tested[3];
            if (below || above) {
                Tracer.diagnostic(Wire.WARNING, "kotlinx.coroutines " + version + " is outside the tested range " + range
                    + ". The agent reports a known mismatch as an error, but it can't find every mismatch.");
            }
        } catch (RuntimeException e) {
            Tracer.diagnostic(Wire.WARNING, "Unrecognized kotlinx.coroutines version '" + version + "'. This agent was tested with " + range + ".");
        }
    }
}
