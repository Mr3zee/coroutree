package kotlinx.coroutree.gui.ide

import kotlinx.coroutree.gui.source.Json
import java.io.File

/** A JetBrains IDE that runs on this machine. */
data class RunningIde(
    val name: String,
    val version: String,
    /** The launcher of the installation. Run with arguments, it hands them to the running instance and exits. */
    val launcher: File,
    /** Directories of the projects the IDE has open. Empty when its recent-projects file isn't readable. */
    val openProjects: List<File>,
) {
    val title: String get() = if (version.isEmpty()) name else "$name $version"

    /** The open project that contains [file], or null. */
    fun projectOf(file: File): File? = openProjects.filter { file.startsWith(it) }.maxByOrNull { it.path.length }
}

/**
 * Finds running JetBrains IDEs through their processes. Every JetBrains installation has a `product-info.json`
 * ([the format](https://github.com/JetBrains/intellij-community/blob/master/platform/build-scripts/src/org/jetbrains/intellij/build/ProductInfoData.kt))
 * with the launcher path. A process is an IDE when its executable is that launcher, or the JVM of the installation
 * running the IDE's main class (`idea.sh` on Linux).
 */
object RunningIdes {
    enum class Os(val productInfoName: String) {
        MAC("macOS"), LINUX("Linux"), WINDOWS("Windows");

        /** Where the IDEs keep their configuration directories, one for each `dataDirectoryName`. */
        fun configRoot(userHome: File = File(System.getProperty("user.home"))): File = when (this) {
            MAC -> File(userHome, "Library/Application Support/JetBrains")
            LINUX -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() } ?: File(userHome, ".config").path, "JetBrains")
            WINDOWS -> File(System.getenv("APPDATA") ?: File(userHome, "AppData/Roaming").path, "JetBrains")
        }

        companion object {
            val current: Os = System.getProperty("os.name", "").lowercase().let {
                when {
                    it.startsWith("mac") -> MAC
                    it.startsWith("windows") -> WINDOWS
                    else -> LINUX
                }
            }
        }
    }

    private const val IDE_MAIN_CLASS = "com.intellij.idea.Main"

    /** How far above its executable an installation's `product-info.json` can be: `Contents/jbr/Contents/Home/bin/java`. */
    private const val MAX_LEVELS = 5

    fun find(): List<RunningIde> {
        val userHome = File(System.getProperty("user.home"))
        val configRoot = Os.current.configRoot(userHome)
        return ProcessHandle.allProcesses().toList()
            .mapNotNull { process ->
                val info = runCatching { process.info() }.getOrNull() ?: return@mapNotNull null
                val command = info.command().orElse(null) ?: return@mapNotNull null
                ideOf(File(command), info.arguments().orElse(null)?.asList(), Os.current, configRoot, userHome)
            }
            .distinctBy { it.launcher }
    }

    /**
     * The IDE whose main process runs [command] with [arguments], or null for any other process. [arguments] is null
     * when the operating system doesn't give them.
     */
    internal fun ideOf(command: File, arguments: List<String>?, os: Os, configRoot: File, userHome: File): RunningIde? {
        val infoFile = productInfoAbove(command) ?: return null
        val info = runCatching { Json.parse(infoFile.readText()) }.getOrNull() ?: return null
        val launch = (info["launch"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull { it["os"] == os.productInfoName } ?: return null
        val launcher = (launch["launcherPath"] as? String)?.let { File(infoFile.parentFile, it).normalize() } ?: return null
        val java = (launch["javaExecutablePath"] as? String)?.let { File(infoFile.parentFile, it).normalize() }
        val isLauncher = sameFile(command, launcher)
        val isIdeJvm = java != null && sameFile(command, java) && arguments.orEmpty().contains(IDE_MAIN_CLASS)
        if (!isLauncher && !isIdeJvm) return null
        val name = info["name"] as? String ?: return null
        val version = info["version"] as? String ?: ""
        val projects = (info["dataDirectoryName"] as? String)
            ?.let { openProjects(File(configRoot, "$it/options/recentProjects.xml"), userHome) }
            .orEmpty()
        return RunningIde(name, version, launcher, projects)
    }

    private fun productInfoAbove(command: File): File? {
        var dir: File? = command.absoluteFile.parentFile
        repeat(MAX_LEVELS) {
            val here = dir ?: return null
            for (candidate in listOf(File(here, "product-info.json"), File(here, "Resources/product-info.json"))) {
                if (candidate.isFile) return candidate
            }
            dir = here.parentFile
        }
        return null
    }

    private fun sameFile(a: File, b: File): Boolean =
        a.absoluteFile.normalize() == b.absoluteFile.normalize() || runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(false)

    private val ENTRY = Regex("""<entry key="([^"]*)">(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The projects that `recentProjects.xml` marks `opened="true"`. The IDE writes the file when a project opens or
     * closes, and marks every project closed when it exits.
     */
    internal fun openProjects(recentProjects: File, userHome: File): List<File> {
        val text = runCatching { recentProjects.readText() }.getOrNull() ?: return emptyList()
        return ENTRY.findAll(text)
            .filter { """opened="true"""" in it.groupValues[2] }
            .map { File(unescapeXml(it.groupValues[1]).replace("\$USER_HOME\$", userHome.path)) }
            .toList()
    }

    private fun unescapeXml(value: String): String =
        value.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** The IDE that has [file]'s project open, the first one otherwise, null when none runs. */
    fun choose(ides: List<RunningIde>, file: File): RunningIde? =
        ides.maxByOrNull { it.projectOf(file)?.path?.length ?: -1 }
}
