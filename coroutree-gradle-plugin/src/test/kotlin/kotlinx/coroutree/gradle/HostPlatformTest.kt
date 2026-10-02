package kotlinx.coroutree.gradle

import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostPlatformTest {
    private val root = "kotlinx/coroutree/agent/native"

    @Test
    fun monitorProbeEntryFollowsTheAgentsLayout() {
        assertEquals("$root/macos/libcoroutree-monitor.dylib", HostPlatform.monitorProbeEntry("Mac OS X", "aarch64"))
        assertEquals("$root/macos/libcoroutree-monitor.dylib", HostPlatform.monitorProbeEntry("Mac OS X", "x86_64"))
        assertEquals("$root/linux-x64/libcoroutree-monitor.so", HostPlatform.monitorProbeEntry("Linux", "amd64"))
        assertEquals("$root/linux-arm64/libcoroutree-monitor.so", HostPlatform.monitorProbeEntry("Linux", "aarch64"))
        assertEquals("$root/windows-x64/coroutree-monitor.dll", HostPlatform.monitorProbeEntry("Windows 11", "amd64"))
    }

    @Test
    fun platformsWithoutAProbeGetNone() {
        assertNull(HostPlatform.monitorProbeEntry("Windows 11", "aarch64"))
        assertNull(HostPlatform.monitorProbeEntry("Linux", "riscv64"))
        assertNull(HostPlatform.monitorProbeEntry("FreeBSD", "amd64"))
    }

    // os.name and os.arch are free text: the same machine is "aarch64" to one JVM and "arm64" to another.
    @Test
    fun namesAreMatchedAsJvmsSpellThem() {
        assertEquals("$root/macos/libcoroutree-monitor.dylib", HostPlatform.monitorProbeEntry("Darwin", "arm64"))
        assertEquals("$root/linux-arm64/libcoroutree-monitor.so", HostPlatform.monitorProbeEntry("linux", "ARM64"))
        assertEquals("$root/linux-x64/libcoroutree-monitor.so", HostPlatform.monitorProbeEntry("Linux", "x86_64"))
        assertEquals("$root/windows-x64/coroutree-monitor.dll", HostPlatform.monitorProbeEntry("Windows Server 2022", "AMD64"))
        // 32-bit JVMs on supported systems have no probe either.
        for (arch in listOf("x86", "i386", "arm", "ppc64le", "s390x", "")) {
            for (os in listOf("Mac OS X", "Linux", "Windows 10")) assertNull(HostPlatform.monitorProbeEntry(os, arch), "$os/$arch")
        }
    }

    // The GUI is published for exactly the platforms DESIGN §12 lists; anything else must say what to do, not fail to resolve.
    @Test
    fun guiClassifierNamesThePlatformOrExplainsWhatToDo() {
        assertEquals("macos-arm64", CoroutreePlugin.hostClassifier("Darwin", "arm64"))
        assertEquals("linux-x64", CoroutreePlugin.hostClassifier("Linux", "x86_64"))
        assertEquals("windows-x64", CoroutreePlugin.hostClassifier("Windows Server 2022", "AMD64"))
        for ((os, arch) in listOf("Linux" to "riscv64", "FreeBSD" to "amd64", "Windows 11" to "arm64", "Linux" to "x86")) {
            val failure = assertFailsWith<GradleException> { CoroutreePlugin.hostClassifier(os, arch) }
            assertTrue("$os/$arch" in failure.message!! && "coroutreeGui" in failure.message!!, failure.message)
        }
    }
}
