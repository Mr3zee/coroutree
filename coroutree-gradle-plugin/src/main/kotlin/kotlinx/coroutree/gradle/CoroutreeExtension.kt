package kotlinx.coroutree.gradle

import org.gradle.api.Action
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/**
 * ```
 * coroutree {
 *     enabled = providers.gradleProperty("coroutree").isPresent   // this is the default
 *     includePackages("com.acme")
 *     excludePackages("com.acme.generated")
 *     live { enabled = true }
 *     pace {                       // execution control: slowing down, pausing and stepping the program from the GUI
 *         enabled = true           // false: the JVM has no gate at all
 *         startPaused = false      // true: held at the first event until a GUI resumes or steps; needs live
 *         eventsPerSecond = 0.2    // the pace the run starts with, per sequence; unset = unlimited
 *     }
 *     stackDepth = 32
 * }
 * ```
 *
 * What one wants to change for a single run is a Gradle property, and **the command line wins over this block**, in
 * both directions: `-Pcoroutree` (`enabled`), `-Pcoroutree.live`, `-Pcoroutree.pace`, `-Pcoroutree.pace.startPaused`
 * (each `true`, also bare, or `false`) and `-Pcoroutree.pace.eventsPerSecond` (a number, or `unlimited`).
 */
public abstract class CoroutreeExtension {
    /**
     * Attach the agent to the `JavaExec` and `Test` tasks of this project. Off by default; `-Pcoroutree` on the command
     * line switches it on and `-Pcoroutree=false` off, whatever is set here.
     */
    public abstract val enabled: Property<Boolean>

    /**
     * Package prefixes that count as project code; everything else is tagged as library.
     * Left empty, the packages of the project's own sources (and of the projects it depends on) are used.
     */
    public abstract val includedPackages: ListProperty<String>

    /** Package prefixes that count as library code even though an included prefix covers them. */
    public abstract val excludedPackages: ListProperty<String>

    /** Frames captured per event. */
    public abstract val stackDepth: Property<Int>

    /**
     * Command template the GUI runs to open a source location in the IDE; handed to it as `--ide`.
     * Left unset, the GUI uses its own default.
     */
    public abstract val ideCommand: Property<String>

    @get:Nested
    public abstract val live: LiveSettings

    public fun live(action: Action<in LiveSettings>) {
        action.execute(live)
    }

    @get:Nested
    public abstract val pace: PaceSettings

    public fun pace(action: Action<in PaceSettings>) {
        action.execute(pace)
    }

    public fun includePackages(vararg prefixes: String) {
        includedPackages.addAll(*prefixes)
    }

    public fun excludePackages(vararg prefixes: String) {
        excludedPackages.addAll(*prefixes)
    }

    public interface LiveSettings {
        /** Serve the trace over a loopback socket while the program runs, so that the GUI can follow it. On by default. */
        public val enabled: Property<Boolean>
    }

    /**
     * Execution control: the agent can hold the program at its events, to slow it down, pause it and step it, the whole
     * program or a subtree, as told by the GUI over the live socket. What is set here is how a run *starts*.
     */
    public interface PaceSettings {
        /**
         * On by default: an open gate costs the program one volatile read per event and holds nobody, and the GUI's
         * controls work without touching the build. Off, the JVM has no gate at all, and the GUI shows no controls.
         */
        public val enabled: Property<Boolean>

        /**
         * Hold the program at its first event until a GUI resumes or steps it. Needs [LiveSettings.enabled]: without the
         * socket nothing could ever resume the program, so the setting is refused with a warning and the program runs.
         */
        public val startPaused: Property<Boolean>

        /**
         * The pace the run starts with, and falls back to when the last controlling GUI goes away: at most this many
         * events per second in every *sequence* (events made by one flow, or happening to one node; parallel sequences
         * are paced independently). `0.2` is one event in five seconds. Unset: unlimited.
         */
        public val eventsPerSecond: Property<Double>
    }
}
