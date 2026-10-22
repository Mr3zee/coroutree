package kotlinx.coroutree.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The agent's options as a user writes them by hand on `-javaagent:`. Parsing never throws: a bad option goes into
 * `problems`, which the agent reports after tracing starts, and the program runs. The form that the Gradle plugin
 * writes is tested in `AgentConfigTest` of the integration tests, and the options of execution control in [PaceTest].
 */
internal class AgentOptionsTest {
    @Test
    fun withoutOptionsEverythingHasItsDefault() {
        for (none in listOf(null, "")) {
            val config = AgentConfig.parse(none)
            assertTrue(config.live)
            assertTrue(config.monitor)
            assertEquals(32, config.stackDepth)
            assertEquals(AgentConfig.defaultSessionsDir(), config.sessionsDir, "the descriptor goes where the GUI looks")
            assertEquals(emptyList<String>(), config.includePackages)
            assertEquals(emptyList<String>(), config.excludePackages)
            assertEquals(emptyList<Any>(), config.sourceIndex)
            assertEquals(emptyList<String>(), config.problems)
        }
    }

    @Test
    fun aBadOptionIsAProblemToReportAndTheOthersStillCount() {
        val config = AgentConfig.parse("build.id=b7,novalue,=orphan,stack.depth=many,unknown.key=1, task.path = :app:run ,live=false,monitor=no")
        assertEquals("b7", config.buildId)
        assertEquals(":app:run", config.taskPath, "blanks around keys and values are not part of them")
        assertFalse(config.live)
        assertFalse(config.monitor, "anything but true is false")
        assertEquals(32, config.stackDepth)
        assertEquals(3, config.problems.size, "novalue, =orphan and stack.depth; an unknown key is a newer plugin's: ${config.problems}")
        assertTrue("novalue" in config.problems[0], config.problems[0])
        assertTrue("=orphan" in config.problems[1], config.problems[1])
        assertTrue("stack.depth=many" in config.problems[2], config.problems[2])

        for (depth in listOf("0", "-5", "3.5", "", "99999999999")) {
            val bad = AgentConfig.parse("stack.depth=$depth")
            assertEquals(32, bad.stackDepth, depth)
            assertEquals(1, bad.problems.size, depth)
        }
        assertEquals(7, AgentConfig.parse("stack.depth= 7 ").stackDepth)
    }

    @Test
    fun aConfigFileComesFirstAsAWholeOrAnywhereElseBelowTheInlineOptions(@TempDir dir: Path) {
        // The plugin's form: `config=` alone, so the whole argument is the path, commas included.
        val file = Files.createDirectories(dir.resolve("build,with=odd chars")).resolve("agent.properties")
        Files.writeString(file, "build.id=from-file\nstack.depth=5\ninclude=com.acme,org.acme\ntask.path=:app:tést\n", Charsets.UTF_8)
        val whole = AgentConfig.parse("config=$file")
        assertEquals(emptyList<String>(), whole.problems)
        assertEquals("from-file", whole.buildId)
        assertEquals(5, whole.stackDepth)
        assertEquals(listOf("com.acme", "org.acme"), whole.includePackages)
        assertEquals(":app:tést", whole.taskPath, "the file is UTF-8")

        val plain = dir.resolve("agent.properties")
        Files.writeString(plain, "build.id=from-file\nstack.depth=5\n")
        val combined = AgentConfig.parse("stack.depth=9,config=$plain")
        assertEquals(9, combined.stackDepth, "inline options override the file")
        assertEquals("from-file", combined.buildId)

        val missing = AgentConfig.parse("config=${dir.resolve("nowhere.properties")}")
        assertEquals(1, missing.problems.size)
        assertTrue("nowhere.properties" in missing.problems[0], missing.problems[0])
        assertEquals(32, missing.stackDepth, "and the agent runs on defaults")

        val noIndex = AgentConfig.parse("source.index=${dir.resolve("nowhere.tsv")}")
        assertEquals(emptyList<Any>(), noIndex.sourceIndex)
        assertTrue("nowhere.tsv" in noIndex.problems[0], noIndex.problems.toString())
    }

    @Test
    fun projectPackagesArePrefixesOfPackagesNotOfStrings() {
        // An inline list is separated with `;` or `:`, because `,` separates the options. A prefix can end in `.` or `*`.
        val config = AgentConfig.parse("include=com.acme.*;org.acme.: net.acme.. ,exclude=com.acme.generated;;*")
        assertEquals(listOf("com.acme", "org.acme", "net.acme"), config.includePackages)
        assertEquals(listOf("com.acme.generated"), config.excludePackages)
        assertTrue(config.hasProjectPackages())

        assertTrue(config.isProjectClass("com.acme.Main"))
        assertTrue(config.isProjectClass("com.acme.deep.down.Main\$Inner"))
        assertTrue(config.isProjectClass("com.acme"), "the prefix itself")
        assertFalse(config.isProjectClass("com.acmecorp.Main"), "another package that starts with the same letters")
        assertFalse(config.isProjectClass("com.Main"))
        assertFalse(config.isProjectClass("com.acme.generated.Proto"), "exclude wins over include")
        assertTrue(config.isProjectClass("com.acme.generatedx.Proto"))
        assertTrue(config.isProjectClassInternalName("org/acme/web/Handler"))
        assertFalse(config.isProjectClassInternalName("org/acmeweb/Handler"))

        // Without `include`, every class that isn't excluded is the project's for the origin of a node. None is for the
        // transformer, because it would otherwise instrument every class.
        val all = AgentConfig.parse("exclude=org.lib")
        assertFalse(all.hasProjectPackages())
        assertTrue(all.isProjectClass("anything.At.All"))
        assertFalse(all.isProjectClass("org.lib.Thing"))
        assertFalse(all.isProjectClassInternalName("anything/At/All"))
    }

    @Test
    fun theTraceFileIsTheOneGivenOrNamedAfterTheTaskAndTheProcess() {
        val explicit = AgentConfig.parse("trace.file=out/my.ctrace,trace.dir=elsewhere,task.path=:app:run").resolveTraceFile(42)
        assertEquals(File("out/my.ctrace").absoluteFile, explicit, "the exact file overrides the directory")

        val names = listOf(
            ":app:run" to "app-run-42.ctrace",
            ":" to "trace-42.ctrace",
            "" to "trace-42.ctrace",
            ":app:integrationTest" to "app-integrationTest-42.ctrace",
            ":sub project:run/it now" to "sub-project-run-it-now-42.ctrace",
            ":a_b:c.d-e" to "a_b-c.d-e-42.ctrace",
        )
        for ((task, name) in names) {
            val file = AgentConfig.parse("trace.dir=/traces/b1,task.path=$task").resolveTraceFile(42)
            assertEquals(File("/traces/b1", name).absoluteFile, file, "task $task")
        }
        val byDefault = AgentConfig.parse("").resolveTraceFile(7)
        assertTrue(byDefault.isAbsolute)
        assertEquals(File("trace-7.ctrace").canonicalFile, byDefault.canonicalFile, "the working directory by default")
    }
}
