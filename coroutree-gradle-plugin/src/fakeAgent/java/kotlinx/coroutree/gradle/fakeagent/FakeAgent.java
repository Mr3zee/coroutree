package kotlinx.coroutree.gradle.fakeagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Stands in for the real agent in the plugin's functional tests: proves that a JVM was started with it by leaving
 * a line with its arguments in {@code attached.txt} next to the configuration file it was given.
 */
public final class FakeAgent {
    private FakeAgent() {
    }

    public static void premain(String args) throws IOException {
        if (args == null || !args.startsWith("config=")) throw new IllegalArgumentException("Unexpected agent arguments: " + args);
        Path config = Path.of(args.substring("config=".length()));
        if (!Files.isRegularFile(config)) throw new IllegalStateException("No configuration file at " + config);
        Files.writeString(
            config.resolveSibling("attached.txt"),
            args + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }
}
