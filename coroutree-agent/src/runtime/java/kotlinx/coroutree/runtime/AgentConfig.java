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
 * Agent options. They come either inline, {@code -javaagent:agent.jar=key=value,key=value}, or, the way the
 * Gradle plugin passes them, from a properties file: {@code -javaagent:agent.jar=config=/path/agent.properties}.
 * Inline options override the file; to combine the two, put {@code config=} anywhere but first.
 */
public final class AgentConfig {
    /** Directory for the trace file; the agent names the file itself because only it knows the pid. */
    public final String traceDir;
    /** Exact trace file; overrides {@link #traceDir}. */
    public final String traceFile;
    /** Directory for the live session descriptor. Empty: no descriptor is written. */
    public final String sessionsDir;
    public final boolean live;
    /** Report threads blocked on {@code synchronized}, which takes a native library (see MonitorProbe). */
    public final boolean monitor;
    /**
     * Execution control (DESIGN §3.1). {@code pace=false}: no gate in this JVM at all. {@code pace.paused}: held at the
     * first event until a GUI resumes or steps; needs {@link #live}. {@code pace.events.per.second}: the pace the run
     * starts with and falls back to, per sequence, a decimal number or {@code unlimited}; here as the interval it means.
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
    /** Problems found while reading the options, to be reported once tracing is up. */
    public final List<String> problems = new ArrayList<>();

    private AgentConfig(Properties p) {
        traceDir = p.getProperty("trace.dir", "");
        traceFile = p.getProperty("trace.file", "");
        sessionsDir = p.getProperty("sessions.dir", "");
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
            problems.add("Ignored pace.paused: without the live socket (live=false) nothing could ever resume the program, so it runs");
            paused = false;
        }
        pacePaused = paused;
    }

    /**
     * Whether this JVM has a gate. Without the live socket nobody can ever send a command, and then there is one only
     * for a pace that was configured.
     */
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
        problems.add("Ignored pace.events.per.second=" + eventsPerSecond + ", expected a positive number or 'unlimited': the program runs unpaced");
        return 0;
    }

    public static AgentConfig parse(String agentArgs) {
        Properties inline = new Properties();
        List<String> problems = new ArrayList<>();
        if (agentArgs != null && agentArgs.startsWith("config=")) {
            // The form the Gradle plugin uses: nothing but the file. Taken whole, because a path may contain a comma.
            inline.setProperty("config", agentArgs.substring("config=".length()));
        } else if (agentArgs != null && !agentArgs.isEmpty()) {
            for (String option : agentArgs.split(",")) {
                int eq = option.indexOf('=');
                if (eq <= 0) {
                    problems.add("Ignored malformed agent option '" + option + "', expected key=value");
                    continue;
                }
                String key = option.substring(0, eq).trim();
                String value = option.substring(eq + 1).trim();
                // include/exclude are lists themselves; inline they are separated with ';' or ':'
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
                problems.add("Cannot read agent config " + file + ": " + e);
            }
        }
        merged.putAll(inline);
        AgentConfig config = new AgentConfig(merged);
        config.problems.addAll(0, problems);
        return config;
    }

    /** Where the trace goes: the explicit file, or a generated name in the trace directory (the working directory by default). */
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

    /** Whether project classes are told apart from libraries at all. Without it, instrumenting "project code" would mean everything. */
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
        problems.add("Ignored " + key + "=" + value + ", expected a positive integer");
        return fallback;
    }

    private List<SourceIndexFile.Module> readSourceIndex(String path) {
        if (path.isEmpty()) return List.of();
        try {
            return SourceIndexFile.read(new File(path));
        } catch (IOException e) {
            problems.add("Cannot read source index " + path + ": " + e);
            return List.of();
        }
    }
}
