package kotlinx.coroutree.it

import kotlinx.coroutree.model.ContextElementKind
import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.HandledBy
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The shared-driver pattern (the `SharedDriver` sample): a chain of interceptors that are all handed one continuation, the
 * driver's, and whose callers are saved on a stack by `proceed()` and resumed one after the other. The golden tree shows
 * what each coroutine did; here: that the driver is not a coroutine of its own, that every event of an interceptor is the
 * event of the coroutine the chain runs for, and that suspending and resuming through the saved callers stays balanced.
 */
class SharedDriverTest {
    private val run = SampleRuns["SharedDriver"]
    private val snapshot = run.snapshot

    private val lines: Map<String, String> = run.output.lines()
        .filter { ": " in it && !it.startsWith("coroutree") }
        .associate { it.substringBefore(": ") to it.substringAfter(": ") }

    private fun node(name: String): NodeSnapshot = snapshot.nodes.values.single { it.info.name == name && it.info.kind != NodeKind.THREAD }
    private fun thread(name: String): NodeSnapshot = snapshot.nodes.values.single { it.info.name == name && it.info.kind == NodeKind.THREAD }
    private fun childrenOf(node: NodeSnapshot): List<NodeSnapshot> = node.children.map { snapshot.nodes.getValue(it) }

    /** What the node did on its own account, as kinds. */
    private fun NodeSnapshot.story(): List<EventKind> = events.filter { it.nodeId == id }
        .map { it.kind }
        .filter { it == EventKind.RESUMED || it == EventKind.SUSPENDED || it == EventKind.FINISHED }

    private fun NodeSnapshot.own(kind: EventKind): List<Event> = events.filter { it.nodeId == id && it.kind == kind }

    private fun threadName(event: Event): String = snapshot.nodes.getValue(event.threadId).info.name

    /** The class of the innermost frame of the project in the event's stack. */
    private fun siteOf(event: Event): String = event.stack.map { snapshot.frame(it)!! }.first { it.className.startsWith("samples.") }.className

