package kotlinx.coroutree.model

import kotlinx.coroutree.model.tree.TreeRenderer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The golden format: "the tree, node states, context diffs and, per node, its events in order", and nothing "that
 * differs between two runs of the same deterministic program: ids, timestamps, sequence numbers, thread names in
 * `resumed on …`".
 */
class TreeRendererTest {
    private val boom = ExceptionInfo("java.lang.IllegalStateException", "boom")

    private val program = script {
        frame(1, "demo.MainKt", "main", "Main.kt", 12)
        thread(1, "main")
        launched(2, parent = 1, construct = "runBlocking")
        launched(3, parent = 2, name = "worker", site = 1)
        event(3, EventKind.CONTEXT_CHANGED) { copy(contextDiff = listOf(ContextChange(ContextElementKind.NAME, "CoroutineName", newValue = "worker", added = true))) }
        event(3, EventKind.DISPATCHER_CHANGED) {
            copy(contextDiff = listOf(ContextChange(ContextElementKind.DISPATCHER, "Dispatcher", "BlockingEventLoop", "Dispatchers.Default")))
        }
        launched(4, parent = 0, construct = "Job()", kind = NodeKind.SCOPE, creator = 3)
        event(3, EventKind.RESUMED, thread = 1)
        event(4, EventKind.CANCELLATION_REQUESTED, other = 3)
        event(4, EventKind.CANCELLING) { copy(exception = ExceptionInfo("kotlinx.coroutines.JobCancellationException", "Job was cancelled", cancellation = true)) }
        finished(4, NodeState.CANCELLED)
        event(1, EventKind.THREAD_BLOCKED, other = 3) { copy(blockReason = BlockReason.SLEEP) }
        event(1, EventKind.THREAD_UNBLOCKED, other = 3)
        event(3, EventKind.EXCEPTION_THROWN) { copy(exception = boom) }
        event(2, EventKind.EXCEPTION_PROPAGATED, other = 3) { copy(exception = boom) }
        finished(3, NodeState.FAILED)
    }

    @Test
    fun rendersTheTreeWithStatesContextDiffsEventsAndLinks() {
        // Scopes rethrow to their caller: the exception is "propagated" on both ends, thrown once.
        assertEquals(
            """
            Thread "main" [active]
              - blocked (sleep) in launch "worker"
              - unblocked
              runBlocking [active]
                - IllegalStateException propagated from launch "worker"
                launch "worker" @ Main.kt:12 [failed] {+CoroutineName: worker, Dispatcher: BlockingEventLoop → Dispatchers.Default}
                  - resumed
                  - requested cancellation of Job()
                  - blocked its thread (sleep)
                  - unblocked its thread
                  - threw IllegalStateException: boom
                  - IllegalStateException propagated to runBlocking
                  - failed
                  ~ launches Job()
                  ~ cancels Job()
            Job() [cancelled]
              - cancellation requested by launch "worker"
              - cancelling: JobCancellationException: Job was cancelled
              - cancelled

            """.trimIndent(),
            TreeRenderer().render(storeOf(program).snapshot()),
        )
    }

    @Test
    fun aSubtreeThatIsLeftOutTakesItsLinksWithItAndNothingElse() {
        // "Nodes for which this returns false are omitted together with their subtrees."
        val snapshot = storeOf(program).snapshot()
        assertEquals(
            """
            Thread "main" [active]
              - blocked (sleep) in launch "worker"
              - unblocked
              runBlocking [active]
                - IllegalStateException propagated from launch "worker"
                launch "worker" @ Main.kt:12 [failed] {+CoroutineName: worker, Dispatcher: BlockingEventLoop → Dispatchers.Default}
                  - resumed
                  - requested cancellation of Job()
                  - blocked its thread (sleep)
                  - unblocked its thread
                  - threw IllegalStateException: boom
                  - IllegalStateException propagated to runBlocking
                  - failed

            """.trimIndent(),
            TreeRenderer(includeNode = { it.info.construct != "Job()" }).render(snapshot),
        )
        assertEquals(
            """
            Thread "main" [active]
              - blocked (sleep) in launch "worker"
              - unblocked
            Job() [cancelled]
              - cancellation requested by launch "worker"
              - cancelling: JobCancellationException: Job was cancelled
              - cancelled

            """.trimIndent(),
            TreeRenderer(includeNode = { it.info.construct != "runBlocking" }).render(snapshot),
        )
    }

