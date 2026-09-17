package kotlinx.coroutree.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

/**
 * {@code -javaagent} entry point.
 *
 * Does one thing before anything else: puts the runtime — the classes that instrumented code calls — on the bootstrap
 * class path, where instrumented JDK classes can see it. The runtime travels as a jar nested in the agent jar. Only
 * then is {@link Agent} touched, because loading it links against the runtime.
 */
public final class Premain {
    private Premain() {}

    private static final String RUNTIME_JAR = "coroutree-runtime.jar";

    public static void premain(String args, Instrumentation instrumentation) {
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(extractRuntime()));
            Agent.start(args, instrumentation);
        } catch (Throwable e) {
            // The application must run with or without us.
            System.err.println("coroutree: agent failed to start, the program runs untraced: " + e);
            if (Boolean.getBoolean("coroutree.debug")) e.printStackTrace();
        }
    }

    private static File extractRuntime() throws IOException {
        File file = File.createTempFile("coroutree-runtime", ".jar");
        file.deleteOnExit();
        try (InputStream in = Premain.class.getResourceAsStream(RUNTIME_JAR)) {
            if (in == null) throw new IOException(RUNTIME_JAR + " is missing from the agent jar");
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }
}
