package kotlinx.coroutree.it

import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.tree.CrossLink
import kotlinx.coroutree.model.tree.NodeSnapshot
import kotlinx.coroutree.model.tree.TraceSnapshot
import kotlinx.coroutree.model.tree.siteOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the golden trees leave out on purpose — threads that are not the project's, and pools — and what they have no
 * place for: whose a thread is, what kind it is, and which node a blocking call is reported on. Samples
 * `ExecutorsAndPools`, `ThreadCorners` and `BlockingOnDispatchers`.
 */
class LibraryThreadsTest {
    private fun TraceSnapshot.named(name: String): NodeSnapshot = nodes.values.single { it.info.name == name }
    private fun TraceSnapshot.site(node: NodeSnapshot) = frame(node.info.siteFrame)?.let { "${it.fileName}:${it.line}" }

    @Test
    fun threadsOfExecutorsAndTimersAreLibraryThreadsUnderTheThreadThatMadeThemStart() {
        val snapshot = SampleRuns["ExecutorsAndPools"].snapshot
        val main = snapshot.named("main")
        for ((name, line) in listOf("pool-worker" to 21, "timer" to 30)) {
            val thread = snapshot.named(name)
            assertEquals(NodeKind.THREAD, thread.info.kind, name)
            assertEquals(Origin.LIBRARY, thread.info.origin, "$name was started by a library on the program's behalf")
            assertEquals("Thread.start", thread.info.construct, name)
            assertEquals(main.id, thread.info.parentId, name)
            assertEquals("ExecutorsAndPools.kt:$line", snapshot.site(thread), "the site is the line of the program that had it started")
            assertEquals(true, thread.info.thread?.daemon, name)
        }
        // What the program's own code does on such a thread is the program's, and hangs under the thread.
        val onThePool = snapshot.named("on the pool")
        assertEquals(snapshot.named("pool-worker").id, onThePool.info.parentId)
        assertEquals(Origin.PROJECT, onThePool.info.origin)
        assertEquals(onThePool.id, snapshot.named("pool child").info.parentId)
        assertTrue(snapshot.named("pool-worker").events.any { it.kind == EventKind.THREAD_BLOCKED && it.blockReason == BlockReason.RUN_BLOCKING })
    }

    @Test
    fun workersOfForkJoinPoolsAndCarriersHangUnderAPoolNode() {
        val snapshot = SampleRuns["ExecutorsAndPools"].snapshot
        for (poolName in listOf("ForkJoinPool.commonPool", "virtual thread carriers")) {
            val pool = snapshot.nodes.values.single { it.info.kind == NodeKind.POOL && it.info.name == poolName }
            assertEquals(0, pool.info.parentId, "$poolName: a pool does not hang off whichever thread made it grow")
            assertEquals(Origin.LIBRARY, pool.info.origin)
            val workers = pool.children.map { snapshot.node(it)!! }
            assertTrue(workers.isNotEmpty(), poolName)
            for (worker in workers) {
                assertEquals(NodeKind.THREAD, worker.info.kind)
                assertEquals("worker", worker.info.construct)
                assertEquals(Origin.LIBRARY, worker.info.origin)
            }
        }
        val commonPoolWorkers = snapshot.nodes.values.single { it.info.name == "ForkJoinPool.commonPool" }.children.map { snapshot.node(it)!! }
        assertTrue(
            commonPoolWorkers.any { worker -> worker.events.any { it.kind == EventKind.THREAD_BLOCKED && it.blockReason == BlockReason.SLEEP } },
            "the task of supplyAsync sleeps on a worker of the common pool",
        )
        // The virtual thread of the per-task executor: a thread like any other, not the project's, and not a carrier.
        val virtual = snapshot.nodes.values.filter { it.info.thread?.virtual == true }
        assertEquals(1, virtual.size, virtual.map { it.info }.toString())
        assertEquals(Origin.LIBRARY, virtual.single().info.origin)
        assertEquals(snapshot.named("main").id, virtual.single().info.parentId)
        assertEquals(NodeState.COMPLETED, virtual.single().state)
    }

    @Test
    fun aThreadIsDescribedAsWhatItIs() {
        val snapshot = SampleRuns["ThreadCorners"].snapshot
        val daemon = snapshot.named("daemon")
        assertEquals(true, daemon.info.thread?.daemon)
        assertEquals(NodeState.BLOCKED, daemon.state, "still asleep when the JVM went")
        assertTrue(daemon.events.none { it.kind == EventKind.FINISHED })
        assertEquals(false, snapshot.named("locker").info.thread?.daemon)

        val virtual = snapshot.named("virtual parker")
        assertEquals(true, virtual.info.thread?.virtual)
        assertEquals(Origin.PROJECT, virtual.info.origin)
        val child = snapshot.named("started by a virtual thread")
        assertEquals(false, child.info.thread?.virtual)
        assertEquals(virtual.id, child.info.parentId)
        // The carriers the virtual thread ran on are infrastructure: under their pool, never its parent.
        assertEquals(NodeKind.THREAD, snapshot.node(virtual.info.parentId)?.info?.kind)
        assertEquals("main", snapshot.node(virtual.info.parentId)?.info?.name)
    }

