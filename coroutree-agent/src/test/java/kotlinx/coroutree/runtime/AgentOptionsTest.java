package kotlinx.coroutree.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent's options as a user writes them on {@code -javaagent:} by hand. Whatever is written there, the program
 * runs: a bad option is a problem to report once tracing is up, never an exception. (The form the Gradle plugin writes
 * is pinned end to end by AgentConfigTest of the integration tests; the options of execution control by PaceTest.)
 */
class AgentOptionsTest {
    @Test
    void withoutOptionsEverythingHasItsDefault() {
        for (String none : new String[] {null, ""}) {
            AgentConfig config = AgentConfig.parse(none);
            assertTrue(config.live);
            assertTrue(config.monitor);
            assertEquals(32, config.stackDepth);
            assertEquals("", config.sessionsDir, "no descriptor is written unless somebody says where");
            assertEquals(List.of(), config.includePackages);
            assertEquals(List.of(), config.excludePackages);
            assertEquals(List.of(), config.sourceIndex);
            assertEquals(List.of(), config.problems);
        }
    }

    @Test
    void aBadOptionIsAProblemToReportAndTheOthersStillCount() {
        AgentConfig config = AgentConfig.parse("build.id=b7,novalue,=orphan,stack.depth=many,unknown.key=1, task.path = :app:run ,live=false,monitor=no");
        assertEquals("b7", config.buildId);
        assertEquals(":app:run", config.taskPath, "blanks around keys and values are not part of them");
        assertFalse(config.live);
        assertFalse(config.monitor, "anything but true is false");
        assertEquals(32, config.stackDepth);
        assertEquals(3, config.problems.size(), "novalue, =orphan and stack.depth; an unknown key is a newer plugin's: " + config.problems);
        assertTrue(config.problems.get(0).contains("novalue"), config.problems.get(0));
        assertTrue(config.problems.get(1).contains("=orphan"), config.problems.get(1));
        assertTrue(config.problems.get(2).contains("stack.depth=many"), config.problems.get(2));

        for (String depth : new String[] {"0", "-5", "3.5", "", "99999999999"}) {
            AgentConfig bad = AgentConfig.parse("stack.depth=" + depth);
            assertEquals(32, bad.stackDepth, depth);
            assertEquals(1, bad.problems.size(), depth);
        }
        assertEquals(7, AgentConfig.parse("stack.depth= 7 ").stackDepth);
    }

    @Test
    void aConfigFileComesFirstAsAWholeOrAnywhereElseBelowTheInlineOptions(@TempDir Path dir) throws IOException {
        // The form of the Gradle plugin: nothing but the file, taken whole because a path may contain a comma.
        Path file = Files.createDirectories(dir.resolve("build,with=odd chars")).resolve("agent.properties");
        Files.writeString(file, "build.id=from-file\nstack.depth=5\ninclude=com.acme,org.acme\ntask.path=:app:t\u00e9st\n", StandardCharsets.UTF_8);
        AgentConfig whole = AgentConfig.parse("config=" + file);
        assertEquals(List.of(), whole.problems);
        assertEquals("from-file", whole.buildId);
        assertEquals(5, whole.stackDepth);
        assertEquals(List.of("com.acme", "org.acme"), whole.includePackages);
        assertEquals(":app:t\u00e9st", whole.taskPath, "the file is UTF-8");

        Path plain = dir.resolve("agent.properties");
        Files.writeString(plain, "build.id=from-file\nstack.depth=5\n");
        AgentConfig combined = AgentConfig.parse("stack.depth=9,config=" + plain);
        assertEquals(9, combined.stackDepth, "inline options override the file");
        assertEquals("from-file", combined.buildId);

        AgentConfig missing = AgentConfig.parse("config=" + dir.resolve("nowhere.properties"));
        assertEquals(1, missing.problems.size());
        assertTrue(missing.problems.get(0).contains("nowhere.properties"), missing.problems.get(0));
        assertEquals(32, missing.stackDepth, "and the agent runs on defaults");

        AgentConfig noIndex = AgentConfig.parse("source.index=" + dir.resolve("nowhere.tsv"));
        assertEquals(List.of(), noIndex.sourceIndex);
        assertTrue(noIndex.problems.get(0).contains("nowhere.tsv"), noIndex.problems.toString());
    }

    @Test
    void projectPackagesArePrefixesOfPackagesNotOfStrings() {
        // Inline, a list within the list of options is separated with ; or :, and patterns may be written the way people write them.
        AgentConfig config = AgentConfig.parse("include=com.acme.*;org.acme.: net.acme.. ,exclude=com.acme.generated;;*");
        assertEquals(List.of("com.acme", "org.acme", "net.acme"), config.includePackages);
        assertEquals(List.of("com.acme.generated"), config.excludePackages);
        assertTrue(config.hasProjectPackages());

        assertTrue(config.isProjectClass("com.acme.Main"));
        assertTrue(config.isProjectClass("com.acme.deep.down.Main$Inner"));
        assertTrue(config.isProjectClass("com.acme"), "the prefix itself");
        assertFalse(config.isProjectClass("com.acmecorp.Main"), "another package that starts with the same letters");
        assertFalse(config.isProjectClass("com.Main"));
        assertFalse(config.isProjectClass("com.acme.generated.Proto"), "exclude wins over include");
        assertTrue(config.isProjectClass("com.acme.generatedx.Proto"));
        assertTrue(config.isProjectClassInternalName("org/acme/web/Handler"));
        assertFalse(config.isProjectClassInternalName("org/acmeweb/Handler"));

        // Without include, everything that is not excluded is the project's for the origin of a node; but instrumenting
        // "project classes" would then mean every class there is, so no class is one for the transformer.
        AgentConfig all = AgentConfig.parse("exclude=org.lib");
        assertFalse(all.hasProjectPackages());
        assertTrue(all.isProjectClass("anything.At.All"));
        assertFalse(all.isProjectClass("org.lib.Thing"));
        assertFalse(all.isProjectClassInternalName("anything/At/All"));
    }

    @Test
    void theTraceFileIsTheOneGivenOrNamedAfterTheTaskAndTheProcess() throws IOException {
        File explicit = AgentConfig.parse("trace.file=out/my.ctrace,trace.dir=elsewhere,task.path=:app:run").resolveTraceFile(42);
        assertEquals(new File("out/my.ctrace").getAbsoluteFile(), explicit, "the exact file overrides the directory");

        String[][] names = {
            {":app:run", "app-run-42.ctrace"},
            {":", "trace-42.ctrace"},
            {"", "trace-42.ctrace"},
            {":app:integrationTest", "app-integrationTest-42.ctrace"},
            {":sub project:run/it now", "sub-project-run-it-now-42.ctrace"},
            {":a_b:c.d-e", "a_b-c.d-e-42.ctrace"},
        };
        for (String[] name : names) {
            File file = AgentConfig.parse("trace.dir=/traces/b1,task.path=" + name[0]).resolveTraceFile(42);
            assertEquals(new File("/traces/b1", name[1]).getAbsoluteFile(), file, "task " + name[0]);
        }
        File byDefault = AgentConfig.parse("").resolveTraceFile(7);
        assertTrue(byDefault.isAbsolute());
        assertEquals(new File("trace-7.ctrace").getCanonicalFile(), byDefault.getCanonicalFile(), "the working directory by default");
    }
}
