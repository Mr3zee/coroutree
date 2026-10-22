package kotlinx.coroutree.agent

import kotlinx.coroutree.runtime.MonitorProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The platform directories and file names of the native monitor probe are a contract. The module's build script
 * packs the binaries under them, and the Gradle plugin (`HostPlatform`) unpacks the right one.
 */
internal class MonitorProbeLoaderTest {
    @Test
    fun aJvmThatWasStartedWithoutTheProbeSaysSoInsteadOfFailing() {
        // The test JVM runs without `-agentpath`, so the native method isn't bound and `connect` returns false, not an exception.
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
