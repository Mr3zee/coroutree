package kotlinx.coroutree.gradle

internal object HostPlatform {
    /**
     * Where in the agent jar the native monitor probe for this machine is, `null` for a platform it is not built for.
     * The names are the agent's (MonitorProbeLoader in coroutree-agent), repeated here because the plugin shares
     * no code with it.
     */
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
