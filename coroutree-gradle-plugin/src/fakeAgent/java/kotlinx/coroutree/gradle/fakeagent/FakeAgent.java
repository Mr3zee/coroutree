package kotlinx.coroutree.gradle.fakeagent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * Stands in for the real agent in the plugin's functional tests: proves that a JVM was started with it by leaving
 * a line with its arguments in {@code attached.txt} next to the configuration file it was given.
 *
 * Like the real one it reads the configuration while the JVM starts, and refuses to start on one that is not whole:
 * a Test task rewrites the files for every fork while earlier forks are starting.
 */
public final class FakeAgent {
    private FakeAgent() {
    }

    public static void premain(String args) throws IOException {
        if (args == null || !args.startsWith("config=")) throw new IllegalArgumentException("Unexpected agent arguments: " + args);
        Path config = Path.of(args.substring("config=".length()));
        if (!Files.isRegularFile(config)) throw new IllegalStateException("No configuration file at " + config);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String index = properties.getProperty("source.index");
        if (properties.getProperty("build.id") == null || properties.getProperty("pace.events.per.second") == null
            || index == null || !Files.isRegularFile(Path.of(index))) {
            throw new IllegalStateException("Incomplete configuration at " + config + ": " + properties);
        }
        Files.writeString(
            config.resolveSibling("attached.txt"),
            args + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }
}
