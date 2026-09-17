package kotlinx.coroutree.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
