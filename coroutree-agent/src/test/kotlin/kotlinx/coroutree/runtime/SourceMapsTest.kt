package kotlinx.coroutree.runtime

import kotlinx.coroutree.runtime.SourceMaps.Inlined
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Source maps as kotlinc 2.4 writes them (taken from the InlineFunctions sample), and the spellings of older compilers. */
internal class SourceMapsTest {
    @Test
    fun aFrameInInlinedCodeIsTheBodyAndTheCallSite() {
        SourceMaps.register(
            "com.acme.MainKt\$main$1",
            "SMAP\nMain.kt\nKotlin\n" + BODY + "*S KotlinDebug\n*F\n" + OWN + "*L\n14#1:36,3\n15#1:39\n18#1:52,10\n*E\n",
            mapOf(
                37 to Inlined(arrayOf("retry"), intArrayOf(14)),
                // Util.kt:117 of withLock, which retry (called at Main.kt:18) calls at Util.kt:24.
                53 to Inlined(arrayOf("withLock", "retry"), intArrayOf(37, 18))
            )
        )
        assertEquals(listOf("com.acme.MainKt\$main$1.invokeSuspend(Main.kt:20)"), frames("com.acme.MainKt\$main$1", 20), "the class's own lines")
        assertEquals(listOf("inline com.acme.util.UtilKt.retry(Util.kt:24)", "com.acme.MainKt\$main$1.invokeSuspend(Main.kt:14)"), frames("com.acme.MainKt\$main$1", 37))
        assertEquals(listOf("inline com.acme.util.UtilKt.(Util.kt:28)", "com.acme.MainKt\$main$1.invokeSuspend(Main.kt:15)"), frames("com.acme.MainKt\$main$1", 39), "no name known")
        assertEquals(listOf("inline kotlinx.coroutines.sync.MutexKt.(Mutex.kt:126)", "com.acme.MainKt\$main$1.invokeSuspend(Main.kt:18)"), frames("com.acme.MainKt\$main$1", 61))
        assertEquals(
            listOf(
                "inline kotlinx.coroutines.sync.MutexKt.withLock(Mutex.kt:118)",
                "inline com.acme.util.UtilKt.retry(Util.kt:24)",
                "com.acme.MainKt\$main$1.invokeSuspend(Main.kt:18)"
            ),
            frames("com.acme.MainKt\$main$1", 53), "inlined through two functions"
        )
        assertEquals(listOf("com.acme.MainKt\$main$1.invokeSuspend(Main.kt:62)"), frames("com.acme.MainKt\$main$1", 62), "past every range")
        assertEquals(listOf("com.acme.Other.invokeSuspend(Main.kt:37)"), frames("com.acme.Other", 37), "a class without a map")
    }

    @Test
    fun olderCompilersSpellCallSiteRangesLikeBodyRangesAndEndEveryStratum() {
        SourceMaps.register(
            "com.acme.Old",
            "SMAP\nMain.kt\nKotlin\n" + BODY.replace("MainKt\$main$1", "Old") + "*E\n*S KotlinDebug\n*F\n" +
                OWN.replace("MainKt\$main$1", "Old") + "*L\n14#1,3:36\n*E\n",
            null
        )
        assertEquals(listOf("inline com.acme.util.UtilKt.(Util.kt:25)", "com.acme.Old.invokeSuspend(Main.kt:14)"), frames("com.acme.Old", 38))
    }

    @Test
    fun aLambdaInACopyOfAnInlineFunctionsClassIsOneFrameWhereItWasWritten() {
        // The copy keeps the name of the file it was copied from; its own lines are another package's.
        SourceMaps.register(
            "com.acme.MainKt\$main$\$inlined\$later$1",
            "SMAP\nUtil.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Util.kt\ncom/acme/util/UtilKt\$later$1\n" +
                "+ 2 Main.kt\ncom/acme/MainKt\n*L\n1#1,35:1\n14#2:36\n*E\n",
            null
        )
        assertEquals(listOf("com.acme.MainKt.invokeSuspend(Main.kt:14)"), frames("com.acme.MainKt\$main$\$inlined\$later$1", 36))
        assertEquals(listOf("com.acme.util.UtilKt\$later$1.invokeSuspend(Util.kt:30)"), frames("com.acme.MainKt\$main$\$inlined\$later$1", 30))
    }

    @Test
    fun whatIsNotAMapIsNoMap() {
        SourceMaps.register("com.acme.Broken", "SMAP\nMain.kt\nKotlin\n*S Kotlin\n*F\n+ one Main.kt\n*L\nx#1:y\n", null)
        SourceMaps.register("com.acme.Jsp", "SMAP\nmain_jsp.java\nJSP\n*S JSP\n*F\n1 main.jsp\n*L\n1#1,5:40\n*E\n", null)
        SourceMaps.register("com.acme.Empty", "", null)
        assertEquals(listOf("com.acme.Broken.invokeSuspend(Main.kt:40)"), frames("com.acme.Broken", 40))
        assertEquals(listOf("com.acme.Jsp.invokeSuspend(Main.kt:40)"), frames("com.acme.Jsp", 40))
        assertEquals(listOf("com.acme.Empty.invokeSuspend(Main.kt:40)"), frames("com.acme.Empty", 40))
    }

    @Test
    fun theMainThatStartsSuspendFunMainIsOnTheLineOfTheOther() {
        SourceMaps.registerSuspendMain("com.acme.ServerKt", 16)
        val frames = ArrayList<StackFrameRef>()
        SourceMaps.add(frames, "com.acme.ServerKt", "main", "Server.kt", -1)
        SourceMaps.add(frames, "com.acme.ServerKt", "helper", "Server.kt", -1)
        assertEquals(16, frames[0].line)
        assertEquals(0, frames[1].line)
    }

    companion object {
        private const val OWN = "+ 1 Main.kt\ncom/acme/MainKt\$main$1\n"
        private const val FILES = OWN + "+ 2 Util.kt\ncom/acme/util/UtilKt\n+ 3 Mutex.kt\nkotlinx/coroutines/sync/MutexKt\n"
        private const val BODY = "*S Kotlin\n*F\n" + FILES + "*L\n1#1,35:1\n23#2,3:36\n28#2:39\n117#3,10:52\n"

        private fun frames(className: String, line: Int): List<String> =
            RuntimeProbe.logicalFrames(className, "invokeSuspend", "Main.kt", line)
    }
}