    /** A coroutine is suspended and resumed in turn, and is not left suspended: what the driver must not upset. */
    private fun assertBalanced(node: NodeSnapshot) {
        val switches = node.story().filter { it != EventKind.FINISHED }
        switches.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b, "${node.info.name}: ${node.story()}") }
        assertTrue(switches.isEmpty() || switches.last() == EventKind.RESUMED, "${node.info.name} is left suspended: ${node.story()}")
        assertEquals(NodeState.COMPLETED, node.state, node.info.name)
        assertEquals(1, node.own(EventKind.FINISHED).size, node.info.name)
    }

    @Test
    fun everyChainRunsToItsEndThroughAllItsSavedCallers() {
        assertEquals(0, run.exitCode, run.output)
        assertEquals("A> B> C> <C <B <A", lines["nested"])
        assertEquals("A> B> C> D> E> <E <D <C <B <A", lines["return"])
        assertEquals("A> wrapped:wrapped B> C> <C <B wrapped back <A", lines["context"])
        assertEquals("A> B> C> <C <B <A", lines["pool"])
        assertEquals("A> B> C> D> <D <C <B <A", lines["thread"])
        assertEquals("A> B> caught boom <A", lines["caught"])
        assertEquals("A> B> C> <B finally java.lang.IllegalArgumentException: late", lines["late failure"])
        assertEquals("A> B> C> D> <D <C <B <A", lines["jobless"])
        assertTrue(snapshot.diagnostics.none { it.severity != Diagnostic.Severity.INFO }, "${snapshot.diagnostics}")
    }

    /** The driver and its saved callers are plumbing: no coroutine of the tree stands for them, and nothing is left unfinished. */
    @Test
    fun theDriverIsNoNodeAndNoCoroutineIsLeftHanging() {
        val coroutines = snapshot.nodes.values.filter { it.info.kind != NodeKind.THREAD && it.info.kind != NodeKind.POOL }
        val cases = listOf("nested", "return", "context", "pool", "thread", "caught", "late failure")
        assertEquals(
            // runBlocking, one launch per case, the two withContext of the cases that have one, the jobless coroutine.
            1 + cases.size + 2 + 1,
            coroutines.size,
            coroutines.joinToString { "${it.info.construct} ${it.info.name}" },
        )
        assertEquals(cases.toSet() + "pipelines", childrenOf(node("pipelines")).mapNotNull { it.info.name.ifEmpty { null } }.toSet() + "pipelines")
        // The library's workers and pools outlive the program; what the program started is over.
        val ours = coroutines + thread("resumer") + thread("jobless resumer") + thread("main")
        assertTrue(ours.all { it.state.isFinal }, ours.filterNot { it.state.isFinal }.joinToString { it.info.name })
        for (node in coroutines) assertBalanced(node)
    }

    /** Nested proceed() calls that return at once suspend nothing: one run of code, start to end. */
    @Test
    fun nestedProceedWithoutSuspendingIsOneRunOfTheCoroutine() {
        val nested = node("nested")
        assertEquals(listOf(EventKind.RESUMED, EventKind.FINISHED), nested.story())
        assertEquals(setOf("main"), nested.events.filter { it.nodeId == nested.id }.map(::threadName).toSet())
    }

    /** The chain suspends twice, deep inside; the coroutine is the one that suspends and is resumed, once per suspension. */
    @Test
    fun aSuspensionDeepInTheChainIsASuspensionOfTheCoroutineTheChainRunsFor() {
        val case = node("return")
        assertEquals(
            listOf(EventKind.RESUMED, EventKind.SUSPENDED, EventKind.RESUMED, EventKind.SUSPENDED, EventKind.RESUMED, EventKind.FINISHED),
            case.story(),
        )
        // The frames are those of the interceptor, not of the coroutine's own body: it is the context the driver
        // reports that says whose they are.
        assertEquals(listOf("samples.SharedDriverKt\$pause\$1"), case.own(EventKind.SUSPENDED).map(::siteOf).distinct())
        assertEquals(setOf("main"), case.events.filter { it.nodeId == case.id }.map(::threadName).toSet())
    }

    @Test
    fun withContextAroundProceedOwnsWhatRunsInsideAndTheCallerGetsItBack() {
        val launch = node("context")
        val wrapped = node("wrapped")
        assertEquals(launch.id, wrapped.info.parentId)
        assertEquals("withContext", wrapped.info.construct)
        assertEquals(listOf("wrapped"), wrapped.contextDiff.filter { it.kind == ContextElementKind.NAME }.map { it.newValue })

        // The delay of the third interceptor, called inside withContext, suspends withContext's coroutine, not the launch.
        assertEquals(listOf(EventKind.SUSPENDED, EventKind.RESUMED, EventKind.FINISHED), wrapped.story())
        assertEquals(listOf("samples.SharedDriverKt\$pause\$1"), wrapped.own(EventKind.SUSPENDED).map(::siteOf))
        // The launch is suspended in withContext itself, once, and resumed when withContext is over.
        assertEquals(listOf(EventKind.RESUMED, EventKind.SUSPENDED, EventKind.RESUMED, EventKind.FINISHED), launch.story())
        assertNotEquals("samples.SharedDriverKt\$pause\$1", siteOf(launch.own(EventKind.SUSPENDED).single()))
        assertTrue(launch.own(EventKind.FINISHED).single().seq > wrapped.own(EventKind.FINISHED).single().seq)
    }

    @Test
    fun withContextOnAnotherDispatcherKeepsThreadsWithTheNodeThatOwnsThem() {
        val launch = node("pool")
        val inPool = childrenOf(launch).single()
        assertEquals("withContext", inPool.info.construct)
        assertEquals(listOf(EventKind.RESUMED, EventKind.SUSPENDED, EventKind.RESUMED, EventKind.FINISHED), inPool.story())

        // Its context change happens on the caller's thread, before it is dispatched; what it runs happens on a worker.
        val onWorkers = inPool.events.filter { it.nodeId == inPool.id && it.kind in RUNNING }.map(::threadName)
        assertTrue(onWorkers.all { it.startsWith("DefaultDispatcher-worker") }, "$onWorkers")
        // The saved caller of the interceptor above is the launch's, resumed through its own dispatcher: on main again.
        val onMain = launch.events.filter { it.nodeId == launch.id && it.kind in RUNNING }.map(::threadName)
        assertEquals(setOf("main"), onMain.toSet(), "$onMain")
    }

    /** A saved caller is resumed from a thread that has nothing to do with the coroutine, and runs where the coroutine belongs. */
    @Test
    fun resumingFromAnotherThreadRunsTheCallersOnTheirOwnDispatcher() {
        val case = node("thread")
        val resumer = thread("resumer")
        assertEquals(case.id, resumer.info.creatorId)
        assertEquals(listOf(EventKind.RESUMED, EventKind.SUSPENDED, EventKind.RESUMED, EventKind.FINISHED), case.story())
        assertEquals(listOf("samples.SharedDriverKt\$resumeFromThread\$1"), case.own(EventKind.SUSPENDED).map(::siteOf))

        assertEquals(setOf("main"), case.events.filter { it.nodeId == case.id }.map(::threadName).toSet())
        assertTrue(snapshot.events.none { it.threadId == resumer.id && it.nodeId == case.id }, "the coroutine ran on the thread that resumed it")
        // The resumer is a thread that sleeps and resumes: it ran no coroutine, so the sleep is nobody's but its own.
        assertEquals(listOf(0L), resumer.own(EventKind.THREAD_BLOCKED).map { it.otherNodeId })
    }

    /** The same exception, seen where it is thrown through the driver's loop and where an interceptor above it catches it. */
    @Test
    fun anExceptionThatPropagatesThroughUpstreamInterceptorsIsOneExceptionOnOneNode() {
        val case = node("caught")
        val caught = case.own(EventKind.EXCEPTION_HANDLED)
        assertEquals(listOf(HandledBy.CATCH, HandledBy.CATCH), caught.map { it.handledBy })
        assertEquals(1, caught.map { it.exception!!.identity }.distinct().size, "one instance, followed from the driver's catch to the interceptor's")
        assertEquals("java.lang.IllegalStateException", caught.first().exception!!.className)
        assertEquals("boom", caught.first().exception!!.message)

        // It came out of the chain into the code that catches it, not out of the coroutine.
        assertEquals(listOf(EventKind.RESUMED, EventKind.SUSPENDED, EventKind.RESUMED, EventKind.FINISHED), case.story())
        assertEquals(NodeState.COMPLETED, case.state)
        assertEquals(emptyList(), snapshot.events.filter { it.kind == EventKind.EXCEPTION_THROWN || it.kind == EventKind.EXCEPTION_PROPAGATED })
    }

    /** An exception after a suspension comes back through the saved callers (a `finally` among them) to the caller of the chain. */
    @Test
    fun anExceptionAfterASuspensionReachesTheCallerOfTheChain() {
        val case = node("late failure")
        assertEquals(listOf(EventKind.RESUMED, EventKind.SUSPENDED, EventKind.RESUMED, EventKind.FINISHED), case.story())
        val caught = case.own(EventKind.EXCEPTION_HANDLED).single()
        assertEquals("java.lang.IllegalArgumentException", caught.exception!!.className)
        assertEquals("late", caught.exception!!.message)
        assertEquals(setOf("main"), case.events.filter { it.nodeId == case.id }.map(::threadName).toSet())
    }

    /**
     * A coroutine without a Job, found by its root frame: the interceptors' frames reach it through the driver, which has
     * the context of a caller and a way to walk to the coroutine, and it is resumed where the resumer happens to be.
     */
    @Test
    fun aChainInAJoblessCoroutineIsTheCoroutinesOwn() {
        val case = node("jobless pipeline")
        assertEquals("startCoroutine", case.info.construct)
        assertEquals(0L, case.info.parentId)
        assertEquals(
            listOf(EventKind.RESUMED, EventKind.SUSPENDED, EventKind.RESUMED, EventKind.SUSPENDED, EventKind.RESUMED, EventKind.FINISHED),
            case.story(),
        )
        assertEquals(
            listOf("samples.SharedDriverKt\$pause\$1", "samples.SharedDriverKt\$resumeFromThread\$1"),
            case.own(EventKind.SUSPENDED).map(::siteOf),
        )
        // No dispatcher: it goes on in the thread that resumes it, the last time the one that was started for it.
        val threads = case.events.filter { it.nodeId == case.id }.map(::threadName)
        assertEquals("main", threads.first())
        assertEquals("jobless resumer", threads.last())
        assertEquals(NodeState.COMPLETED, case.state)
    }

    private companion object {
        val RUNNING = setOf(EventKind.RESUMED, EventKind.SUSPENDED, EventKind.FINISHED)
    }
}
