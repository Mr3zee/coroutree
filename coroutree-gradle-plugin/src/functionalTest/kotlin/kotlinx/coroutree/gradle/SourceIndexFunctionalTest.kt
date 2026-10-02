package kotlinx.coroutree.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the source index lists, in which order, and when it is written again. */
class SourceIndexFunctionalTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun everySourceDirectoryOfTheProjectIsIndexedOnceMainBeforeTest() {
        val project = TestProject(tempDir.canonicalFile)
        project.file(
            "build.gradle.kts",
            """
            plugins {
                // Before the plugins whose source sets it reads.
                id("org.jetbrains.kotlinx.coroutree")
                id("org.jetbrains.kotlin.jvm")
            }
            val generate = tasks.register("generate") {
                val directory = layout.buildDirectory.dir("generated/kotlin")
                outputs.dir(directory)
                doLast {
                    directory.get().file("com/acme/gen/Generated.kt").asFile.apply {
                        parentFile.mkdirs()
                        writeText("package com.acme.gen\n\nclass Generated\n")
                    }
                }
            }
            sourceSets.create("integrationTest")
            kotlin.sourceSets.named("main") {
                kotlin.srcDir(generate)
                kotlin.srcDir("src/extra")
            }
            """,
        )
        project.file("src/main/kotlin/com/acme/App.kt", "package com.acme\n\nclass App")
        // In a Kotlin project this directory belongs to the Java source set and to the Kotlin one.
        project.file("src/main/java/com/acme/J.java", "package com.acme;\n\nclass J {}")
        project.file("src/extra/Extra.kt", "package com.acme.extra\n\nclass Extra")
        project.file("src/test/kotlin/com/acme/App.kt", "package com.acme\n\nclass AppTest")
        project.file("src/integrationTest/kotlin/com/acme/it/It.kt", "package com.acme.it\n\nclass It")
        // Not sources: a note among them, and a template that only looks like one.
        project.file("src/main/kotlin/com/acme/notes.txt", "package com.acme.notes")
        project.file("src/main/resources/templates/Template.kt", "package com.acme.templates")

        val result = project.run("coroutreeSourceIndex")

        assertEquals(TaskOutcome.SUCCESS, result.task(":generate")?.outcome, "sources that are generated are generated before they are indexed")
        val index = project.lines("build/coroutree/source-index.tsv")
        assertEquals("M\t:\t${project.dir.path}", index.first())
        val main = listOf(
            "F\tcom.acme\tApp.kt\tsrc/main/kotlin/com/acme/App.kt",
            "F\tcom.acme\tJ.java\tsrc/main/java/com/acme/J.java",
            "F\tcom.acme.extra\tExtra.kt\tsrc/extra/Extra.kt",
            "F\tcom.acme.gen\tGenerated.kt\tbuild/generated/kotlin/com/acme/gen/Generated.kt",
        )
        val test = listOf(
            "F\tcom.acme\tApp.kt\tsrc/test/kotlin/com/acme/App.kt",
            "F\tcom.acme.it\tIt.kt\tsrc/integrationTest/kotlin/com/acme/it/It.kt",
        )
        assertEquals((main + test).sorted(), index.drop(1).sorted())
        // (package, file name) is the key: of two files with the same one the first listed is found, and that is to be the main one.
        assertEquals(main.toSet(), index.drop(1).take(main.size).toSet(), "main sources first: $index")
    }

    @Test
    fun theIndexIsWrittenAgainWhenWhatItSaysChangesAndOnlyThen() {
        val project = TestProject(File(tempDir.canonicalFile, "original"))
        project.javaApplication()
        // One cache for the project and for its copy below.
        project.append("settings.gradle.kts", """buildCache { local { directory = File(rootDir, "../build-cache") } }""")
        fun TestProject.index(): TaskOutcome? = run("coroutreeSourceIndex", "--build-cache").task(":coroutreeSourceIndex")?.outcome
        val indexFile = "build/coroutree/source-index.tsv"

        assertEquals(TaskOutcome.SUCCESS, project.index())

        project.file("src/main/java/demo/app/notes.txt", "not a source")
        File(project.dir, "src/main/java/demo/empty").mkdirs()
        assertEquals(TaskOutcome.UP_TO_DATE, project.index(), "nothing the index lists has changed")

        // The file stays where it is and declares another package.
        project.file("src/main/java/demo/app/Main.java", "package demo.moved;\n\npublic class Main {}")
        assertEquals(TaskOutcome.SUCCESS, project.index())
        assertEquals(listOf("M\t:\t${project.dir.path}", "F\tdemo.moved\tMain.java\tsrc/main/java/demo/app/Main.java"), project.lines(indexFile))

        val helper = project.file("src/main/java/demo/app/Helper.java", "package demo.app;\n\nclass Helper {}")
        assertEquals(TaskOutcome.SUCCESS, project.index())
        assertContains(project.lines(indexFile), "F\tdemo.app\tHelper.java\tsrc/main/java/demo/app/Helper.java")
        assertTrue(helper.delete())
        project.index()
        assertEquals(listOf("M\t:\t${project.dir.path}", "F\tdemo.moved\tMain.java\tsrc/main/java/demo/app/Main.java"), project.lines(indexFile))

        // A second checkout of the same sources. The index says where the files are on this machine, so what the
        // first one put into the build cache is of no use to it.
        val copy = TestProject(File(tempDir.canonicalFile, "copy"))
        project.dir.listFiles()!!.filter { it.name != "build" && it.name != ".gradle" }.forEach { it.copyRecursively(File(copy.dir, it.name), overwrite = true) }
        copy.index()
        assertEquals(listOf("M\t:\t${copy.dir.path}", "F\tdemo.moved\tMain.java\tsrc/main/java/demo/app/Main.java"), copy.lines(indexFile))
        assertFalse(project.dir.path in File(copy.dir, indexFile).readText())
    }
}
