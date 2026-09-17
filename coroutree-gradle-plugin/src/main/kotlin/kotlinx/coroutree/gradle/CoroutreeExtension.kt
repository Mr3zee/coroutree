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
 *     stackDepth = 32
 * }
 * ```
 */
public abstract class CoroutreeExtension {
    /**
     * Attach the agent to the `JavaExec` and `Test` tasks of this project.
     * On by default exactly when the build runs with `-Pcoroutree` (`-Pcoroutree=false` counts as absent).
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
}
