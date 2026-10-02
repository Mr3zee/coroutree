package kotlinx.coroutree.model

import kotlinx.coroutree.model.EventKind.CANCELLING
import kotlinx.coroutree.model.EventKind.RESUMED
import kotlinx.coroutree.model.EventKind.SUSPENDED
import kotlinx.coroutree.model.EventKind.THREAD_BLOCKED
import kotlinx.coroutree.model.EventKind.THREAD_UNBLOCKED
import kotlinx.coroutree.model.tree.CrossLink
import kotlinx.coroutree.model.tree.TraceStore
import kotlinx.coroutree.model.tree.title
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How events fold into the tree: TRACE_FORMAT, "Semantics worth knowing" (nodes, two-node events, cross-links, state). */
class TreeFoldTest {
    @Test
    fun stateIsAFoldOverTheEventsOfANode() {
        // "final state if FINISHED; else blocked while THREAD_BLOCKEDs outnumber THREAD_UNBLOCKEDs (they nest); else
        // cancelling after CANCELLING; else suspended / active by the latest of SUSPENDED / RESUMED."
        class Case(val expected: NodeState, val final: NodeState? = null, vararg val kinds: EventKind)

        val cases = listOf(
            Case(NodeState.ACTIVE),
            Case(NodeState.SUSPENDED, null, SUSPENDED),
            Case(NodeState.ACTIVE, null, SUSPENDED, RESUMED),
            Case(NodeState.SUSPENDED, null, RESUMED, SUSPENDED, RESUMED, SUSPENDED),
            Case(NodeState.CANCELLING, null, CANCELLING),
            Case(NodeState.CANCELLING, null, SUSPENDED, CANCELLING),
            Case(NodeState.CANCELLING, null, CANCELLING, SUSPENDED),
            Case(NodeState.CANCELLING, null, CANCELLING, SUSPENDED, RESUMED),
            Case(NodeState.BLOCKED, null, THREAD_BLOCKED),
            Case(NodeState.ACTIVE, null, THREAD_BLOCKED, THREAD_UNBLOCKED),
            Case(NodeState.BLOCKED, null, THREAD_BLOCKED, THREAD_BLOCKED, THREAD_UNBLOCKED),
            Case(NodeState.ACTIVE, null, THREAD_BLOCKED, THREAD_BLOCKED, THREAD_UNBLOCKED, THREAD_UNBLOCKED),
            Case(NodeState.BLOCKED, null, CANCELLING, THREAD_BLOCKED),
            Case(NodeState.CANCELLING, null, CANCELLING, THREAD_BLOCKED, THREAD_UNBLOCKED),
            Case(NodeState.BLOCKED, null, THREAD_BLOCKED, CANCELLING),
            Case(NodeState.SUSPENDED, null, SUSPENDED, THREAD_BLOCKED, THREAD_UNBLOCKED),
            Case(NodeState.COMPLETED, NodeState.COMPLETED),
            Case(NodeState.FAILED, NodeState.FAILED, SUSPENDED, RESUMED),
            Case(NodeState.CANCELLED, NodeState.CANCELLED, SUSPENDED, CANCELLING),
            // A thread that ends while the books say it is blocked has ended.
            Case(NodeState.COMPLETED, NodeState.COMPLETED, THREAD_BLOCKED),
            Case(NodeState.FAILED, NodeState.FAILED, CANCELLING, THREAD_BLOCKED, SUSPENDED),
        )
        for (case in cases) {
            val what = case.kinds.joinToString { it.name } + (case.final?.let { " + FINISHED($it)" } ?: "")
            val store = storeOf(script {
                launched(1)
                case.kinds.forEach { event(1, it) }
                case.final?.let { finished(1, it) }
            })
            assertEquals(case.expected, store.snapshot().node(1)!!.state, what)
            if (case.final != null) {
                // Nothing that is said about a node after its end brings it back.
                var seq = store.snapshot().events.size.toLong()
                for (kind in listOf(SUSPENDED, RESUMED, THREAD_BLOCKED, CANCELLING)) store.accept(Frame(event = Event(seq = ++seq, nodeId = 1, kind = kind)))
                assertEquals(case.final, store.snapshot().node(1)!!.state, "$what, then more events")
            }
        }
    }

