package kotlinx.coroutree.model

import kotlinx.coroutree.model.tree.SourceLocation
import kotlinx.coroutree.model.tree.SourceResolver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DESIGN §12, source index: "Keyed by (package, source file name) rather than by class: that is what a stack frame
 * carries, and both languages put a class in its file's package."
 */
class SourceResolverTest {
    private val index = SourceIndex(
        listOf(
            SourceModule(
                ":app", "/work/app",
                listOf(
                    SourceFile("demo", "Main.kt", "src/main/kotlin/demo/Main.kt"),
                    SourceFile("demo", "Main.kt", "src/test/kotlin/demo/Main.kt"),
                    SourceFile("demo.util", "Main.kt", "src/main/kotlin/demo/util/Main.kt"),
                    SourceFile("", "Script.kt", "src/main/kotlin/Script.kt"),
                ),
            ),
            SourceModule(
                ":lib", "/work/lib",
                listOf(
                    SourceFile("demo", "Main.kt", "src/main/kotlin/demo/Main.kt"),
                    SourceFile("lib", "Lib.java", "src/main/java/lib/Lib.java"),
                ),
            ),
        ),
    )
    private val resolver = SourceResolver(index)

    private fun location(module: String, root: String, path: String, line: Int) = SourceLocation(module, path, File(root, path).path, line)

    @Test
    fun aFrameFindsItsFileByThePackageOfItsClassAndTheFileName() {
        val main = location(":app", "/work/app", "src/main/kotlin/demo/Main.kt", 12)
        assertEquals(main, resolver.resolve(StackFrameDef(1, "demo.MainKt", "main", "Main.kt", 12)))
        // Whatever the class is called: a file facade, a class declared in the file, a lambda, a nested class.
        for (className in listOf("demo.Main", "demo.SomethingElse", "demo.MainKt\$main\$1", "demo.Outer\$Inner\$Companion")) {
            assertEquals(main, resolver.resolve(StackFrameDef(1, className, "invoke", "Main.kt", 12)), className)
        }
        assertEquals(
            location(":app", "/work/app", "src/main/kotlin/demo/util/Main.kt", 3),
            resolver.resolve(StackFrameDef(1, "demo.util.MainKt", "helper", "Main.kt", 3)),
            "the same file name in another package is another file",
        )
        assertEquals(
            location(":app", "/work/app", "src/main/kotlin/Script.kt", 1),
            resolver.resolve(StackFrameDef(1, "ScriptKt", "main", "Script.kt", 1)),
            "a class without a package",
        )
        assertEquals(location(":lib", "/work/lib", "src/main/java/lib/Lib.java", 40), resolver.resolve(StackFrameDef(1, "lib.Lib", "call", "Lib.java", 40)))
        assertEquals(0, resolver.resolve(StackFrameDef(1, "lib.Lib", "call", "Lib.java"))!!.line, "0: the line is unknown, the file is not")
    }

    @Test
    fun theBodyOfAnInlineFunctionIsFoundInTheFileThatDeclaresIt() {
        // TRACE_FORMAT, "Inlined code": "class_name is the class that declares the inline function (so that package
        // and file_name find the file like for any frame), line the line in that file".
        val body = StackFrameDef(1, "demo.util.MainKt", "retry", "Main.kt", 7, inlined = true)
        assertEquals(location(":app", "/work/app", "src/main/kotlin/demo/util/Main.kt", 7), resolver.resolve(body))
        assertEquals(location(":app", "/work/app", "src/main/kotlin/demo/util/Main.kt", 7), resolver.resolve(body.copy(methodName = "")))
    }

    @Test
    fun aFileThatExistsTwiceIsTheOneListedFirst() {
        // Main before test, the first module before the second: a frame cannot tell them apart.
        val found = resolver.resolve(StackFrameDef(1, "demo.MainKt", "main", "Main.kt", 1))!!
        assertEquals(":app", found.module)
        assertEquals("src/main/kotlin/demo/Main.kt", found.path)
    }

    @Test
    fun codeThatIsNotInTheIndexHasNoLocation() {
        val unknown = listOf(
            StackFrameDef(1, "other.MainKt", "main", "Main.kt", 1),          // right file name, wrong package
            StackFrameDef(1, "MainKt", "main", "Main.kt", 1),                // no package
            StackFrameDef(1, "demo.MainKt", "main", "Other.kt", 1),
            StackFrameDef(1, "demo.MainKt", "main", "", 1),                  // compiled without debug information
            StackFrameDef(1, "demo.util.deep.MainKt", "main", "Main.kt", 1),
            StackFrameDef(1, "kotlinx.coroutines.DelayKt", "delay", "Delay.kt", 1),
            StackFrameDef(1, "Script", "main", "Script.java", 1),
        )
        for (frame in unknown) {
            assertNull(resolver.resolve(frame), frame.toString())
            assertFalse(resolver.isKnown(frame))
        }
        assertTrue(resolver.isKnown(StackFrameDef(1, "demo.MainKt", "main", "Main.kt", 1)))
        assertNull(SourceResolver.EMPTY.resolve(StackFrameDef(1, "demo.MainKt", "main", "Main.kt", 1)))
    }

    @Test
    fun aSnapshotResolvesWithTheIndexOfItsHeader() {
        val frame = StackFrameDef(1, "demo.MainKt", "main", "Main.kt", 12)
        val before = storeOf(script { frame(1, "demo.MainKt", "main", "Main.kt", 12) })
        assertNull(before.snapshot().sources.resolve(frame), "no header, no index")
        val snapshot = storeOf(script { header(TraceHeader(formatVersion = 1, sourceIndex = index)) }).snapshot()
        assertEquals(":app", snapshot.sources.resolve(frame)?.module)
    }
}
