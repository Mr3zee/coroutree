package kotlinx.coroutree.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Which JVMs get the agent, what else they are started with, and where the artifacts come from. */
class AgentAttachmentFunctionalTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var project: TestProject

    @BeforeTest
    fun setUp() {
        project = TestProject(tempDir.canonicalFile)
    }

    private fun javaTestProject(testTask: String = "") {
        project.file(
            "build.gradle.kts",
            """
            plugins {
                java
                id("org.jetbrains.kotlinx.coroutree")
            }
            tasks.withType<JavaCompile>().configureEach { options.release = 17 }
            dependencies {
                coroutreeAgent(files("${project.fakeAgent}"))
                testImplementation("org.junit.jupiter:junit-jupiter:${project.junitVersion}")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.junitVersion}")
            }
            tasks.test {
                useJUnitPlatform()
                $testTask
            }
            """,
        )
    }

    private fun javaTestClass(name: String) =
        project.file("src/test/java/demo/$name.java", "package demo;\n\nimport org.junit.jupiter.api.Test;\n\nclass $name {\n    @Test\n    void passes() {}\n}")

    @Test
    fun everyJavaExecGetsAConfigurationOfItsOwnAndKeepsTheJvmArgumentsOfTheBuild() {
        // Where a user's project may well live: a path with a blank, and with what separates -javaagent options.
        project = TestProject(File(tempDir.canonicalFile, "odd dir, a=b"))
        project.javaApplication(
            extraBuildScript = """
                application { applicationDefaultJvmArgs = listOf("-Ddemo.user=first", "-Xmx96m") }
                tasks.register<JavaExec>("second") {
                    classpath = sourceSets["main"].runtimeClasspath
                    mainClass = "demo.app.Main"
                    jvmArgs("-Ddemo.user=second", "-Xmx97m")
                }
            """,
        )
        project.file(
            "src/main/java/demo/app/Main.java",
            """
            package demo.app;

            public class Main {
                public static void main(String[] args) {
                    System.out.println("jvm of " + System.getProperty("demo.user") + ": " + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
                }
            }
            """,
        )
        fun jvm(output: String, user: String) = output.lines().single { it.startsWith("jvm of $user: ") }

        val plain = project.run("run", "second").output
        for (user in listOf("first", "second")) {
            assertFalse("-javaagent" in jvm(plain, user) || "-agentpath" in jvm(plain, user) || "-Xshare" in jvm(plain, user), "without the agent a JVM starts as the build script says: ${jvm(plain, user)}")
        }

        val traced = project.run("run", "second", "-Pcoroutree").output
        val run = project.agentConfig("build/coroutree/tmp/run/agent.properties")
        val second = project.agentConfig("build/coroutree/tmp/second/agent.properties")
        for ((user, task, own) in listOf(Triple("first", "run", listOf("-Xmx96m")), Triple("second", "second", listOf("-Xmx97m")))) {
            val arguments = jvm(traced, user)
            assertContains(arguments, "-javaagent:${File(project.fakeAgent).path}=config=${File(project.dir, "build/coroutree/tmp/$task/agent.properties").path}")
            assertContains(arguments, "-Xshare:off")
            for (argument in own) assertContains(arguments, argument, message = "the task's own JVM arguments stay")
            assertEquals(1, project.lines("build/coroutree/tmp/$task/attached.txt").size)
        }

        assertEquals(":run", run.getProperty("task.path"))
        assertEquals(":second", second.getProperty("task.path"))
        assertEquals(run.getProperty("build.id"), second.getProperty("build.id"), "JVMs forked by one invocation are grouped under one id")
        assertEquals(File(project.dir, "build/coroutree/traces/${run.getProperty("build.id")}").path, run.getProperty("trace.dir"))
        assertEquals(run.getProperty("trace.dir"), second.getProperty("trace.dir"))
        assertEquals(project.dir.path, second.getProperty("project.dir"))
        assertEquals(project.lines("build/coroutree/tmp/run/source-index.tsv"), project.lines("build/coroutree/tmp/second/source-index.tsv"))
        assertEquals(listOf("M\t:\t${project.dir.path}", "F\tdemo.app\tMain.java\tsrc/main/java/demo/app/Main.java"), project.lines("build/coroutree/tmp/second/source-index.tsv"))
    }

    /**
     * A JavaExec has no outputs, so Gradle runs it whenever it is asked to: `gradlew run` starts the program.
     * A build that has the plugin and does not use it is to behave like one that does not have it.
     */
    @Test
    fun aProgramRunsEveryTimeItIsAskedForWithOrWithoutTheAgent() {
        project.javaApplication()
        for (attempt in 1..2) {
            val result = project.run("run")
            assertContains(result.output, "demo is running", message = "plain run $attempt")
            assertEquals(TaskOutcome.SUCCESS, result.task(":run")?.outcome, "plain run $attempt")
        }
        for (attempt in 1..2) assertContains(project.run("run", "-Pcoroutree").output, "demo is running", message = "traced run $attempt")
        assertEquals(2, project.lines("build/coroutree/tmp/run/attached.txt").size)
        assertContains(project.run("run").output, "demo is running", message = "plain again")
    }

    @Test
    fun aProjectWithoutAJvmPluginStillGetsTheAgent() {
        project.file(
            "build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlinx.coroutree") }
            dependencies { coroutreeAgent(files("${project.fakeAgent}")) }
            // A program that comes as a jar; the stand-in for the GUI is one that says where it was started.
            tasks.register<JavaExec>("launch") {
                classpath = files("${project.fakeAgent}")
                mainClass = "kotlinx.coroutree.gui.MainKt"
                args("--dir", layout.buildDirectory.dir("launch").get().asFile.path)
            }
            """,
        )

        project.run("launch")
        assertEquals(1, project.lines("build/launch/launched.txt").count { it == "launched" })
        assertFalse(project.exists("build/coroutree"))

        val result = project.run("launch", "-Pcoroutree")
        assertEquals(TaskOutcome.SUCCESS, result.task(":coroutreeSourceIndex")?.outcome)
        assertEquals(2, project.lines("build/launch/launched.txt").count { it == "launched" })
        assertEquals(1, project.lines("build/coroutree/tmp/launch/attached.txt").size)
        val config = project.agentConfig("build/coroutree/tmp/launch/agent.properties")
        assertEquals(":launch", config.getProperty("task.path"))
        assertEquals("", config.getProperty("include"), "no sources, no project packages")
        assertEquals(listOf("M\t:\t${project.dir.path}"), File(config.getProperty("source.index")).readLines())
    }

    @Test
    fun everyForkOfATestTaskIsAttached() {
        javaTestProject(testTask = "forkEvery = 1\nmaxParallelForks = 2")
        val classes = listOf("FirstTest", "SecondTest", "ThirdTest", "FourthTest", "FifthTest", "SixthTest")
        classes.forEach(::javaTestClass)

        // The stand-in agent refuses to start on a configuration that is not whole, which fails the fork and the task.
        val result = project.run("test", "-Pcoroutree")

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        val attached = project.lines("build/coroutree/tmp/test/attached.txt")
        assertEquals(classes.size, attached.size, "one JVM per test class")
        assertEquals(setOf("config=${File(project.dir, "build/coroutree/tmp/test/agent.properties")}"), attached.toSet())
        assertEquals(1, File(project.dir, "build/coroutree/traces").list()!!.size, "all forks of one invocation write into one directory")
    }

    @Test
    fun aTracedTestRunIsNeverTakenFromTheBuildCacheAndPlainOnesStillAre() {
        javaTestProject()
        javaTestClass("DemoTest")
        project.append("settings.gradle.kts", """buildCache { local { directory = File(rootDir, "local-cache") } }""")
        fun test(vararg arguments: String): TaskOutcome? {
            File(project.dir, "build").deleteRecursively()
            return project.run("test", "--build-cache", *arguments).task(":test")?.outcome
        }

        assertEquals(TaskOutcome.SUCCESS, test())
        assertEquals(TaskOutcome.FROM_CACHE, test(), "the plugin does not get in the way of a build that does not use it")
        assertFalse(project.exists("build/coroutree"))

        assertEquals(TaskOutcome.SUCCESS, test("-Pcoroutree"))
        assertEquals(TaskOutcome.SUCCESS, test("-Pcoroutree"), "a run with the agent exists for its trace")
        assertEquals(1, project.lines("build/coroutree/tmp/test/attached.txt").size)

        assertEquals(TaskOutcome.FROM_CACHE, test(), "and what traced runs left in the cache is not theirs to serve")
        assertFalse(project.exists("build/coroutree"))
    }

    @Test
    fun switchingTheAgentOnAndOffWorksWhateverTheConfigurationCacheHolds() {
        project.javaApplication()
        val attached = "build/coroutree/tmp/run/attached.txt"

        assertContains(project.run("run", "--configuration-cache").output, "Configuration cache entry stored")
        assertFalse(project.exists("build/coroutree"))

        // The entry above was stored for a build without the agent: no index task, no agent jar among the inputs.
        val on = project.run("run", "--configuration-cache", "-Pcoroutree")
        assertEquals(TaskOutcome.SUCCESS, on.task(":coroutreeSourceIndex")?.outcome)
        assertEquals(1, project.lines(attached).size)

        project.run("run", "--configuration-cache", "-Pcoroutree=false")
        project.run("run", "--configuration-cache")
        assertEquals(1, project.lines(attached).size, "off again")

        project.run("run", "--configuration-cache", "-Pcoroutree")
        assertEquals(2, project.lines(attached).size)
    }

    @Test
    fun theSwitchIsAGradlePropertyFromWhereverGradleTakesThem() {
        project.javaApplication(extraBuildScript = "coroutree { enabled = true }")
        val attached = "build/coroutree/tmp/run/attached.txt"

        // A properties file keeps the blanks at the end of a line.
        project.file("gradle.properties", "coroutree=false ")
        project.run("run")
        assertFalse(project.exists("build/coroutree"), "gradle.properties switches off what the script switches on")

        // Anything but false has always meant on.
        project.run("run", "-Pcoroutree=1")
        assertEquals(1, project.lines(attached).size)

        val environment = System.getenv() + mapOf("ORG_GRADLE_PROJECT_coroutree" to "yes", "ORG_GRADLE_PROJECT_coroutree.pace.eventsPerSecond" to " 0.5 ", "ORG_GRADLE_PROJECT_coroutree.live" to "false")
        project.runner("run").withEnvironment(environment).build()
        assertEquals(2, project.lines(attached).size, "the environment is stronger than gradle.properties")
        val config = project.agentConfig("build/coroutree/tmp/run/agent.properties")
        assertEquals("0.5", config.getProperty("pace.events.per.second"))
        assertEquals("false", config.getProperty("live"))

        val bad = project.runner("run").withEnvironment(System.getenv() + mapOf("ORG_GRADLE_PROJECT_coroutree" to "1", "ORG_GRADLE_PROJECT_coroutree.live" to "nope")).buildAndFail()
        assertContains(bad.output, "-Pcoroutree.live=nope")
    }

    @Test
    fun artifactsAreResolvedFromRepositoriesByTheirPublishedCoordinatesAndOnlyWhenNeeded() {
        val group = project.pluginProperties.getProperty("group")
        val version = project.pluginProperties.getProperty("version")
        assertEquals("org.jetbrains.kotlinx", group)
        project.file(
            "settings.gradle.kts",
            """
            dependencyResolutionManagement {
                repositories { maven { url = uri("repo") } }
            }
            rootProject.name = "demo"
            """,
        )
        project.javaApplication()
        val buildScript = File(project.dir, "build.gradle.kts")
        buildScript.writeText(buildScript.readText().replace(Regex("""dependencies \{ coroutreeAgent.*"""), ""))

        // Nothing is published where this build looks, and nobody asked for the agent or the GUI.
        assertContains(project.run("run").output, "demo is running")

        val missing = project.runner("run", "-Pcoroutree").buildAndFail()
        assertContains(missing.output, "$group:coroutree-agent:$version")
        assertFalse("demo is running" in missing.output, "the program ran without the agent that was asked for")

        fun publish(artifact: String, classifier: String?): File {
            val directory = "repo/${group.replace('.', '/')}/$artifact/$version"
            project.file(
                "$directory/$artifact-$version.pom",
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>$group</groupId>
                  <artifactId>$artifact</artifactId>
                  <version>$version</version>
                </project>
                """,
            )
            val jar = File(project.dir, "$directory/$artifact-$version${if (classifier == null) "" else "-$classifier"}.jar")
            File(project.fakeAgent).copyTo(jar)
            return jar
        }
        publish("coroutree-agent", classifier = null)
        val gui = publish("coroutree-gui", classifier = hostClassifier())

        assertContains(project.run("run", "-Pcoroutree").output, "demo is running")
        assertEquals(1, project.lines("build/coroutree/tmp/run/attached.txt").size)

        val view = project.run("coroutreeView", "-Pcoroutree.view.dryRun").output.lines()
        val classpath = view[view.indexOf("  -cp") + 1].trim()
        assertEquals(gui.name, File(classpath).name, "the GUI is the one published for this platform, and nothing else")
    }

    /** The classifiers the GUI is published under (DESIGN §12), spelled out again: the tests share no code with the plugin. */
    private fun hostClassifier(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
        return when {
            os.startsWith("mac") -> "macos-$arch"
            os.startsWith("linux") -> "linux-$arch"
            else -> "windows-$arch"
        }
    }

    /**
     * Projects of one build that each bring the plugin themselves, with no common declaration above them, load its
     * classes once each. To every one of them the build service that names the invocation is the same service.
     */
    @Test
    fun projectsThatLoadThePluginSeparatelyAreStillOneBuild() {
        project.append("settings.gradle.kts", """include(":a", ":b", ":core")""")
        project.file(
            "build.gradle.kts",
            """
            gradle.projectsEvaluated {
                val classes = subprojects.map { it.plugins.getPlugin("org.jetbrains.kotlinx.coroutree").javaClass }.toSet()
                println("coroutree plugin loaded " + classes.size + " times")
            }
            """,
        )
        val pluginClasspath = project.pluginClasses.joinToString(", ") { "\"$it\"" }
        fun module(name: String, plugin: String, extra: String) {
            project.file(
                "$name/build.gradle.kts",
                """
                buildscript {
                    // The class path differs from project to project, as it does when they declare different sets of plugins.
                    dependencies { classpath(files($pluginClasspath, "only-$name")) }
                }
                plugins { $plugin }
                apply(plugin = "org.jetbrains.kotlinx.coroutree")
                tasks.withType<JavaCompile>().configureEach { options.release = 17 }
                dependencies {
                    "coroutreeAgent"(files("${project.fakeAgent}"))
                    "coroutreeGui"(files("${project.fakeAgent}"))
                }
                """,
            )
            project.append("$name/build.gradle.kts", extra)
            project.file("$name/only-$name/marker-$name.txt", name)
            project.file(
                "$name/src/main/java/demo/$name/Main.java",
                """
                package demo.$name;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println("$name is running");
                    }
                }
                """,
            )
        }
        val application = """
            configure<JavaApplication> { mainClass = "demo.%s.Main" }
            dependencies { "implementation"(project(":core")) }
        """
        module("a", "application", application.format("a"))
        module("b", "application", application.format("b"))
        module("core", "`java-library`", "")

        val result = project.runnerWithoutPlugin(":a:run", ":b:run", "-Pcoroutree").build()

        assertContains(result.output, "coroutree plugin loaded 3 times", message = "the build does not show what this test is about")
        assertContains(result.output, "a is running")
        assertContains(result.output, "b is running")
        val a = project.agentConfig("a/build/coroutree/tmp/run/agent.properties")
        val b = project.agentConfig("b/build/coroutree/tmp/run/agent.properties")
        assertEquals(a.getProperty("build.id"), b.getProperty("build.id"))
        assertEquals(File(project.dir, "build/coroutree/traces/${a.getProperty("build.id")}").path, a.getProperty("trace.dir"))
        assertEquals(a.getProperty("trace.dir"), b.getProperty("trace.dir"))
        // The index of a dependency crosses the class loaders too.
        assertEquals("demo.b,demo.core", b.getProperty("include"))
        assertEquals(
            listOf(
                "M\t:a\t${File(project.dir, "a").path}",
                "F\tdemo.a\tMain.java\tsrc/main/java/demo/a/Main.java",
                "M\t:core\t${File(project.dir, "core").path}",
                "F\tdemo.core\tMain.java\tsrc/main/java/demo/core/Main.java",
            ),
            File(a.getProperty("source.index")).readLines(),
        )

        val next = project.runnerWithoutPlugin(":a:run", "-Pcoroutree").build()
        assertContains(next.output, "a is running")
        assertNotEquals(a.getProperty("build.id"), project.agentConfig("a/build/coroutree/tmp/run/agent.properties").getProperty("build.id"))

        // Every one of the three has a coroutreeView; they all mean the same GUI on the same directory.
        val view = project.runnerWithoutPlugin("coroutreeView").build()
        assertEquals(3, view.tasks.count { it.path.endsWith(":coroutreeView") && it.outcome == TaskOutcome.SUCCESS })
        assertEquals(1, Regex("coroutree GUI started").findAll(view.output).count(), view.output)
        assertEquals(listOf(listOf("--dir", File(project.dir, "build/coroutree").path, "--open-latest")), GuiLaunches.await(File(project.dir, "build/coroutree"), 1))
        assertTrue(File(project.dir, "build/coroutree/gui.log").isFile)
    }
}
