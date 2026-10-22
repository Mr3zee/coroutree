package kotlinx.coroutree.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Inline options use {@code -javaagent:agent.jar=key=value,key=value}.
 * A properties file uses {@code -javaagent:agent.jar=config=/path/agent.properties}.
 * Inline options override the file. To combine them, put {@code config=} after another option.
 */
public final class AgentConfig {
    public final String traceDir;
    /** Overrides {@link #traceDir}. */
    public final String traceFile;
    /**
     * Defaults to the shared user registry at {@code ~/.coroutree/sessions}. Empty disables session descriptors.
     */
    public final String sessionsDir;
    public final boolean live;
    /** Requires the native library loaded by MonitorProbe. */
    public final boolean monitor;
    /**
     * Execution-control options (DESIGN §3.1):
     * <ul>
     * <li>{@code pace=false} removes the gate.</li>
     * <li>{@code pace.paused} holds the first event until a GUI resumes or steps. Requires {@link #live}.</li>
     * <li>{@code pace.events.per.second} sets the initial and fallback pace for each sequence, stored here as nanoseconds between steps.</li>
     * </ul>
     */
    public final boolean pace;
    public final boolean pacePaused;
    public final long paceIntervalNanos;
    public final String buildId;
    public final String taskPath;
    public final String projectDir;
    public final List<String> includePackages;
    public final List<String> excludePackages;
    public final int stackDepth;
    public final List<SourceIndexFile.Module> sourceIndex;
    /** Report after tracing starts. */
    public final List<String> problems = new ArrayList<>();

    private AgentConfig(Properties p) {
        traceDir = p.getProperty("trace.dir", "");
        traceFile = p.getProperty("trace.file", "");
        sessionsDir = p.getProperty("sessions.dir", defaultSessionsDir());
        live = Boolean.parseBoolean(p.getProperty("live", "true"));
        monitor = Boolean.parseBoolean(p.getProperty("monitor", "true"));
        buildId = p.getProperty("build.id", "");
        taskPath = p.getProperty("task.path", "");
        projectDir = p.getProperty("project.dir", "");
        includePackages = prefixes(p.getProperty("include", ""));
        excludePackages = prefixes(p.getProperty("exclude", ""));
        stackDepth = positiveInt(p, "stack.depth", 32);
        sourceIndex = readSourceIndex(p.getProperty("source.index", ""));
        pace = Boolean.parseBoolean(p.getProperty("pace", "true"));
        paceIntervalNanos = pace ? intervalOf(p.getProperty("pace.events.per.second")) : 0;
        boolean paused = pace && Boolean.parseBoolean(p.getProperty("pace.paused", "false"));
        if (paused && !live) {
            problems.add("Ignored pace.paused: with live=false no GUI can resume the program, so it runs.");
            paused = false;
        }
        pacePaused = paused;
    }

    /** Must match SessionRegistry.defaultDir in the GUI. */
    public static String defaultSessionsDir() {
        return new File(System.getProperty("user.home", "."), ".coroutree/sessions").getPath();
    }

    public boolean hasGate() {
        return pace && (live || paceIntervalNanos > 0);
    }

    private long intervalOf(String eventsPerSecond) {
        if (eventsPerSecond == null) return 0;
        String value = eventsPerSecond.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("unlimited")) return 0;
        try {
            double perSecond = Double.parseDouble(value);
            if (perSecond > 0 && !Double.isInfinite(perSecond)) {
                double interval = 1e9 / perSecond;
                return interval >= Pace.MAX_INTERVAL_NANOS ? Pace.MAX_INTERVAL_NANOS : Math.max(1, Math.round(interval));
            }
        } catch (NumberFormatException ignored) {
        }
        problems.add("Ignored pace.events.per.second=" + eventsPerSecond + ": expected a positive number or 'unlimited'. The program runs unpaced.");
        return 0;
    }

    public static AgentConfig parse(String agentArgs) {
        Properties inline = new Properties();
        List<String> problems = new ArrayList<>();
        if (agentArgs != null && agentArgs.startsWith("config=")) {
            // A leading config= consumes the whole argument because file paths can contain commas.
            inline.setProperty("config", agentArgs.substring("config=".length()));
        } else if (agentArgs != null && !agentArgs.isEmpty()) {
            for (String option : agentArgs.split(",")) {
                int eq = option.indexOf('=');
                if (eq <= 0) {
                    problems.add("Ignored agent option '" + option + "': expected key=value.");
                    continue;
                }
                String key = option.substring(0, eq).trim();
                String value = option.substring(eq + 1).trim();
                // Inline include/exclude lists use semicolons or colons because commas separate options.
                if (key.equals("include") || key.equals("exclude")) value = value.replace(';', ',').replace(':', ',');
                inline.setProperty(key, value);
            }
        }
        Properties merged = new Properties();
        String file = inline.getProperty("config");
        if (file != null) {
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                merged.load(reader);
            } catch (IOException e) {
                problems.add("Can't read the agent config " + file + ": " + e);
            }
        }
        merged.putAll(inline);
        AgentConfig config = new AgentConfig(merged);
        config.problems.addAll(0, problems);
        return config;
    }

    public File resolveTraceFile(long pid) {
        if (!traceFile.isEmpty()) return new File(traceFile).getAbsoluteFile();
        String label = taskPath.isEmpty() ? "trace" : taskPath.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (label.isEmpty()) label = "trace";
        return new File(traceDir.isEmpty() ? "." : traceDir, label + "-" + pid + ".ctrace").getAbsoluteFile();
    }

    boolean isProjectClass(String className) {
        for (String prefix : excludePackages) if (matches(className, prefix)) return false;
        if (includePackages.isEmpty()) return true;
        for (String prefix : includePackages) if (matches(className, prefix)) return true;
        return false;
    }

    public boolean hasProjectPackages() {
        return !includePackages.isEmpty();
    }

    public boolean isProjectClassInternalName(String internalName) {
        return hasProjectPackages() && isProjectClass(internalName.replace('/', '.'));
    }

    private static boolean matches(String className, String prefix) {
        return className.startsWith(prefix) && (className.length() == prefix.length() || className.charAt(prefix.length()) == '.');
    }

    private static List<String> prefixes(String value) {
        List<String> result = new ArrayList<>();
        for (String prefix : value.split(",")) {
            prefix = prefix.trim();
            while (prefix.endsWith(".") || prefix.endsWith("*")) prefix = prefix.substring(0, prefix.length() - 1);
            if (!prefix.isEmpty()) result.add(prefix);
        }
        return result;
    }

    private int positiveInt(Properties p, String key, int fallback) {
        String value = p.getProperty(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
        }
        problems.add("Ignored " + key + "=" + value + ": expected a positive integer.");
        return fallback;
    }

    private List<SourceIndexFile.Module> readSourceIndex(String path) {
        if (path.isEmpty()) return List.of();
        try {
            return SourceIndexFile.read(new File(path));
        } catch (IOException e) {
            problems.add("Can't read the source index " + path + ": " + e);
            return List.of();
        }
    }
}