    @Test
    fun anEventThatIsLeftOutIsLeftOutOnBothOfItsNodes() {
        // How the goldens keep monitor contention out: by the event, wherever it is shown.
        val quiet = TreeRenderer(includeEvent = { it.kind != EventKind.THREAD_BLOCKED && it.kind != EventKind.THREAD_UNBLOCKED && it.kind != EventKind.RESUMED })
        assertEquals(
            """
            Thread "main" [active]
              runBlocking [active]
                - IllegalStateException propagated from launch "worker"
                launch "worker" @ Main.kt:12 [failed] {+CoroutineName: worker, Dispatcher: BlockingEventLoop → Dispatchers.Default}
                  - requested cancellation of Job()
                  - threw IllegalStateException: boom
                  - IllegalStateException propagated to runBlocking
                  - failed
                  ~ launches Job()
                  ~ cancels Job()
            Job() [cancelled]
              - cancellation requested by launch "worker"
              - cancelling: JobCancellationException: Job was cancelled
              - cancelled

            """.trimIndent(),
            quiet.render(storeOf(program).snapshot()),
        )
    }

    @Test
    fun twoRunsOfOneProgramRenderTheSame() {
        // What a run cannot repeat: the ids it hands out, the clock, how the events of two coroutines interleave,
        // which worker picked a coroutine up.
        fun run(base: Long, clock: Long, worker: Long, interleave: Boolean) = script {
            frame(1, "demo.MainKt", "main", "Main.kt", 12)
            val (main, scope, a, b) = listOf(base + 1, base + 2, base + 3, base + 4)
            var time = clock
            fun step(node: Long, kind: EventKind, thread: Long = 0, configure: Event.() -> Event = { this }) {
                time += clock
                event(node, kind, thread = thread) { copy(timeNanos = time, heldNanos = clock).configure() }
            }
            thread(main, "main")
            launched(scope, parent = main, construct = "runBlocking", site = 1)
            launched(a, parent = scope, name = "a", creator = scope)
            launched(b, parent = scope, name = "b", creator = scope)
            val ofA: List<() -> Unit> = listOf(
                { step(a, EventKind.RESUMED, thread = worker) },
                { step(a, EventKind.SUSPENDED) },
                { step(a, EventKind.RESUMED, thread = main) },
                { step(a, EventKind.FINISHED) { copy(finalState = NodeState.COMPLETED) } },
            )
            val ofB: List<() -> Unit> = listOf(
                { step(b, EventKind.RESUMED, thread = main) },
                { step(b, EventKind.EXCEPTION_THROWN) { copy(exception = boom.copy(identity = clock.toInt())) } },
                { step(b, EventKind.FINISHED) { copy(finalState = NodeState.FAILED) } },
            )
            if (interleave) {
                for (i in 0 until 4) {
                    ofB.getOrNull(i)?.invoke()
                    ofA[i]()
                }
            } else {
                ofA.forEach { it() }
                ofB.forEach { it() }
            }
        }

        val first = TreeRenderer().render(storeOf(run(base = 0, clock = 10, worker = 50, interleave = false)).snapshot())
        val second = TreeRenderer().render(storeOf(run(base = 4000, clock = 977, worker = 4777, interleave = true)).snapshot())
        assertEquals(
            """
            Thread "main" [active]
              runBlocking @ Main.kt:12 [active]
                launch "a" [completed]
                  - resumed
                  - suspended
                  - resumed
                  - completed
                launch "b" [failed]
                  - resumed
                  - threw IllegalStateException: boom
                  - failed

            """.trimIndent(),
            first,
        )
        assertEquals(first, second)
    }

    @Test
    fun aTraceWithoutNodesRendersAsNothing() {
        assertEquals("", TreeRenderer().render(storeOf(script { frame(1, "A") }).snapshot()))
    }
}
