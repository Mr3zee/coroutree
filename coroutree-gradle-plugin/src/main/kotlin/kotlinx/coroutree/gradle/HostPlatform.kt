package kotlinx.coroutree.gradle

internal object HostPlatform {
    /** Entry paths must match MonitorProbeLoader in coroutree-agent. */
    fun monitorProbeEntry(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): String? {
        val os = osName.lowercase()
        val arch = when (osArch.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x64"
            else -> return null
        }
        val library = when {
            os.startsWith("mac") || os.startsWith("darwin") -> "macos/libcoroutree-monitor.dylib" // universal
            os.startsWith("linux") -> "linux-$arch/libcoroutree-monitor.so"
            os.startsWith("windows") && arch == "x64" -> "windows-x64/coroutree-monitor.dll"
            else -> return null
        }
        return "kotlinx/coroutree/agent/native/$library"
    }
}