    @Test
    fun blockingNestsAndIsSharedWithTheCoroutineOnTheThread() {
        // "a thread blocked in runBlocking runs a coroutine that sleeps"; THREAD_BLOCKED: "node_id = the thread;
        // other_node_id = the coroutine that was running on it".
        val store = TraceStore()
        val frames = script {
            thread(1, "main")
            launched(2, parent = 1, construct = "runBlocking")
            launched(3, parent = 2)
            event(1, THREAD_BLOCKED) { copy(blockReason = BlockReason.RUN_BLOCKING) }
            event(3, RESUMED, thread = 1)
            event(1, THREAD_BLOCKED, other = 3) { copy(blockReason = BlockReason.SLEEP) }
            event(1, THREAD_UNBLOCKED, other = 3)
            event(1, THREAD_UNBLOCKED)
        }
        fun states() = store.snapshot().let { s -> listOf(1L, 2L, 3L).map { s.node(it)!!.state } }

        frames.take(4).forEach(store::accept)
        assertEquals(listOf(NodeState.BLOCKED, NodeState.ACTIVE, NodeState.ACTIVE), states(), "the thread is in runBlocking; nobody was running on it")
        frames.subList(4, 6).forEach(store::accept)
        assertEquals(listOf(NodeState.BLOCKED, NodeState.ACTIVE, NodeState.BLOCKED), states(), "the coroutine sleeps on the thread")
        store.accept(frames[6])
        assertEquals(listOf(NodeState.BLOCKED, NodeState.ACTIVE, NodeState.ACTIVE), states(), "the sleep is over, runBlocking is not")
        store.accept(frames[7])
        assertEquals(listOf(NodeState.ACTIVE, NodeState.ACTIVE, NodeState.ACTIVE), states())
    }

    @Test
    fun aNodeRunsOnTheThreadOfItsLatestResumeAndOnNoneWhileItDoesNotRun() {
        val store = TraceStore()
        val frames = script {
            launched(1)
            event(1, RESUMED, thread = 9)
            event(1, SUSPENDED)
            event(1, RESUMED, thread = 10)
            finished(1, NodeState.COMPLETED)
        }
        val runsOn = frames.map { store.accept(it); store.snapshot().node(1)!!.runsOn }
        assertEquals(listOf(0L, 9L, 0L, 10L, 0L), runsOn)
    }

    @Test
    fun aTwoNodeEventIsOnBothNodesAndOnANodeThatNamesItselfOnce() {
        // "Two-node events belong to the node they happen to and name the other one in other_node_id. A reader shows
        // them on both."
        val snapshot = snapshotOf {
            launched(1)
            launched(2, parent = 1)
            event(2, EventKind.CANCELLATION_PROPAGATED, other = 1) { copy(direction = PropagationDirection.PARENT_TO_CHILD) }
            event(1, EventKind.EXCEPTION_PROPAGATED, other = 2)
            event(2, EventKind.CANCELLATION_REQUESTED, other = 2) // a coroutine that cancels itself
            event(1, SUSPENDED)
        }
        assertEquals(listOf(1L, 3L, 4L, 6L), snapshot.node(1)!!.events.map { it.seq })
        assertEquals(listOf(2L, 3L, 4L, 5L), snapshot.node(2)!!.events.map { it.seq })
        assertEquals(6, snapshot.events.size, "and once in the history of the whole trace")
        // The event stays the event of the node it happened to; being shown elsewhere changes nobody's state.
        assertEquals(NodeState.SUSPENDED, snapshot.node(1)!!.state)
        assertEquals(NodeState.ACTIVE, snapshot.node(2)!!.state)
    }

