package kotlinx.coroutree.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * The id must change for each invocation, including configuration-cache reuse.
 * Computing it during configuration would cache the id across invocations.
 */
internal abstract class BuildIdService : BuildService<BuildServiceParameters.None> {
    val buildId: String by lazy {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val suffix = (1..4).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
        "$time-$suffix"
    }

    companion object {
        const val NAME = "coroutreeBuildId"

        /** Reflection supports services loaded by another project's class loader. */
        fun idOf(service: BuildService<*>): String =
            if (service is BuildIdService) service.buildId else service.javaClass.getMethod("getBuildId").invoke(service) as String
        private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    }
}
