package kotlinx.coroutree.it

import kotlinx.coroutree.model.ContextElementKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the agent writes about objects of the program, where the golden corpus cannot show it: values that would differ
 * from run to run have no place in a trace (TRACE_FORMAT: "`toString()` of the element, identity hashes stripped").
 */
class DescribedValuesTest {
    /**
     * A dispatcher made of an executor prints itself as the executor does: `…ExecutorService@1b2c3d`, the identity
     * hash of an object *inside* the element. The golden is hand-written to what the format promises, and is not
     * rewritten by -PupdateGoldens.
     */
    @Test
    fun dispatchersMadeOfExecutorsAreDescribedWithoutIdentityHashes() {
        val run = runUnderAgent("samples.OwnThreadDispatchersKt")
        assertEquals(0, run.exitCode, run.output)
        GoldenTreeTest.check("OwnThreadDispatchers", run, mayUpdate = false)
    }

    /** The same promise over the golden corpus: its goldens could not be stable otherwise, but a value may hide in a node's full context. */
    @Test
    fun noContextValueOfTheCorpusCarriesAnIdentityHash() {
        val identity = Regex("@[0-9a-f]{5,8}\\b")
        for (sample in GoldenTreeTest.SAMPLES) {
            val snapshot = SampleRuns[sample].snapshot
            val values = snapshot.nodes.values.flatMap { node -> node.info.context.map { it.value } } +
                snapshot.events.flatMap { event -> event.contextDiff.flatMap { listOf(it.oldValue, it.newValue) } }
            val withHash = values.filter { identity.containsMatchIn(it) }.distinct()
            assertTrue(withHash.isEmpty(), "$sample: values that change from run to run: $withHash")
        }
    }

    /** What `HostileObjects` shows in its golden tree, and what it does not: the element kinds and the cut-off. */
    @Test
    fun hostileObjectsAreDescribedAndCutToSize() {
        val snapshot = SampleRuns["HostileObjects"].snapshot
        val hostile = snapshot.nodes.values.flatMap { it.info.context }.filter { it.kind == ContextElementKind.OTHER }
        assertEquals(setOf("samples.Hostile", "samples.HashBomb"), hostile.map { it.key }.toSet())
        assertTrue(hostile.all { it.value.length <= 201 }, hostile.map { it.value.length }.toString())
        val thrown = snapshot.events.mapNotNull { it.exception }.filter { it.className == "samples.Cursed" }
        assertTrue(thrown.isNotEmpty() && thrown.all { it.message.isEmpty() })
        assertEquals(1, thrown.map { it.identity }.toSet().size, "one exception, followed across its events")
    }
}
