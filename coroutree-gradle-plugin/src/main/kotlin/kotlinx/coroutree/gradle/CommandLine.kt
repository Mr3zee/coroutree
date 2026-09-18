package kotlinx.coroutree.gradle

import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/**
 * Settings of a single run: `-Pcoroutree.live=false`, `-Pcoroutree.pace.eventsPerSecond=2`. **The command line takes
 * precedence over the build script**, in both directions, which is why these are not conventions of the extension
 * (a convention is what the script overrides): each is resolved as `gradleProperty(…).orElse(what the script says)`
 * where the agent's arguments are put together. That is a provider chain, so it survives the configuration cache, and
 * a changed `-P` arrives without the build being configured again.
 *
 * A value that does not parse fails the build and names the property; it never silently falls back. Being Gradle
 * properties they may equally sit in `gradle.properties` or in `ORG_GRADLE_PROJECT_…`; Gradle's own order applies
 * among those sources.
 */
internal class CommandLine(private val providers: ProviderFactory) {
    /** `true` (also bare: `-Pname`) or `false`. */
    fun boolean(name: String, script: Provider<Boolean>): Provider<Boolean> =
        providers.gradleProperty(name).map { text -> parseBoolean(name, text) }.orElse(script)

    /**
     * `-Pcoroutree` itself. Anything but `false` has always switched the agent on (`-Pcoroutree=1`), and stays so.
     */
    fun enabled(script: Provider<Boolean>): Provider<Boolean> =
        providers.gradleProperty(ENABLED).map { it.trim() != "false" }.orElse(script)

    /** A positive decimal number, or `unlimited`. The result is what goes into `agent.properties`. */
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
            else -> throw GradleException("-P$name=$text: expected 'true' (or just -P$name) or 'false'")
        }

        fun parseEventsPerSecond(what: String, text: String): String {
            if (text.equals(AgentArgumentProvider.UNLIMITED, ignoreCase = true)) return AgentArgumentProvider.UNLIMITED
            val value = text.toDoubleOrNull()
            if (value == null || value.isNaN() || value.isInfinite() || value <= 0.0) {
                throw GradleException("$what=$text: expected a positive number of events per second (0.2 is one event in five seconds) or 'unlimited'")
            }
            return text
        }
    }
}
