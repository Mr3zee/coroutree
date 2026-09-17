package kotlinx.coroutree.agent;

import kotlinx.coroutree.runtime.AgentConfig;
import kotlinx.coroutree.runtime.Tracer;
import kotlinx.coroutree.runtime.Wire;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Wires the pieces together once the runtime is reachable. See {@link Premain} for why this is a separate class. */
final class Agent {
    private Agent() {}

    static void start(String args, Instrumentation instrumentation) throws IOException {
        AgentConfig config = AgentConfig.parse(args);
        HookTable table = new HookTable();
        CoroutreeTransformer transformer = new CoroutreeTransformer(table, config);

        // A transformer that loads classes while a class is being loaded invites circularity errors. Run it once
        // outside of class loading so that ASM and everything else it needs is loaded and linked before it is installed.
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
            if (in == null) return;
            byte[] bytes = in.readAllBytes();
            transformer.rewrite(null, sample, table.get(sample), false, bytes);
            transformer.rewrite(null, sample, null, true, bytes);
        }
    }

    /** The JDK classes in the table were loaded long before the agent; kotlin and kotlinx classes normally were not. */
    private static void retransformLoaded(Instrumentation instrumentation, HookTable table) {
        Set<String> wanted = new HashSet<>();
        for (String name : table.classNames()) wanted.add(name.replace('/', '.'));
        List<Class<?>> loaded = new ArrayList<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (wanted.contains(type.getName()) && instrumentation.isModifiableClass(type)) loaded.add(type);
        }
        // One by one: a class the JVM refuses to retransform should cost its own hooks, not everybody's.
        for (Class<?> type : loaded) {
            try {
                instrumentation.retransformClasses(type);
            } catch (Throwable e) {
                Tracer.diagnostic(Wire.WARNING, "cannot instrument " + type.getName() + ", events from it will be missing: " + e);
            }
        }
    }
}
