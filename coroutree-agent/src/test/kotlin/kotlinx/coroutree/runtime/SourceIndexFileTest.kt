package kotlinx.coroutree.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The source index as the Gradle plugin writes it, read by an agent that can be older or newer than the plugin.
 * The reader skips a record of an unknown kind or with the wrong number of fields and takes the rest as it is.
 */
internal class SourceIndexFileTest {
    @Test
    fun modulesAndTheirFilesAreReadInOrderAndConcatenatedIndexesAreOneIndex(@TempDir dir: Path) {
        assertEquals(
            listOf(
                "module :app at /work/my app",
                "  [com.acme] Main.kt -> src/main/kotlin/com/acme/Main.kt",
                "  [] Root.kt -> src/main/kotlin/Root.kt",
                "  [com.acme.größe] Größe.kt -> src/main/kotlin/Größe.kt",
                "module :lib at /work/lib",
                "module : at /work",
                "  [a] A.java -> src/main/java/a/A.java"
            ),
            read(
                dir,
                "M\t:app\t/work/my app\n" +
                    "F\tcom.acme\tMain.kt\tsrc/main/kotlin/com/acme/Main.kt\n" +
                    "F\t\tRoot.kt\tsrc/main/kotlin/Root.kt\n" +
                    "F\tcom.acme.größe\tGröße.kt\tsrc/main/kotlin/Größe.kt\n" +
                    "M\t:lib\t/work/lib\n" +
                    "M\t:\t/work\r\n" +
                    "F\ta\tA.java\tsrc/main/java/a/A.java\r\n"
            )
        )
        assertEquals(emptyList<String>(), read(dir, ""))
    }

    @Test
    fun whatIsNotARecordOfThisVersionIsSkippedAndTheRestStands(@TempDir dir: Path) {
        assertEquals(
            listOf(
                "module :app at /work/app",
                "  [com.acme] Kept.kt -> src/Kept.kt",
                "  [com.acme] AfterTheBadModule.kt -> src/After.kt"
            ),
            read(
                dir,
                "F\tcom.acme\tOrphan.kt\tsrc/Orphan.kt\n" + // a file before the first module belongs to no module
                    "\n" +
                    "# a comment, should a newer plugin write one\n" +
                    "M\t:app\t/work/app\n" +
                    "X\tfrom a newer plugin\twith\tfields\n" +
                    "F\tcom.acme\tKept.kt\tsrc/Kept.kt\n" +
                    "F\tcom.acme\tTooFew.kt\n" +
                    "F\tcom.acme\tTooMany.kt\tsrc/TooMany.kt\tand a field of a newer plugin\n" +
                    "F com.acme Spaces.kt src/Spaces.kt\n" +
                    "M\t:broken\n" + // not a module record, so the files after it belong to the module before it
                    "F\tcom.acme\tAfterTheBadModule.kt\tsrc/After.kt\n" +
                    "m\t:lower\t/work/lower\n"
            )
        )
    }

    @Test
    fun aMissingIndexIsAnErrorForTheCallerToReport(@TempDir dir: Path) {
        assertThrows<IOException> { SourceIndexFile.read(dir.resolve("nowhere.tsv").toFile()) }
    }

    private fun read(dir: Path, content: String): List<String> {
        val file = dir.resolve("index.tsv")
        Files.write(file, content.toByteArray(Charsets.UTF_8))
        return SourceIndexFile.read(file.toFile()).flatMap { module ->
            listOf("module ${module.path} at ${module.rootDir}") +
                module.files.map { "  [${it.packageName}] ${it.fileName} -> ${it.path}" }
        }
    }
}
