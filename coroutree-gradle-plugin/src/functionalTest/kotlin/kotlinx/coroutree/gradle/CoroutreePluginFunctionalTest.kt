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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoroutreePluginFunctionalTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var project: TestProject

    @BeforeTest
    fun setUp() {
        project = TestProject(tempDir.canonicalFile)
    }

    @Test
    fun agentIsNotAttachedUnlessEnabled() {
        project.javaApplication()

        val result = project.run("run")

        assertContains(result.output, "demo is running")
        assertNull(result.task(":coroutreeSourceIndex"), "no index is built for a run without the agent")
        assertFalse(project.exists("build/coroutree"), "nothing is written for a run without the agent")
    }

    @Test
    fun javaExecRunsWithAgentAndConfiguration() {
        project.javaApplication()
        project.file("src/main/java/demo/app/deep/Helper.java", "package demo.app.deep;\n\nclass Helper {}")
        project.file("src/test/java/demo/app/MainTest.java", "package demo.app;\n\nclass MainTest {}")
        project.file("src/main/java/Rootless.java", "class Rootless {}")

        val result = project.run("run", "-Pcoroutree")

        assertContains(result.output, "demo is running")
        assertEquals(TaskOutcome.SUCCESS, result.task(":coroutreeSourceIndex")?.outcome)
        val config = project.agentConfig("build/coroutree/tmp/run/agent.properties")
        assertEquals(listOf("config=${File(project.dir, "build/coroutree/tmp/run/agent.properties")}"), project.lines("build/coroutree/tmp/run/attached.txt"))

        val buildId = config.getProperty("build.id")
        assertTrue(Regex("""\d{8}-\d{6}-[0-9a-z]{4}""").matches(buildId), buildId)
        assertEquals(File(project.dir, "build/coroutree/traces/$buildId").path, config.getProperty("trace.dir"))
        assertEquals(File(project.dir, "build/coroutree/sessions/$buildId").path, config.getProperty("sessions.dir"))
        assertTrue(File(config.getProperty("trace.dir")).isDirectory)
        assertEquals("true", config.getProperty("live"))
        assertEquals(":run", config.getProperty("task.path"))
        assertEquals(project.dir.path, config.getProperty("project.dir"))
        assertEquals("32", config.getProperty("stack.depth"))
        assertEquals("demo.app", config.getProperty("include"), "packages of the sources, reduced to minimal prefixes, without the root package")
        assertEquals("", config.getProperty("exclude"))

        assertEquals(File(project.dir, "build/coroutree/tmp/run/source-index.tsv").path, config.getProperty("source.index"))
        assertEquals(
            listOf(
                "M\t:\t${project.dir.path}",
                "F\t\tRootless.java\tsrc/main/java/Rootless.java",
                "F\tdemo.app\tMain.java\tsrc/main/java/demo/app/Main.java",
                "F\tdemo.app.deep\tHelper.java\tsrc/main/java/demo/app/deep/Helper.java",
                "F\tdemo.app\tMainTest.java\tsrc/test/java/demo/app/MainTest.java",
            ),
            File(config.getProperty("source.index")).readLines(),
        )
    }

    @Test
    fun settingsReachTheAgent() {
        project.javaApplication(
            extraBuildScript = """
                coroutree {
                    enabled = true
                    includePackages("demo", "com.acme")
                    excludePackages("demo.generated")
                    live { enabled = false }
                    stackDepth = 8
                }
            """,
        )

        project.run("run")

        val config = project.agentConfig("build/coroutree/tmp/run/agent.properties")
        assertEquals("demo,com.acme", config.getProperty("include"))
        assertEquals("demo.generated", config.getProperty("exclude"))
        assertEquals("false", config.getProperty("live"))
        assertEquals("8", config.getProperty("stack.depth"))
    }

    @Test
    fun propertySetToFalseDoesNotEnable() {
        project.javaApplication()
        project.run("run", "-Pcoroutree=false")
        assertFalse(project.exists("build/coroutree"))
    }

    @Test
    fun configurationCacheIsReusedWithAFreshBuildId() {
        project.javaApplication()

        val first = project.run("run", "-Pcoroutree", "--configuration-cache")
        assertContains(first.output, "Configuration cache entry stored")
        val firstId = project.agentConfig("build/coroutree/tmp/run/agent.properties").getProperty("build.id")

        val second = project.run("run", "-Pcoroutree", "--configuration-cache")
        assertContains(second.output, "Reusing configuration cache")
        assertContains(second.output, "demo is running")
        val secondId = project.agentConfig("build/coroutree/tmp/run/agent.properties").getProperty("build.id")

        assertNotEquals(firstId, secondId)
        assertEquals(2, project.lines("build/coroutree/tmp/run/attached.txt").size)
        assertTrue(File(project.dir, "build/coroutree/traces/$secondId").isDirectory)
    }

    @Test
    fun agentFromAnIncludedBuildIsBuiltFirst() {
        // The default dependency, org.jetbrains.kotlinx:coroutree-agent, substituted by a project: how `samples` gets it.
        project.append("settings.gradle.kts", """includeBuild("agent-build")""")
        project.file("agent-build/settings.gradle.kts", """rootProject.name = "coroutree-agent"""")
        project.file(
            "agent-build/build.gradle.kts",
            """
            plugins { java }
            group = "org.jetbrains.kotlinx"
            tasks.withType<JavaCompile>().configureEach { options.release = 17 }
            tasks.jar { manifest { attributes("Premain-Class" to "agent.Agent") } }
            """,
        )
        project.file(
            "agent-build/src/main/java/agent/Agent.java",
            """
            package agent;

            public class Agent {
                public static void premain(String args) {
                    System.out.println("included agent got " + args.substring(0, args.indexOf('=')));
                }
            }
            """,
        )
        project.javaApplication()
        val buildScript = File(project.dir, "build.gradle.kts")
        buildScript.writeText(buildScript.readText().replace(Regex("""dependencies \{ coroutreeAgent.*"""), ""))

        val result = project.run("run", "-Pcoroutree", "--configuration-cache")

        assertContains(result.output, "> Task :agent-build:jar") // TestKit does not list tasks of included builds
        assertContains(result.output, "included agent got config")
        assertContains(project.run("run", "-Pcoroutree", "--configuration-cache").output, "included agent got config")
    }

    @Test
    fun testTaskRunsWithAgentAndIsNeverUpToDateWithIt() {
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
            tasks.test { useJUnitPlatform() }
            """,
        )
        project.file(
            "src/test/java/demo/DemoTest.java",
            """
            package demo;

            import org.junit.jupiter.api.Test;

            class DemoTest {
                @Test
                void passes() {}
            }
            """,
        )

        assertEquals(TaskOutcome.SUCCESS, project.run("test").task(":test")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, project.run("test").task(":test")?.outcome)
        assertFalse(project.exists("build/coroutree"))

        assertEquals(TaskOutcome.SUCCESS, project.run("test", "-Pcoroutree").task(":test")?.outcome)
        assertEquals(1, project.lines("build/coroutree/tmp/test/attached.txt").size)
        assertEquals(":test", project.agentConfig("build/coroutree/tmp/test/agent.properties").getProperty("task.path"))

        assertEquals(TaskOutcome.SUCCESS, project.run("test", "-Pcoroutree").task(":test")?.outcome, "a traced run is wanted for its trace")
        assertEquals(2, project.lines("build/coroutree/tmp/test/attached.txt").size)
    }

    @Test
    fun sourceIndexAggregatesProjectDependencies() {
        project.append("settings.gradle.kts", """include(":app", ":lib", ":plain")""")
        project.javaApplication(
            projectDir = "app",
            extraBuildScript = """
                dependencies {
                    implementation(project(":lib"))
                    implementation(project(":plain"))
                }
            """,
        )
        project.file(
            "lib/build.gradle.kts",
            """
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget

            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("org.jetbrains.kotlinx.coroutree")
            }
            kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
            tasks.withType<JavaCompile>().configureEach { options.release = 17 }
            """,
        )
        // Kotlin does not tie the directory of a file to its package.
        project.file("lib/src/main/kotlin/Misplaced.kt", "@file:JvmName(\"Misplaced\")\n\npackage com.acme.lib.misplaced\n\nfun answer() = 42")
        project.file("lib/src/main/kotlin/com/acme/lib/Lib.kt", "package com.acme.lib\n\nclass Lib")
        project.file("lib/src/main/java/com/acme/lib/JavaLib.java", "package com.acme.lib;\n\npublic class JavaLib {}")
        project.file("lib/src/test/kotlin/com/acme/lib/LibTest.kt", "package com.acme.lib\n\nclass LibTest")
        // No coroutree plugin here: the module is simply absent from the index.
        project.file(
            "plain/build.gradle.kts",
            """
            plugins { `java-library` }
            tasks.withType<JavaCompile>().configureEach { options.release = 17 }
            """,
        )
        project.file("plain/src/main/java/org/plain/Plain.java", "package org.plain;\n\npublic class Plain {}")

        val result = project.run(":app:run", "-Pcoroutree")

        assertContains(result.output, "demo is running")
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:coroutreeSourceIndex")?.outcome)
        val config = project.agentConfig("app/build/coroutree/tmp/run/agent.properties")
        assertEquals(project.dir.path, config.getProperty("project.dir"))
        assertEquals(File(project.dir, "build/coroutree/traces/${config.getProperty("build.id")}").path, config.getProperty("trace.dir"), "data goes to the root project")
        assertEquals("com.acme.lib,demo.app", config.getProperty("include"))
        assertEquals(
            listOf(
                "M\t:app\t${File(project.dir, "app").path}",
                "F\tdemo.app\tMain.java\tsrc/main/java/demo/app/Main.java",
                "M\t:lib\t${File(project.dir, "lib").path}",
                "F\tcom.acme.lib\tJavaLib.java\tsrc/main/java/com/acme/lib/JavaLib.java",
                "F\tcom.acme.lib.misplaced\tMisplaced.kt\tsrc/main/kotlin/Misplaced.kt",
                "F\tcom.acme.lib\tLib.kt\tsrc/main/kotlin/com/acme/lib/Lib.kt",
                "F\tcom.acme.lib\tLibTest.kt\tsrc/test/kotlin/com/acme/lib/LibTest.kt",
            ),
            File(config.getProperty("source.index")).readLines(),
        )
    }

    @Test
    fun multiplatformProjectIndexesWhatItsJvmTargetCompiles() {
        project.file(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.multiplatform")
                id("org.jetbrains.kotlinx.coroutree")
            }
            kotlin {
                jvm()
                js { nodejs() }
            }
            """,
        )
        project.file("src/commonMain/kotlin/com/acme/Common.kt", "package com.acme\n\nclass Common")
        project.file("src/jvmMain/kotlin/com/acme/Jvm.kt", "package com.acme\n\nclass Jvm")
        project.file("src/jsMain/kotlin/com/acme/Js.kt", "package com.acme\n\nclass Js")
        project.file("src/commonTest/kotlin/com/acme/CommonTest.kt", "package com.acme\n\nclass CommonTest")
        project.file("src/jvmTest/kotlin/com/acme/JvmTest.kt", "package com.acme\n\nclass JvmTest")

        project.run("coroutreeSourceIndex")

        assertEquals(
            setOf(
                "F\tcom.acme\tCommon.kt\tsrc/commonMain/kotlin/com/acme/Common.kt",
                "F\tcom.acme\tJvm.kt\tsrc/jvmMain/kotlin/com/acme/Jvm.kt",
                "F\tcom.acme\tCommonTest.kt\tsrc/commonTest/kotlin/com/acme/CommonTest.kt",
                "F\tcom.acme\tJvmTest.kt\tsrc/jvmTest/kotlin/com/acme/JvmTest.kt",
            ),
            project.lines("build/coroutree/source-index.tsv").drop(1).toSet(),
        )
        val files = project.lines("build/coroutree/source-index.tsv").drop(1).map { it.split('\t')[2] }
        assertTrue(files.indexOf("Jvm.kt") < files.indexOf("CommonTest.kt"), "main before test: $files")
        assertTrue(files.indexOf("Common.kt") < files.indexOf("JvmTest.kt"), "main before test: $files")
    }

    @Test
    fun sourceIndexIsCacheable() {
        project.javaApplication()
        project.file("gradle.properties", "org.gradle.caching=true")
        project.append(
            "settings.gradle.kts",
            """
            buildCache {
                local { directory = File(rootDir, "local-cache") }
            }
            """,
        )

        assertEquals(TaskOutcome.SUCCESS, project.run("coroutreeSourceIndex").task(":coroutreeSourceIndex")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, project.run("coroutreeSourceIndex").task(":coroutreeSourceIndex")?.outcome)
        File(project.dir, "build").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, project.run("coroutreeSourceIndex").task(":coroutreeSourceIndex")?.outcome)

        project.file("src/main/java/demo/app/Added.java", "package demo.app;\n\nclass Added {}")
        assertEquals(TaskOutcome.SUCCESS, project.run("coroutreeSourceIndex").task(":coroutreeSourceIndex")?.outcome)
        assertContains(project.lines("build/coroutree/source-index.tsv"), "F\tdemo.app\tAdded.java\tsrc/main/java/demo/app/Added.java")
    }

    @Test
    fun viewDryRunPrintsTheCommandLine() {
        project.javaApplication(
            extraBuildScript = """
                coroutree { ideCommand = "idea --line {line} {file}" }
                dependencies { coroutreeGui(files("gui.jar", "gui-dependency.jar")) }
            """,
        )
        project.file("gui.jar", "")
        project.file("gui-dependency.jar", "")

        val first = project.run("coroutreeView", "-Pcoroutree.view.dryRun", "--configuration-cache")
        val second = project.run("coroutreeView", "-Pcoroutree.view.dryRun", "--configuration-cache")
        assertContains(second.output, "Reusing configuration cache")

        for (result in listOf(first, second)) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":coroutreeView")?.outcome)
            val command = result.output.lines().dropWhile { it != "coroutreeView command line:" }.drop(1)
                .takeWhile { it.startsWith("  ") }.map { it.removePrefix("  ") }
            assertNotNull(command.firstOrNull(), result.output)
            assertTrue(File(command[0]).name.startsWith("java"), command[0])
            assertEquals(
                listOf(
                    "-cp",
                    listOf("gui.jar", "gui-dependency.jar").joinToString(File.pathSeparator) { File(project.dir, it).path },
                    "kotlinx.coroutree.gui.MainKt",
                    "--dir",
                    File(project.dir, "build/coroutree").path,
                    "--open-latest",
                    "--ide",
                    "idea --line {line} {file}",
                ),
                command.drop(1),
            )
        }
        assertFalse(project.exists("build/coroutree/gui.log"), "a dry run launches nothing")
    }
}
