package kotlinx.coroutree.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The source index as the Gradle plugin writes it, read by an agent that may be older or newer than the plugin:
 * records it does not know, or that are not what they should be, are skipped; the rest is taken as it is.
 */
class SourceIndexFileTest {
    private static List<String> read(Path dir, String content) throws IOException {
        Path file = dir.resolve("index.tsv");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        List<String> result = new ArrayList<>();
        for (SourceIndexFile.Module module : SourceIndexFile.read(file.toFile())) {
            result.add("module " + module.path + " at " + module.rootDir);
            for (SourceIndexFile.Entry entry : module.files) result.add("  [" + entry.packageName + "] " + entry.fileName + " -> " + entry.path);
        }
        return result;
    }

    @Test
    void modulesAndTheirFilesAreReadInOrderAndConcatenatedIndexesAreOneIndex(@TempDir Path dir) throws IOException {
        assertEquals(
            List.of(
                "module :app at /work/my app",
                "  [com.acme] Main.kt -> src/main/kotlin/com/acme/Main.kt",
                "  [] Root.kt -> src/main/kotlin/Root.kt",
                "  [com.acme.größe] Größe.kt -> src/main/kotlin/Größe.kt",
                "module :lib at /work/lib",
                "module : at /work",
                "  [a] A.java -> src/main/java/a/A.java"),
            read(dir, "M\t:app\t/work/my app\n"
                + "F\tcom.acme\tMain.kt\tsrc/main/kotlin/com/acme/Main.kt\n"
                + "F\t\tRoot.kt\tsrc/main/kotlin/Root.kt\n"
                + "F\tcom.acme.größe\tGröße.kt\tsrc/main/kotlin/Größe.kt\n"
                + "M\t:lib\t/work/lib\n"
                + "M\t:\t/work\r\n"
                + "F\ta\tA.java\tsrc/main/java/a/A.java\r\n"));
        assertEquals(List.of(), read(dir, ""));
    }

    @Test
    void whatIsNotARecordOfThisVersionIsSkippedAndTheRestStands(@TempDir Path dir) throws IOException {
        assertEquals(
            List.of(
                "module :app at /work/app",
                "  [com.acme] Kept.kt -> src/Kept.kt",
                "  [com.acme] AfterTheBadModule.kt -> src/After.kt"),
            read(dir, "F\tcom.acme\tOrphan.kt\tsrc/Orphan.kt\n"           // a file before any module has nowhere to go
                + "\n"
                + "# a comment, should a newer plugin write one\n"
                + "M\t:app\t/work/app\n"
                + "X\tfrom a newer plugin\twith\tfields\n"
                + "F\tcom.acme\tKept.kt\tsrc/Kept.kt\n"
                + "F\tcom.acme\tTooFew.kt\n"
                + "F\tcom.acme\tTooMany.kt\tsrc/TooMany.kt\tand a field of a newer plugin\n"
                + "F com.acme Spaces.kt src/Spaces.kt\n"
                + "M\t:broken\n"                                          // not a module: files after it stay with the last one that was
                + "F\tcom.acme\tAfterTheBadModule.kt\tsrc/After.kt\n"
                + "m\t:lower\t/work/lower\n"));
    }

    @Test
    void aMissingIndexIsAnErrorForTheCallerToReport(@TempDir Path dir) {
        assertThrows(IOException.class, () -> SourceIndexFile.read(dir.resolve("nowhere.tsv").toFile()));
    }
}
