package kotlinx.coroutree.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Names the build invocation. Traces and live sessions of every JVM forked by one invocation are grouped under it.
 *
 * A build service lives for one invocation and is created anew even when the configuration cache is reused,
 * which is exactly the lifetime the id needs; computing it while configuring would freeze it into the cache.
 */
internal abstract class BuildIdService : BuildService<BuildServiceParameters.None> {
    val buildId: String by lazy {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val suffix = (1..4).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
        "$time-$suffix"
    }

    companion object {
        const val NAME = "coroutreeBuildId"

        /** The id of a service that may be an instance of this class as another class loader knows it. */
        fun idOf(service: BuildService<*>): String =
            if (service is BuildIdService) service.buildId else service.javaClass.getMethod("getBuildId").invoke(service) as String
        private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    }
}
