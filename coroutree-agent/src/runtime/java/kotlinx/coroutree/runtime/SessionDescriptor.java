package kotlinx.coroutree.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * How a GUI finds this JVM: one file per traced JVM, {@code <sessions dir>/<pid>.json}, in a directory that is the
 * same for every JVM of the user ({@code ~/.coroutree/sessions} unless {@code sessions.dir} says otherwise). The
 * GUI watches that directory and offers whatever is in it: the JVMs that run, to connect to, and those that have
 * ended, as recorded traces. With a live socket the descriptor carries its port and token; without one it still
 * says where the trace is, and {@code "liveFailed": true} when there was to be one. It is rewritten with
 * {@code "ended": true} on orderly shutdown; after a crash it is stale and says so by its pid being gone.
 */
final class SessionDescriptor {
    private final File file;
    private final String fields;

    SessionDescriptor(AgentConfig config, File traceFile, long startedAtEpochMillis, LiveServer live) {
        long pid = ProcessHandle.current().pid();
        this.file = config.sessionsDir.isEmpty() ? null : new File(config.sessionsDir, pid + ".json");
        this.fields = "\"pid\":" + pid
            + (live == null ? "" : ",\"port\":" + live.port() + ",\"token\":" + json(live.token()))
            + (live == null && config.live ? ",\"liveFailed\":true" : "")
            + ",\"traceFile\":" + json(traceFile.getPath())
            + ",\"taskPath\":" + json(config.taskPath)
            + ",\"buildId\":" + json(config.buildId)
            + ",\"command\":" + json(System.getProperty("sun.java.command", ""))
            + ",\"startedAt\":" + startedAtEpochMillis
            + ",\"paceable\":" + (live != null && config.hasGate());
    }

    void write(boolean ended) {
        if (file == null) return;
        try {
            file.getParentFile().mkdirs();
            // Written aside and moved into place: a reader polling the directory never sees half a file.
            File temp = new File(file.getPath() + ".tmp");
            Files.write(temp.toPath(), ("{" + fields + ",\"ended\":" + ended + "}\n").getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Tracer.reportInternalError("can't write the session descriptor " + file, e);
        }
    }

    private static String json(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }
}
