package kotlinx.coroutree.agent;

import kotlinx.coroutree.runtime.AgentConfig;
import kotlinx.coroutree.runtime.Tracer;
import kotlinx.coroutree.runtime.Wire;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Must load after {@link Premain} makes the runtime available on the bootstrap classpath. */
final class Agent {
    private Agent() {}

    static void start(String args, Instrumentation instrumentation) throws IOException {
        AgentConfig config = AgentConfig.parse(args);
        HookTable table = new HookTable();
        CoroutreeTransformer transformer = new CoroutreeTransformer(table, config);

        // Load and link transformer dependencies before installation to avoid circular class loading during transformation.
        warmUp(transformer, table);

        Tracer.start(config);
        instrumentation.addTransformer(transformer, true);
        retransformLoaded(instrumentation, table);
        if (config.monitor) MonitorProbeLoader.start();
        Tracer.activate();
    }

    private static void warmUp(CoroutreeTransformer transformer, HookTable table) throws IOException {
        String sample = "java/lang/Thread";
        try (InputStream in = ClassLoader.getSystemResourceAsStream(sample + ".class")) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                transformer.rewrite(null, sample, table.get(sample), false, bytes);
                transformer.rewrite(null, sample, null, true, bytes);
                CoroutreeTransformer.readSourceMap(sample, bytes);
            }
        }
        // JDK classes don't exercise inline source maps or suspend main detection.
        // This synthetic class exercises both readers and registers no usable source locations.
        ClassNode inlined = new ClassNode();
        inlined.version = Opcodes.V17;
        inlined.name = "kotlinx/coroutree/agent/WarmUp";
        inlined.superName = "java/lang/Object";
        String[][] methods = {{"warmUp", "()V", "1"}, {"main", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "0"}};
        for (String[] signature : methods) {
            MethodNode method = new MethodNode(Opcodes.ACC_STATIC, signature[0], signature[1], null, null);
            LabelNode start = new LabelNode(), end = new LabelNode();
            method.instructions.add(start);
            method.instructions.add(new LineNumberNode(Integer.parseInt(signature[2]), start));
            method.instructions.add(new InsnNode(Opcodes.NOP));
            method.instructions.add(end);
            method.localVariables.add(new LocalVariableNode("$i$f$warmUp", "I", null, start, end, 0));
            inlined.methods.add(method);
        }
        String file = "+ 1 WarmUp.kt\n" + inlined.name + "\n*L\n1#1:1\n";
        inlined.sourceDebug = "SMAP\nWarmUp.kt\nKotlin\n*S Kotlin\n*F\n" + file + "*S KotlinDebug\n*F\n" + file + "*E\n";
        CoroutreeTransformer.readSourceMap(inlined);
        ClassWriter writer = new ClassWriter(0);
        inlined.accept(writer);
        CoroutreeTransformer.readSourceMap(inlined.name, writer.toByteArray());
    }

    private static void retransformLoaded(Instrumentation instrumentation, HookTable table) {
        Set<String> wanted = new HashSet<>();
        for (String name : table.classNames()) wanted.add(name.replace('/', '.'));
        List<Class<?>> loaded = new ArrayList<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (wanted.contains(type.getName()) && instrumentation.isModifiableClass(type)) loaded.add(type);
        }
        // Retransform separately so one failure doesn't prevent instrumentation of other classes.
        for (Class<?> type : loaded) {
            try {
                instrumentation.retransformClasses(type);
            } catch (Throwable e) {
                Tracer.diagnostic(Wire.WARNING, "can't instrument " + type.getName() + ", so its events are missing: " + e);
            }
        }
    }
}
