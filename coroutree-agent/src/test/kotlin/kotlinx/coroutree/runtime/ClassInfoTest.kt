package kotlinx.coroutree.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The kind and the behavior of the node that a job becomes, by the job's class (DESIGN §2.1), read from the class
 * hierarchy of the kotlinx.coroutines that the project is built with. The classes are the library's internal ones,
 * under the names that `ClassInfo` matches.
 */
internal class ClassInfoTest {
    @Test
    fun theClassOfAJobSaysWhatKindOfNodeItIsAndHowItBehaves() {
        val expected = listOf(
            // Runs code of its own, concurrently with its parent.
            "kotlinx.coroutines.StandaloneCoroutine" to "COROUTINE launch",
            "kotlinx.coroutines.LazyStandaloneCoroutine" to "COROUTINE launch",
            "kotlinx.coroutines.DeferredCoroutine" to "COROUTINE async, holds its exception",
            "kotlinx.coroutines.LazyDeferredCoroutine" to "COROUTINE async, holds its exception",
            "kotlinx.coroutines.BlockingCoroutine" to "COROUTINE runBlocking, blocks its thread",
            // Runs in its caller's place, on the caller's thread until it suspends, and rethrows its failure to the caller.
            "kotlinx.coroutines.internal.ScopeCoroutine" to "SCOPE coroutineScope, throws at its caller, starts undispatched",
            "kotlinx.coroutines.SupervisorCoroutine" to "SCOPE supervisorScope, throws at its caller, starts undispatched",
            "kotlinx.coroutines.TimeoutCoroutine" to "SCOPE withTimeout, throws at its caller, starts undispatched",
            // withContext to another dispatcher is dispatched like any coroutine. Otherwise it starts in place.
            "kotlinx.coroutines.DispatchedCoroutine" to "CONTEXT_CHANGE withContext, throws at its caller",
            "kotlinx.coroutines.UndispatchedCoroutine" to "CONTEXT_CHANGE withContext, throws at its caller, starts undispatched",
            // A plain Job runs nothing. It only holds children.
            "kotlinx.coroutines.JobImpl" to "SCOPE Job()",
            "kotlinx.coroutines.SupervisorJobImpl" to "SCOPE SupervisorJob()",
            // A class whose constructor isn't hooked: the node is found when something happens to it and named after the class.
            "kotlinx.coroutines.CompletableDeferredImpl" to "SCOPE CompletableDeferredImpl, construction not seen",
        )
        for ((className, description) in expected) assertEquals(description, info(className), className)
    }

    private fun info(className: String): String {
        val info = ClassInfo.of(Class.forName(className, false, ClassInfoTest::class.java.classLoader))
        val kind = when (info.kind) {
            Wire.KIND_COROUTINE -> "COROUTINE"
            Wire.KIND_SCOPE -> "SCOPE"
            Wire.KIND_CONTEXT_CHANGE -> "CONTEXT_CHANGE"
            else -> "kind ${info.kind}"
        }
        return "$kind ${info.construct}" +
            (if (info.scoped) ", throws at its caller" else "") +
            (if (info.startsUndispatched) ", starts undispatched" else "") +
            (if (info.deferred) ", holds its exception" else "") +
            (if (info.blocking) ", blocks its thread" else "") +
            (if (info.constructionHooked) "" else ", construction not seen")
    }
}
