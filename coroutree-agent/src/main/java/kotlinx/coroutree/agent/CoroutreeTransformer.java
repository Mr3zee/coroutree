package kotlinx.coroutree.agent;

import kotlinx.coroutree.runtime.AgentConfig;
import kotlinx.coroutree.runtime.Tracer;
import kotlinx.coroutree.runtime.Wire;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

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
        // Project classes come from an application class loader; the bootstrap loader (null) never holds them.
        boolean project = loader != null && config.isProjectClassInternalName(className);
        if (patch == null && !project) return null;
        try {
            if (patch != null && patch.changesShape() && classBeingRedefined != null) {
                // Cannot add a field to a loaded class. Only happens if the agent is attached to a running JVM.
                Tracer.diagnostic(Wire.ERROR, className.replace('/', '.') + " was loaded before the agent; coroutines will not be traced");
                return null;
            }
            return rewrite(loader, className, patch, project, bytes);
        } catch (Throwable e) {
            Tracer.diagnostic(Wire.ERROR, "cannot instrument " + className.replace('/', '.') + ": " + e);
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
                    + ". The trace will be incomplete or wrong.");
            }
        }
        if (project) changed |= CatchPatch.apply(node);
        if (!changed) return null;
        // Only max stack and locals are recomputed; see MethodPatch for why frames are not.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void checkCoroutinesVersion(ClassLoader loader) {
        String version = null;
        try (InputStream in = loader == null ? null : loader.getResourceAsStream("META-INF/kotlinx_coroutines_core.version")) {
            if (in != null) version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
        }
        int[] tested = HookTable.TESTED_COROUTINES;
        String range = tested[0] + "." + tested[1] + " – " + tested[2] + "." + tested[3];
        if (version == null) {
            Tracer.diagnostic(Wire.WARNING, "Cannot tell the version of kotlinx.coroutines; this agent was tested with " + range);
            return;
        }
        String[] parts = version.split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            boolean below = major < tested[0] || major == tested[0] && minor < tested[1];
            boolean above = major > tested[2] || major == tested[2] && minor > tested[3];
            if (below || above) {
                Tracer.diagnostic(Wire.WARNING, "kotlinx.coroutines " + version + " is outside the range this agent was tested with (" + range
                    + "). It instruments library internals; a mismatch is reported as an error, the absence of one is not a guarantee.");
            }
        } catch (RuntimeException e) {
            Tracer.diagnostic(Wire.WARNING, "Unrecognized kotlinx.coroutines version '" + version + "'; this agent was tested with " + range);
        }
    }
}
