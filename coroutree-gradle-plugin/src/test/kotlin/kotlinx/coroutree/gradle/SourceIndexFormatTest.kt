package kotlinx.coroutree.gradle

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceIndexFormatTest {
    @Test
    fun minimalPrefixesDropCoveredPackagesAndTheRoot() {
        assertEquals(
            listOf("com.acme", "org.other.deep"),
            SourceIndexFormat.minimalPrefixes(listOf("com.acme.util", "", "com.acme", "org.other.deep", "com.acme.util.io")),
        )
        // A common textual prefix is not a package prefix.
        assertEquals(listOf("com.acme", "com.acmeplus"), SourceIndexFormat.minimalPrefixes(listOf("com.acmeplus", "com.acme")))
        assertEquals(emptyList(), SourceIndexFormat.minimalPrefixes(listOf("")))
    }

    @Test
    fun mergeKeepsOrderAndDropsRepeatedModules() {
        val dir = Files.createTempDirectory("coroutree-index").toFile()
        try {
            val app = File(dir, "app.tsv").apply { writeText("M\t:app\t/p/app\nF\tcom.app\tMain.kt\tsrc/Main.kt\n") }
            val lib = File(dir, "lib.tsv").apply { writeText("M\t:lib\t/p/lib\nF\t\tRoot.java\tsrc/Root.java") } // no final newline
            val libAgain = File(dir, "lib2.tsv").apply { writeText("M\t:lib\t/p/lib\nF\tcom.lib\tDuplicate.kt\tsrc/Duplicate.kt\n") }
            val merged = SourceIndexFormat.merge(listOf(app, lib, File(dir, "missing.tsv"), libAgain))
            assertEquals(
                listOf("M\t:app\t/p/app", "F\tcom.app\tMain.kt\tsrc/Main.kt", "M\t:lib\t/p/lib", "F\t\tRoot.java\tsrc/Root.java"),
                merged,
            )
            assertEquals(setOf("com.app", ""), SourceIndexFormat.packages(merged))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun hostClassifier() {
        assertEquals("macos-arm64", CoroutreePlugin.hostClassifier("Mac OS X", "aarch64"))
        assertEquals("macos-x64", CoroutreePlugin.hostClassifier("Mac OS X", "x86_64"))
        assertEquals("linux-x64", CoroutreePlugin.hostClassifier("Linux", "amd64"))
        assertEquals("linux-arm64", CoroutreePlugin.hostClassifier("Linux", "aarch64"))
        assertEquals("windows-x64", CoroutreePlugin.hostClassifier("Windows 11", "amd64"))
        assertFailsWith<GradleException> { CoroutreePlugin.hostClassifier("Windows 11", "aarch64") }
        assertFailsWith<GradleException> { CoroutreePlugin.hostClassifier("SunOS", "sparcv9") }
    }

    // The agent reads a record by splitting its line at tabs and drops one with the wrong number of fields.
    @Test
    fun aRecordIsOneLineWithAFixedNumberOfFieldsWhateverTheNames() {
        val file = SourceIndexFormat.fileRecord("com.acme", "Odd\tName\n.kt", "src/main/kotlin/odd dir\r\n/Odd\tName.kt")
        assertEquals(1, file.lines().size, file)
        assertEquals(4, file.split('\t').size, file)
        val module = SourceIndexFormat.moduleRecord(":odd\tproject", "/work/with\ttab\nand break")
        assertEquals(1, module.lines().size, module)
        assertEquals(3, module.split('\t').size, module)

        // Everything else is the user's and arrives as it is.
        assertEquals("F\t\tÜber Datei #1 = a,b.kt\tsrc/main/kotlin/über dir/Über Datei #1 = a,b.kt", SourceIndexFormat.fileRecord("", "Über Datei #1 = a,b.kt", "src/main/kotlin/über dir/Über Datei #1 = a,b.kt"))
        assertEquals("M\t:a:b\tC:\\Users\\Jo Doe\\project", SourceIndexFormat.moduleRecord(":a:b", "C:\\Users\\Jo Doe\\project"))
    }

    @Test
    fun mergeKeepsNothingThatBelongsToNoModule() {
        val dir = Files.createTempDirectory("coroutree-index").toFile()
        try {
            val orphans = File(dir, "orphans.tsv").apply { writeText("F\tcom.orphan\tOrphan.kt\tsrc/Orphan.kt\n\nX\tfrom a newer plugin\nM\t:app\t/p/app\nF\tcom.app\tMain.kt\tsrc/Main.kt\n") }
            // A repeated module in the middle of a part: its files go, the module after it stays.
            val mixed = File(dir, "mixed.tsv").apply { writeText("M\t:app\t/p/app\nF\tcom.app\tAgain.kt\tsrc/Again.kt\nM\t:lib\t/p/lib\nF\tcom.lib\tLib.kt\tsrc/Lib.kt\n") }
            // Two builds of a composite may both have an ":app": the directory tells them apart.
            val other = File(dir, "other.tsv").apply { writeText("M\t:app\t/q/app\nF\torg.other\tMain.kt\tsrc/Main.kt\n") }
            val unicode = File(dir, "unicode.tsv").apply { writeText("M\t:ü\t/p/ü\nF\tcom.ü\tÜ.kt\tsrc/Ü.kt\n", Charsets.UTF_8) }
            assertEquals(
                listOf(
                    "M\t:app\t/p/app", "F\tcom.app\tMain.kt\tsrc/Main.kt",
                    "M\t:lib\t/p/lib", "F\tcom.lib\tLib.kt\tsrc/Lib.kt",
                    "M\t:app\t/q/app", "F\torg.other\tMain.kt\tsrc/Main.kt",
                    "M\t:ü\t/p/ü", "F\tcom.ü\tÜ.kt\tsrc/Ü.kt",
                ),
                SourceIndexFormat.merge(listOf(orphans, mixed, other, unicode, dir)),
            )
            assertEquals(emptyList(), SourceIndexFormat.merge(emptyList()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun includedPackagesOfAnIndexAreItsPackagesReducedToPrefixes() {
        val index = listOf(
            SourceIndexFormat.moduleRecord(":app", "/p/app"),
            SourceIndexFormat.fileRecord("com.acme.app", "Main.kt", "src/Main.kt"),
            SourceIndexFormat.fileRecord("", "Script.kt", "src/Script.kt"),
            SourceIndexFormat.moduleRecord(":lib", "/p/lib"),
            SourceIndexFormat.fileRecord("com.acme", "Lib.kt", "src/Lib.kt"),
            SourceIndexFormat.fileRecord("com.acme", "Lib2.kt", "src/Lib2.kt"),
            SourceIndexFormat.fileRecord("org.other", "Other.java", "src/Other.java"),
        )
        assertEquals(listOf("com.acme", "org.other"), SourceIndexFormat.minimalPrefixes(SourceIndexFormat.packages(index)))
        // Modules without sources, or with sources in the root package only: nothing counts as project code by prefix.
        assertEquals(emptyList(), SourceIndexFormat.minimalPrefixes(SourceIndexFormat.packages(index.take(1) + index[2])))
    }
}
