package kotlinx.coroutree.agent;

import kotlinx.coroutree.runtime.MonitorProbe;
import kotlinx.coroutree.runtime.Tracer;
import kotlinx.coroutree.runtime.Wire;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Gets the native monitor probe going. The agent jar carries one build of it per platform under
 * {@code kotlinx/coroutree/agent/native/<platform>/}; the Gradle plugin unpacks the right one and passes it as
 * {@code -agentpath:}, in which case there is nothing to do here but connect. Started by hand without
 * {@code -agentpath}, the agent unpacks and loads the library itself.
 */
final class MonitorProbeLoader {
    private MonitorProbeLoader() {}

    static final String RESOURCE_ROOT = "native/";

    static void start() {
        try {
            if (MonitorProbe.connect()) return;
            String resource = hostLibrary();
            InputStream library = resource == null ? null : MonitorProbeLoader.class.getResourceAsStream(RESOURCE_ROOT + resource);
            if (library == null) {
                Tracer.diagnostic(Wire.INFO, "This agent has no monitor probe for " + System.getProperty("os.name") + " "
                    + System.getProperty("os.arch") + ": threads blocked on 'synchronized' are not reported, everything else is.");
                return;
            }
            File file;
            try (library) {
                String name = resource.substring(resource.lastIndexOf('/') + 1);
                file = File.createTempFile("coroutree-", "-" + name);
                file.deleteOnExit();
                Files.copy(library, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            MonitorProbe.loadAndConnect(file.getAbsolutePath());
        } catch (IOException | RuntimeException | LinkageError e) {
            Tracer.diagnostic(Wire.WARNING, "Cannot load the monitor probe, threads blocked on 'synchronized' are not reported: " + e);
        }
    }

    /** {@code <platform>/<file name>} of the probe for the JVM this runs in, {@code null} for a platform nobody builds it for. */
    static String hostLibrary() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("mac") || os.contains("darwin")) return x64 || arm64 ? "macos/libcoroutree-monitor.dylib" : null; // a universal binary
        if (os.contains("linux")) return x64 ? "linux-x64/libcoroutree-monitor.so" : arm64 ? "linux-arm64/libcoroutree-monitor.so" : null;
        if (os.contains("windows")) return x64 ? "windows-x64/coroutree-monitor.dll" : null;
        return null;
    }
}
