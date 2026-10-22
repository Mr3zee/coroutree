package kotlinx.coroutree.gradle

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/**
 * ```
 * coroutree {
 *     enabled = providers.gradleProperty("coroutree").isPresent
 *     includePackages("com.acme")
 *     excludePackages("com.acme.generated")
 *     live { enabled = true }
 *     pace {
 *         enabled = true
 *         startPaused = false
 *         eventsPerSecond = 0.2
 *     }
 *     stackDepth = 32
 *     sessionsDirectory = layout.buildDirectory.dir("coroutree/sessions")
 * }
 * ```
 *
 * Use Gradle properties to override these settings for one run:
 *
 * - `-Pcoroutree`: enables tracing. `-Pcoroutree=false` disables it.
 * - `-Pcoroutree.live`: enables live streaming. Accepts `true`, `false`, or no value for `true`.
 * - `-Pcoroutree.pace`: enables execution control. Accepts the same boolean values.
 * - `-Pcoroutree.pace.startPaused`: starts paused. Accepts the same boolean values.
 * - `-Pcoroutree.pace.eventsPerSecond`: sets the initial pace. Accepts a positive number or `unlimited`.
 */
public abstract class CoroutreeExtension {
    /**
     * Whether to attach the agent to this project's `JavaExec` and `Test` tasks. Default is false.
     * `-Pcoroutree` enables tracing and `-Pcoroutree=false` disables it, regardless of this setting.
     */
    public abstract val enabled: Property<Boolean>

    /**
     * Package prefixes tagged as project code. All other packages are tagged as library code.
     * Default is empty, which uses this project's source packages and those of its project dependencies.
     */
    public abstract val includedPackages: ListProperty<String>

    /** Package prefixes tagged as library code even when an included prefix matches. Default is empty. */
    public abstract val excludedPackages: ListProperty<String>

    /** Maximum stack frames captured for each event. Must be positive. Default is 32. */
    public abstract val stackDepth: Property<Int>

    /**
     * Command template for the GUI to open a source location in the IDE.
     * Default is unset, which uses the GUI's default command.
     */
    public abstract val ideCommand: Property<String>

    /**
     * Where the agent publishes the session descriptor that the GUI's session list reads.
     * Default is unset, which uses the agent's default: `~/.coroutree/sessions`, shared by every build of the user.
     * Set it to keep a build's JVMs out of that list, on CI for example.
     */
    public abstract val sessionsDirectory: DirectoryProperty

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
        /** Whether to serve the trace to the GUI over a loopback socket while the program runs. Default is true. */
        public val enabled: Property<Boolean>
    }

    /**
     * Initial execution-control settings. The GUI can later change the pace, pause, or step the program or a subtree.
     */
    public interface PaceSettings {
        /**
         * Whether the GUI can slow down, pause, and step the program. Default is true.
         * If false, the GUI hides execution controls.
         */
        public val enabled: Property<Boolean>

        /**
         * Whether to hold the program at its first event until a GUI resumes or steps it. Default is false.
         * Requires [LiveSettings.enabled]. Otherwise, the agent reports a warning and runs without the initial pause.
         */
        public val startPaused: Property<Boolean>

        /**
         * Initial maximum events per second for each sequence: one flow or one node. Parallel sequences are paced independently.
         * When the last controlling GUI disconnects, the run returns to this pace.
         * `0.2` is one event in five seconds. Default is unset for unlimited speed.
         */
        public val eventsPerSecond: Property<Double>
    }
}
