package kotlinx.coroutree.gradle

import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/**
 * The `-Pcoroutree.*` properties of one run. They override the build script in both directions, so they can't be
 * extension conventions. Each is a provider chain, so a changed `-P` takes effect on a reused configuration cache.
 * A value that doesn't parse fails the build and names the property.
 */
internal class CommandLine(private val providers: ProviderFactory) {
    /** `true` (also bare: `-Pname`) or `false`. */
    fun boolean(name: String, script: Provider<Boolean>): Provider<Boolean> =
        providers.gradleProperty(name).map { text -> parseBoolean(name, text) }.orElse(script)

    /** Unlike other booleans, `-Pcoroutree` accepts any value except `false` as enabled. */
    fun enabled(script: Provider<Boolean>): Provider<Boolean> =
        providers.gradleProperty(ENABLED).map { it.trim() != "false" }.orElse(script)

    /** A positive decimal number or `unlimited`, in the agent's configuration format. */
    fun eventsPerSecond(script: Provider<Double>): Provider<String> =
        providers.gradleProperty(EVENTS_PER_SECOND)
            .map { text -> parseEventsPerSecond("-P$EVENTS_PER_SECOND", text.trim()) }
            .orElse(script.map { value -> parseEventsPerSecond("coroutree.pace.eventsPerSecond", value.toString()) })
            .orElse(AgentArgumentProvider.UNLIMITED)

    companion object {
        const val ENABLED = "coroutree"
        const val LIVE = "coroutree.live"
        const val PACE = "coroutree.pace"
        const val START_PAUSED = "coroutree.pace.startPaused"
        const val EVENTS_PER_SECOND = "coroutree.pace.eventsPerSecond"

        fun parseBoolean(name: String, text: String): Boolean = when (text.trim()) {
            "", "true" -> true
            "false" -> false
            else -> throw GradleException("-P$name=$text: expected 'true' or 'false'. A bare -P$name means true.")
        }

        fun parseEventsPerSecond(what: String, text: String): String {
            if (text.equals(AgentArgumentProvider.UNLIMITED, ignoreCase = true)) return AgentArgumentProvider.UNLIMITED
            val value = text.toDoubleOrNull()
            if (value == null || value.isNaN() || value.isInfinite() || value <= 0.0) {
                throw GradleException("$what=$text: expected a positive number of events per second or 'unlimited'. For example, 0.2 is one event in 5 seconds.")
            }
            return text
        }
    }
}