    @Test
    fun crossLinksAreDerivedFromCreatorsCancellationsAndInterrupts() {
        // "creator_id ≠ parent_id → launched from; CANCELLATION_REQUESTED → cancels; THREAD_INTERRUPTED → interrupts";
        // CrossLink: "Stored on both of its ends."
        val snapshot = snapshotOf {
            thread(1, "main")
            launched(2, parent = 1, creator = 1)                       // seq 2: created by its parent, no link
            launched(3, parent = 2)                                    // seq 3: creator unknown, no link
            launched(4, parent = 0, construct = "Job()", creator = 3)  // seq 4
            launched(5, parent = 2, creator = 3)                       // seq 5: launched into another scope
            event(4, EventKind.CANCELLATION_REQUESTED, other = 3)      // seq 6
            event(4, EventKind.CANCELLATION_REQUESTED)                 // seq 7: nobody known to have asked
            event(4, EventKind.CANCELLATION_REQUESTED, other = 3)      // seq 8: asked again
            event(1, EventKind.THREAD_INTERRUPTED, other = 5)          // seq 9
            event(1, EventKind.THREAD_INTERRUPTED)                     // seq 10
        }
        val launches4 = CrossLink(CrossLink.Kind.LAUNCHED_FROM, from = 3, to = 4, seq = 4)
        val launches5 = CrossLink(CrossLink.Kind.LAUNCHED_FROM, from = 3, to = 5, seq = 5)
        val cancels = CrossLink(CrossLink.Kind.CANCELS, from = 3, to = 4, seq = 6)
        val cancelsAgain = cancels.copy(seq = 8)
        val interrupts = CrossLink(CrossLink.Kind.INTERRUPTS, from = 5, to = 1, seq = 9)
        assertEquals(listOf(interrupts), snapshot.node(1)!!.links)
        assertEquals(emptyList(), snapshot.node(2)!!.links)
        assertEquals(listOf(launches4, launches5, cancels, cancelsAgain), snapshot.node(3)!!.links)
        assertEquals(listOf(launches4, cancels, cancelsAgain), snapshot.node(4)!!.links)
        assertEquals(listOf(launches5, interrupts), snapshot.node(5)!!.links)
        // A link is not a tree edge.
        assertEquals(listOf(1L, 4L), snapshot.roots)
        assertEquals(listOf(3L, 5L), snapshot.node(2)!!.children)
        assertEquals(emptyList(), snapshot.node(3)!!.children)
    }

    @Test
    fun contextDiffIsTheNodesContextAndDispatcherChangesInOrder() {
        val name = ContextChange(ContextElementKind.NAME, "CoroutineName", newValue = "worker", added = true)
        val dispatcher = ContextChange(ContextElementKind.DISPATCHER, "Dispatcher", "Dispatchers.Default", "Dispatchers.IO")
        val custom = ContextChange(ContextElementKind.OTHER, "demo.MyElement", oldValue = "x", removed = true)
        val snapshot = snapshotOf {
            launched(1)
            launched(2, parent = 1, construct = "withContext", kind = NodeKind.CONTEXT_CHANGE)
            event(2, EventKind.CONTEXT_CHANGED) { copy(contextDiff = listOf(name, custom)) }
            event(2, EventKind.DISPATCHER_CHANGED) { copy(contextDiff = listOf(dispatcher)) }
            // A diff carried by any other kind of event is not a change of context.
            event(2, SUSPENDED) { copy(contextDiff = listOf(dispatcher)) }
        }
        assertEquals(listOf(name, custom, dispatcher), snapshot.node(2)!!.contextDiff)
        assertEquals(emptyList(), snapshot.node(1)!!.contextDiff, "the diff is against the parent and stays with the child")
    }

