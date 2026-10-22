package kotlinx.coroutree.agent

import kotlinx.coroutree.runtime.MonitorProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Which build of the native monitor probe a JVM gets. Platform directories and file names are a contract with the
 * module's build script, which packs the binaries, and with the Gradle plugin, which unpacks the right one.
 */
internal class MonitorProbeLoaderTest {
    @Test
    fun aJvmThatWasStartedWithoutTheProbeSaysSoInsteadOfFailing() {
        // No -agentpath here: the native method is not there, which is an answer, not an error.
        assertFalse(MonitorProbe.connect())
    }

    @Test
    fun theProbeOfAPlatformIsTheOneTheBuildPacksForItAndNoneWhereNobodyBuildsOne() {
        val platforms = listOf(
            // os.name, os.arch, <platform>/<file name>
            Triple("Mac OS X", "aarch64", "macos/libcoroutree-monitor.dylib"),
            Triple("Mac OS X", "x86_64", "macos/libcoroutree-monitor.dylib"), // one universal binary
            Triple("Darwin", "arm64", "macos/libcoroutree-monitor.dylib"),
            Triple("Linux", "amd64", "linux-x64/libcoroutree-monitor.so"),
            Triple("Linux", "x86_64", "linux-x64/libcoroutree-monitor.so"),
            Triple("Linux", "aarch64", "linux-arm64/libcoroutree-monitor.so"),
            Triple("LINUX", "AMD64", "linux-x64/libcoroutree-monitor.so"),
            Triple("Windows 11", "amd64", "windows-x64/coroutree-monitor.dll"),
            Triple("Windows Server 2022", "amd64", "windows-x64/coroutree-monitor.dll"),
            Triple("Windows 11", "aarch64", null),
            Triple("Linux", "riscv64", null),
            Triple("Linux", "x86", null),
            Triple("Mac OS X", "ppc", null),
            Triple("FreeBSD", "amd64", null),
            Triple("", "", null),
        )
        val os = System.getProperty("os.name")
        val arch = System.getProperty("os.arch")
        try {
            for ((name, architecture, library) in platforms) {
                System.setProperty("os.name", name)
                System.setProperty("os.arch", architecture)
                assertEquals(library, MonitorProbeLoader.hostLibrary(), "$name on $architecture")
            }
        } finally {
            System.setProperty("os.name", os)
            System.setProperty("os.arch", arch)
        }
    }
}
