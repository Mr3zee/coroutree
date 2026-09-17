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
}