    @Test
    fun rootsAndChildrenAreInOrderOfAppearance() {
        val snapshot = snapshotOf {
            launched(7)
            thread(3, "main")
            launched(9, parent = 3)
            launched(5)
            launched(2, parent = 3)
            launched(8, parent = 9)
        }
        assertEquals(listOf(7L, 3L, 5L), snapshot.roots)
        assertEquals(listOf(9L, 2L), snapshot.node(3)!!.children)
        assertEquals(listOf(8L), snapshot.node(9)!!.children)
        assertEquals(setOf(2L, 3L, 5L, 7L, 8L, 9L), snapshot.nodes.keys)
    }

    @Test
    fun aJobCancelledInsideItsOwnConstructorKeepsWhatHappenedBeforeItWasDefined() {
        // CLAUDE.md: "A node may be referenced before it is defined (a job is cancelled inside its own constructor; a
        // thread is interrupted before start)." TRACE_FORMAT: "a reader keeps a placeholder."
        val store = TraceStore()
        val frames = script {
            launched(1, name = "parent")
            event(2, EventKind.CANCELLATION_PROPAGATED, other = 1) { copy(direction = PropagationDirection.PARENT_TO_CHILD) }
            event(2, CANCELLING)
            launched(2, parent = 1, name = "child")
            thread(3, "main")
            event(4, EventKind.THREAD_INTERRUPTED, other = 3)
            event(4, EventKind.LAUNCHED) { copy(node = NodeInfo(id = 4, kind = NodeKind.THREAD, construct = "Thread.start", name = "worker", parentId = 3)) }
        }
        frames.take(3).forEach(store::accept)
        val before = store.snapshot()
        assertTrue(before.node(2)!!.placeholder)
        assertEquals("node #2", before.node(2)!!.title)
        assertEquals(NodeState.CANCELLING, before.node(2)!!.state)
        assertEquals(listOf(1L, 2L), before.roots, "until its definition says where it belongs, it is on its own")

        frames.drop(3).forEach(store::accept)
        val snapshot = store.snapshot()
        val job = snapshot.node(2)!!
        assertFalse(job.placeholder)
        assertEquals("launch \"child\"", job.title)
        assertEquals(NodeState.CANCELLING, job.state)
        assertEquals(listOf(2L, 3L, 4L), job.events.map { it.seq })
        assertEquals(listOf(2L), snapshot.node(1)!!.children)

        val thread = snapshot.node(4)!!
        assertFalse(thread.placeholder)
        assertEquals("Thread.start \"worker\"", thread.title)
        assertEquals(listOf(CrossLink(CrossLink.Kind.INTERRUPTS, from = 3, to = 4, seq = 6)), thread.links)
        assertEquals(listOf(4L), snapshot.node(3)!!.children)
        assertEquals(listOf(1L, 3L), snapshot.roots, "each node is in the tree once")
    }

    @Test
    fun childrenOfANodeThatIsDefinedLaterMoveWithIt() {
        val store = TraceStore()
        val frames = script {
            launched(3, parent = 5)      // its parent is unknown so far
            launched(6, parent = 5)
            event(9, SUSPENDED)          // a node that is never defined
            launched(5, parent = 4)      // so is the parent's parent
            thread(4, "main")
            launched(7, parent = 5)
        }
        frames.take(3).forEach(store::accept)
        val early = store.snapshot()
        assertEquals(listOf(5L, 9L), early.roots)
        assertTrue(early.node(5)!!.placeholder)
        assertEquals(listOf(3L, 6L), early.node(5)!!.children)

        frames.drop(3).forEach(store::accept)
        val snapshot = store.snapshot()
        assertEquals(listOf(9L, 4L), snapshot.roots, "a root that turned out to have a parent is a root no more; the others are in order of appearance")
        assertEquals(listOf(5L), snapshot.node(4)!!.children)
        assertEquals(listOf(3L, 6L, 7L), snapshot.node(5)!!.children)
        assertFalse(snapshot.node(4)!!.placeholder)
        assertFalse(snapshot.node(5)!!.placeholder)
        assertEquals("Thread \"main\"", snapshot.node(4)!!.title)

        val undefined = snapshot.node(9)!!
        assertTrue(undefined.placeholder, "the definition was never seen")
        assertEquals(NodeState.SUSPENDED, undefined.state)
        assertEquals(0L, undefined.info.parentId)
        assertNull(snapshot.node(10))
    }

