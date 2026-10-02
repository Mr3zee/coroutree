package kotlinx.coroutree.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.util.Properties

/** A throw-away Gradle build the plugin under test is applied to. */
class TestProject(val dir: File) {
    val fakeAgent: String = File(System.getProperty("coroutree.test.fakeAgent")).invariantSeparatorsPath
    val junitVersion: String = System.getProperty("coroutree.test.junitVersion")

    init {
        file(
            "settings.gradle.kts",
            """
            dependencyResolutionManagement {
                repositories { mavenCentral() }
            }
            rootProject.name = "demo"
            """,
        )
    }

    fun file(path: String, content: String): File = File(dir, path).apply {
        parentFile.mkdirs()
        writeText(content.trimIndent() + "\n")
    }

    fun append(path: String, content: String) = File(dir, path).appendText(content.trimIndent() + "\n")

    /** A Java application whose main class is `demo.app.Main`, with the fake agent standing in for the real one. */
    fun javaApplication(projectDir: String = "", extraBuildScript: String = "") {
        val prefix = if (projectDir.isEmpty()) "" else "$projectDir/"
        file(
            "${prefix}build.gradle.kts",
            """
            plugins {
                application
                id("org.jetbrains.kotlinx.coroutree")
            }
            application { mainClass = "demo.app.Main" }
            tasks.withType<JavaCompile>().configureEach { options.release = 17 }
            dependencies { coroutreeAgent(files("$fakeAgent")) }
            """,
        )
        append("${prefix}build.gradle.kts", extraBuildScript)
        file(
            "${prefix}src/main/java/demo/app/Main.java",
            """
            package demo.app;

            public class Main {
                public static void main(String[] args) {
                    System.out.println("demo is running");
                }
            }
            """,
        )
    }

    fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

    fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")

    /** Without the plugin injected by TestKit: for builds that bring it themselves, see [pluginClasses]. */
    fun runnerWithoutPlugin(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withArguments(*arguments, "--stacktrace")

    /**
     * Class and resource directories of the plugin under test, for a `buildscript { }` class path. A build that takes
     * the plugin this way loads it once per script, which is how projects without a common declaration get it.
     */
    val pluginClasses: List<String> by lazy {
        val metadata = Properties().apply {
            val resource = TestProject::class.java.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")
                ?: error("plugin-under-test-metadata.properties is not on the test class path")
            resource.use(::load)
        }
        metadata.getProperty("implementation-classpath").split(File.pathSeparator).map(::File).filter { it.isDirectory }.map { it.invariantSeparatorsPath }
    }

    /** `group` and `version` of the artifacts the plugin resolves by default: what its own build stamped into it. */
    val pluginProperties: Properties by lazy {
        val file = pluginClasses.map { File(it, "kotlinx/coroutree/gradle/plugin.properties") }.firstOrNull { it.isFile }
            ?: error("plugin.properties is not among $pluginClasses")
        Properties().apply { file.inputStream().use(::load) }
    }

    fun agentConfig(path: String): Properties = Properties().apply { File(dir, path).inputStream().use(::load) }

    fun exists(path: String): Boolean = File(dir, path).exists()

    fun lines(path: String): List<String> = File(dir, path).readLines()
}