    @Test
    fun aBlockingCallMadeOfBlockingCallsIsReportedOnceWhereTheProgramMadeIt() {
        val snapshot = SampleRuns["ThreadCorners"].snapshot
        for ((name, reason, line) in listOf(
            Triple("locker", BlockReason.PARK, 21),           // ReentrantLock.lock parks
            Triple("latched", BlockReason.PARK, 22),          // CountDownLatch.await parks
            Triple("interrupted in await", BlockReason.PARK, 50), // Condition.await parks
            Triple("interrupted in join", BlockReason.JOIN, 59),  // Thread.join waits
            Triple("timed waiter", BlockReason.WAIT, 23),
        )) {
            // Minus what golden trees leave out as well: contention on the JVM's own monitors, class loading and the like.
            val blocked = snapshot.named(name).events.filter { it.kind == EventKind.THREAD_BLOCKED && it.blockReason != BlockReason.MONITOR }
            assertEquals(listOf(reason), blocked.map { it.blockReason }, name)
            assertEquals("ThreadCorners.kt:$line", snapshot.siteOf(blocked.single().stack)?.let { "${it.fileName}:${it.line}" }, name)
        }
        val blocks = snapshot.events.filter { it.kind == EventKind.THREAD_BLOCKED || it.kind == EventKind.THREAD_UNBLOCKED }
        for ((thread, ofThread) in blocks.groupBy { it.nodeId }) {
            if (snapshot.node(thread)?.state?.isFinal != true) continue
            assertEquals(ofThread.count { it.kind == EventKind.THREAD_BLOCKED }, ofThread.count { it.kind == EventKind.THREAD_UNBLOCKED }, snapshot.node(thread)?.info?.name)
        }
    }

    @Test
    fun aThreadThatInterruptsItselfIsBothEndsOfTheEvent() {
        val snapshot = SampleRuns["ThreadCorners"].snapshot
        val self = snapshot.named("interrupts itself")
        val interrupt = self.events.single { it.kind == EventKind.THREAD_INTERRUPTED }
        assertEquals(self.id, interrupt.nodeId)
        assertEquals(self.id, interrupt.otherNodeId)
        assertEquals(self.id, interrupt.threadId)
        assertEquals(1, self.events.count { it.seq == interrupt.seq }, "shown once on the node, not once for each end")
        val main = snapshot.named("main")
        assertEquals(4, main.links.count { it.kind == CrossLink.Kind.INTERRUPTS && it.from == main.id })
    }

    @Test
    fun aCoroutineThatBlocksAPoolThreadIsNamedInTheThreadsEvent() {
        val snapshot = SampleRuns["BlockingOnDispatchers"].snapshot
        for ((name, reason, line) in listOf(
            Triple("wants the lock", BlockReason.PARK, 20),
            Triple("bridge", BlockReason.RUN_BLOCKING, 24),
            Triple("joins a thread", BlockReason.JOIN, 29),
        )) {
            val coroutine = snapshot.named(name)
            // One blocking call each, next to whatever contention on the JVM's own monitors (class loading) came their way.
            val blocked = snapshot.events.single { it.kind == EventKind.THREAD_BLOCKED && it.otherNodeId == coroutine.id && it.blockReason != BlockReason.MONITOR }
            assertEquals(reason, blocked.blockReason, name)
            assertEquals("BlockingOnDispatchers.kt:$line", snapshot.siteOf(blocked.stack)?.let { "${it.fileName}:${it.line}" }, name)
            val worker = assertNotNull(snapshot.node(blocked.nodeId), name)
            assertEquals("worker", worker.info.construct, "$name runs on a thread of the dispatcher")
            assertEquals(NodeKind.POOL, snapshot.node(worker.info.parentId)?.info?.kind, name)
            val unblocked = snapshot.events.first { it.kind == EventKind.THREAD_UNBLOCKED && it.otherNodeId == coroutine.id && it.seq > blocked.seq }
            assertEquals(blocked.nodeId, unblocked.nodeId, "$name is unblocked on the thread it blocked")
        }
        // A thread hangs under the thread that started it, a pool worker here; the coroutine whose code it was is its creator.
        val short = snapshot.named("short")
        assertEquals(Origin.PROJECT, short.info.origin)
        assertEquals("worker", snapshot.node(short.info.parentId)?.info?.construct)
        assertEquals(snapshot.named("joins a thread").id, short.info.creatorId)
        // runBlocking inside a coroutine hangs off that coroutine, and its own children under it.
        assertEquals(snapshot.named("bridge").id, snapshot.named("inner loop").info.parentId)
        assertEquals(snapshot.named("inner loop").id, snapshot.named("inner child").info.parentId)
    }
}