    @Test
    fun aPlaceholderThatIsDefinedAsARootStaysTheOneRootItWas() {
        val snapshot = snapshotOf {
            launched(2, parent = 1)
            launched(3)
            thread(1, "main")
        }
        assertEquals(listOf(1L, 3L), snapshot.roots)
        assertEquals(listOf(2L), snapshot.node(1)!!.children)
    }

    @Test
    fun theOtherNodeOfAnEventAndTheEndsOfALinkAreKeptEvenIfNeverDefined() {
        // An agent attached late, or a trace cut short: references to nodes whose definition is not in the stream.
        val snapshot = snapshotOf {
            launched(1, creator = 8)
            event(1, EventKind.CANCELLATION_REQUESTED, other = 9)
        }
        assertEquals(listOf(1L, 8L, 9L), snapshot.roots)
        assertTrue(snapshot.node(8)!!.placeholder && snapshot.node(9)!!.placeholder)
        assertEquals(listOf(CrossLink(CrossLink.Kind.LAUNCHED_FROM, 8, 1, 1)), snapshot.node(8)!!.links)
        assertEquals(listOf(2L), snapshot.node(9)!!.events.map { it.seq })
        assertEquals(listOf(CrossLink.Kind.LAUNCHED_FROM, CrossLink.Kind.CANCELS), snapshot.node(1)!!.links.map { it.kind })
    }

    @Test
    fun stackFramesAreFoundByIdAndZeroIsNoFrame() {
        // "Ids start at 1 and are dense"; "0 is none for node and frame ids".
        val store = TraceStore()
        val early = store.snapshot()
        store.accept(Frame(stackFrame = StackFrameDef(1, "A", "a", "A.kt", 1)))
        store.accept(Frame(stackFrame = StackFrameDef(2, "B", "b", "B.kt", 2, inlined = true)))
        val snapshot = store.snapshot()
        assertEquals("A", snapshot.frame(1)?.className)
        assertEquals(true, snapshot.frame(2)?.inlined)
        assertNull(snapshot.frame(0))
        assertNull(snapshot.frame(3))
        assertNull(snapshot.frame(-1))
        assertNull(early.frame(1), "a snapshot knows the frames that were defined when it was taken")

        // The definitions are sent again to a client that reconnects; the id keeps meaning what it meant.
        store.accept(Frame(stackFrame = StackFrameDef(1, "A", "a", "A.kt", 1)))
        store.accept(Frame(stackFrame = StackFrameDef(3, "C", "c", "C.kt", 3)))
        assertEquals(listOf("A", "B", "C"), (1..3).map { store.snapshot().frame(it)?.className })
    }

    @Test
    fun diagnosticsAndTheHeaderNeedNoEvents() {
        val store = TraceStore()
        assertNull(store.snapshot().header)
        store.accept(Frame(header = TraceHeader(formatVersion = 1, taskPath = ":app:run")))
        store.accept(Frame(diagnostic = Diagnostic(Diagnostic.Severity.ERROR, "hook target missing")))
        store.accept(Frame(diagnostic = Diagnostic(Diagnostic.Severity.WARNING, "untested version")))
        val snapshot = store.snapshot()
        assertEquals(":app:run", snapshot.header?.taskPath)
        assertEquals(listOf("hook target missing", "untested version"), snapshot.diagnostics.map { it.message })
        assertEquals(emptyList(), snapshot.roots)
        assertFalse(snapshot.complete)
    }
}
