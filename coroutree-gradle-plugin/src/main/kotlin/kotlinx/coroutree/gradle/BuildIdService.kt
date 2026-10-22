package kotlinx.coroutree.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Names the build invocation, which groups the traces of the JVMs it forks. A build service is created for each
 * invocation, also on configuration-cache reuse. An id computed while configuring would be frozen into the cache.
 */
internal abstract class BuildIdService : BuildService<BuildServiceParameters.None> {
    val buildId: String by lazy {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val suffix = (1..4).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
        "$time-$suffix"
    }

    companion object {
        const val NAME = "coroutreeBuildId"

        /** Reflection for a service that another project's class loader loaded. */
        fun idOf(service: BuildService<*>): String =
            if (service is BuildIdService) service.buildId else service.javaClass.getMethod("getBuildId").invoke(service) as String
        private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    }
}
