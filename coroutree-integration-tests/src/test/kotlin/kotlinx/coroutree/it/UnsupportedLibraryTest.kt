package kotlinx.coroutree.it

import kotlinx.coroutree.model.Diagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agent hooks into private parts of kotlinx.coroutines. When a version comes along where they are gone, the worst
 * outcome is a trace that looks fine and is wrong; the agent has to say loudly that it cannot be trusted.
 */
class UnsupportedLibraryTest {
    @Test
    fun missingHooksAreReportedLoudlyAndTheProgramStillRuns() {
        val run = runUnderAgent("fixtures.UsesFakeCoroutines", classpath = TestEnvironment.fakeCoroutinesClasses)
        assertEquals(0, run.exitCode, run.output)
        assertTrue("ran to the end" in run.output, run.output)

        val errors = run.snapshot.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }.map { it.message }
        val error = errors.singleOrNull() ?: error("expected one error, got $errors")
        assertTrue(error.startsWith("Unsupported version of kotlinx.coroutines.JobSupport"), error)
        for (method in listOf("parentCancelled", "notifyCancelling", "makeCompletingOnce", "completeStateFinalization")) {
            assertTrue(method in error, "$method is not named in: $error")
        }
        assertTrue("cancel(" !in error, "cancel is present in the fake and must not be reported: $error")
        assertTrue(error in run.output, "the error also goes to stderr:\n${run.output}")

        val warnings = run.snapshot.diagnostics.filter { it.severity == Diagnostic.Severity.WARNING }.map { it.message }
        assertTrue(warnings.any { "version of kotlinx.coroutines" in it }, warnings.toString())
    }
}
