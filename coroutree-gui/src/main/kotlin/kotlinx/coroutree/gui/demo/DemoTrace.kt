package kotlinx.coroutree.gui.demo

import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.ContextChange
import kotlinx.coroutree.model.ContextElement
import kotlinx.coroutree.model.ContextElementKind
import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.ExceptionInfo
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.HandledBy
import kotlinx.coroutree.model.JvmInfo
import kotlinx.coroutree.model.NodeInfo
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.NodeState
import kotlinx.coroutree.model.Origin
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.PropagationDirection
import kotlinx.coroutree.model.SourceFile
import kotlinx.coroutree.model.SourceIndex
import kotlinx.coroutree.model.SourceModule
import kotlinx.coroutree.model.StackFrameDef
import kotlinx.coroutree.model.ThreadInfo
import kotlinx.coroutree.model.TraceHeader

/**
 * A synthetic trace of a small "checkout" program that touches everything the GUI can show: threads and a dispatcher
 * pool, structured and unstructured coroutines, a dispatcher change, a failure that cancels siblings, a supervisor,
 * a CoroutineExceptionHandler, blocking, an interrupt, cross-links, library-internal nodes, an agent diagnostic, and
 * execution control: the program was paused and stepped for a moment, and one subtree is still slowed down at the end.
 *
 * Used by `--demo`, by screenshots and by tests; with a delay between frames it doubles as a fake live session.
 */
object DemoTrace {
    fun frames(): List<Frame> = Script().apply { play() }.frames

    private class Script {
        val frames = ArrayList<Frame>()
        private val frameIds = HashMap<StackFrameDef, Int>()
        private var seq = 0L
        private var clock = 0L
        private var nextId = 0L

        // --- vocabulary -------------------------------------------------------------------------------------------

        fun at(className: String, method: String, file: String, line: Int): Int {
            val key = StackFrameDef(0, className, method, file, line)
            return frameIds.getOrPut(key) {
                val id = frameIds.size + 1
                frames += Frame(stackFrame = key.copy(id = id))
                id
            }
        }

        val resumeWith = at("kotlin.coroutines.jvm.internal.BaseContinuationImpl", "resumeWith", "ContinuationImpl.kt", 33)
        val dispatchedRun = at("kotlinx.coroutines.DispatchedTask", "run", "DispatchedTask.kt", 100)
        val workerRun = at("kotlinx.coroutines.scheduling.CoroutineScheduler\$Worker", "run", "CoroutineScheduler.kt", 682)
        val launchFrame = at("kotlinx.coroutines.BuildersKt", "launch\$default", "Builders.common.kt", 1)
        val threadRun = at("java.lang.Thread", "run", "Thread.java", 1474)
        val threadSleep = at("java.lang.Thread", "sleep", "Thread.java", 509)

        fun checkout(method: String, line: Int) = at("demo.shop.CheckoutKt", method, "Checkout.kt", line)
        fun inventory(method: String, line: Int) = at("demo.shop.Inventory", method, "Inventory.kt", line)
        fun notifier(method: String, line: Int) = at("demo.shop.Notifier", method, "Notifier.kt", line)
        fun http(method: String, line: Int) = at("io.example.http.ClientEngine", method, "ClientEngine.kt", line)

        fun coroutineStack(site: Int) = listOf(site, resumeWith, dispatchedRun, workerRun)
        fun launchStack(site: Int) = listOf(launchFrame, site, resumeWith, dispatchedRun)

        fun emit(
            nodeId: Long,
            kind: EventKind,
            thread: Long,
            stack: List<Int> = emptyList(),
            advanceMicros: Long = 150,
            configure: Event.() -> Event = { this },
        ) {
            clock += advanceMicros * 1000
            frames += Frame(event = Event(seq = ++seq, timeNanos = clock, nodeId = nodeId, kind = kind, threadId = thread, stack = stack).configure())
        }

        /** A setting of the agent's gate, made right after the last event so far. */
        fun pace(scope: Long = 0, intervalNanos: Long = 0, paused: Boolean = false, steps: Int = 0, reason: PaceDef.Reason = PaceDef.Reason.CONTROLLER, dropped: Boolean = false) {
            frames += Frame(pace = PaceDef(clock, seq, scope, intervalNanos, paused, steps, reason, dropped))
        }

        fun job() = ContextElement(ContextElementKind.JOB, "Job")
        fun dispatcher(name: String) = ContextElement(ContextElementKind.DISPATCHER, "Dispatcher", name)
        fun coroutineName(name: String) = ContextElement(ContextElementKind.NAME, "CoroutineName", "CoroutineName($name)")

        fun thread(name: String, parent: Long, creator: Long, site: Int, on: Long, daemon: Boolean = false, origin: Origin = Origin.PROJECT): Long {
            val id = ++nextId
            val info = NodeInfo(
                id = id, kind = NodeKind.THREAD, construct = "Thread", name = name, parentId = parent, creatorId = creator,
                siteFrame = site, origin = origin, implClass = "java.lang.Thread", thread = ThreadInfo(tid = 20 + id, daemon = daemon),
            )
            emit(id, EventKind.LAUNCHED, on, listOf(at("java.lang.Thread", "start", "Thread.java", 1395), site)) { copy(node = info) }
            return id
        }

        fun coroutine(
            construct: String,
            parent: Long,
            site: Int,
            on: Long,
            kind: NodeKind = NodeKind.COROUTINE,
            name: String = "",
            creator: Long = parent,
            origin: Origin = Origin.PROJECT,
            implClass: String = "kotlinx.coroutines.StandaloneCoroutine",
            context: List<ContextElement> = listOf(job(), dispatcher("Dispatchers.Default")),
            diff: List<ContextChange> = emptyList(),
        ): Long {
            val id = ++nextId
            val elements = if (name.isEmpty()) context else context + coroutineName(name)
            val info = NodeInfo(
                id = id, kind = kind, construct = construct, name = name, parentId = parent, creatorId = creator,
                siteFrame = site, origin = origin, context = elements, implClass = implClass,
            )
            emit(id, EventKind.LAUNCHED, on, launchStack(site)) { copy(node = info) }
            val (dispatcherChanges, otherChanges) = diff.partition { it.kind == ContextElementKind.DISPATCHER }
            val named = if (name.isEmpty()) otherChanges else
                otherChanges + ContextChange(ContextElementKind.NAME, "CoroutineName", newValue = "CoroutineName($name)", added = true)
            if (named.isNotEmpty()) emit(id, EventKind.CONTEXT_CHANGED, on, advanceMicros = 1) { copy(contextDiff = named) }
            if (dispatcherChanges.isNotEmpty()) emit(id, EventKind.DISPATCHER_CHANGED, on, advanceMicros = 1) { copy(contextDiff = dispatcherChanges) }
            return id
        }

        fun dispatcherChange(from: String, to: String) = listOf(ContextChange(ContextElementKind.DISPATCHER, "Dispatcher", from, to))

        fun exception(className: String, message: String, throwSite: Int, identity: Int, cancellation: Boolean = false) =
            ExceptionInfo(className, message, listOf(throwSite, resumeWith, dispatchedRun, workerRun), identity, cancellation)

        fun finish(id: Long, on: Long, state: NodeState = NodeState.COMPLETED) =
            emit(id, EventKind.FINISHED, on) { copy(finalState = state) }

        // --- the story --------------------------------------------------------------------------------------------

        fun play() {
            frames += Frame(
                header = TraceHeader(
                    formatVersion = TraceHeader.FORMAT_VERSION,
                    agentVersion = "demo",
                    buildId = "20260917-201500-demo",
                    taskPath = ":shop:run",
                    jvm = JvmInfo(pid = 4242, javaVersion = "26.0.2", vmName = "OpenJDK 64-Bit Server VM", vmVersion = "26.0.2+1", command = "demo.shop.CheckoutKt --orders 3"),
                    sourceIndex = SourceIndex(
                        listOf(
                            SourceModule(
                                path = ":shop", rootDir = "/demo/shop",
                                files = listOf("Checkout.kt", "Inventory.kt", "Notifier.kt").map { SourceFile("demo.shop", it, "src/main/kotlin/demo/shop/$it") },
                            )
                        )
                    ),
                    includePackages = listOf("demo.shop"),
                    startedAtEpochMillis = 1_789_668_900_000,
                    projectDir = "/demo",
                    paceable = true,
                )
            )
            pace(reason = PaceDef.Reason.CONFIG)
            frames += Frame(
                diagnostic = Diagnostic(
                    Diagnostic.Severity.WARNING,
                    "kotlinx-coroutines-core 1.9.0 is older than the versions coroutree was tested with (1.10.2 – 1.11.0); cancellation events may be incomplete.",
                )
            )

            val main = ++nextId
            emit(main, EventKind.DISCOVERED, main, advanceMicros = 0) {
                copy(node = NodeInfo(id = main, kind = NodeKind.THREAD, construct = "Thread", name = "main", implClass = "java.lang.Thread", thread = ThreadInfo(tid = 1)))
            }

            val runBlocking = coroutine(
                "runBlocking", main, checkout("main", 14), main,
                implClass = "kotlinx.coroutines.BlockingCoroutine",
                context = listOf(job(), dispatcher("BlockingEventLoop@5e91993f")),
            )
            emit(main, EventKind.THREAD_BLOCKED, main, listOf(at("kotlinx.coroutines.BlockingCoroutine", "joinBlocking", "Builders.kt", 95), checkout("main", 14))) {
                copy(blockReason = BlockReason.RUN_BLOCKING)
            }
            emit(runBlocking, EventKind.RESUMED, main)

            // Dispatchers.Default comes to life with the first coroutine sent to it.
            val pool = ++nextId
            emit(pool, EventKind.LAUNCHED, main) {
                copy(node = NodeInfo(id = pool, kind = NodeKind.POOL, construct = "Pool", name = "DefaultDispatcher", origin = Origin.LIBRARY, implClass = "kotlinx.coroutines.scheduling.CoroutineScheduler"))
            }
            val schedulerSite = at("kotlinx.coroutines.scheduling.CoroutineScheduler", "createNewWorker", "CoroutineScheduler.kt", 490)
            val worker1 = thread("DefaultDispatcher-worker-1", pool, runBlocking, schedulerSite, main, daemon = true, origin = Origin.LIBRARY)
            val worker2 = thread("DefaultDispatcher-worker-2", pool, runBlocking, schedulerSite, main, daemon = true, origin = Origin.LIBRARY)
            val worker3 = thread("DefaultDispatcher-worker-3", pool, runBlocking, schedulerSite, worker1, daemon = true, origin = Origin.LIBRARY)

            val toDefault = dispatcherChange("BlockingEventLoop@5e91993f", "Dispatchers.Default")

            // A plain thread started from a coroutine: structural parent is the starting thread, the coroutine is a link.
            val reporter = thread("reporter", main, runBlocking, checkout("startReporter", 58), main)
            emit(reporter, EventKind.THREAD_BLOCKED, reporter, listOf(threadSleep, checkout("report", 64), threadRun)) { copy(blockReason = BlockReason.SLEEP) }

            val inventoryJob = coroutine("launch", runBlocking, checkout("main", 17), main, name = "inventory", diff = toDefault)
            val price = coroutine(
                "async", runBlocking, checkout("main", 21), main, name = "price", diff = toDefault,
                implClass = "kotlinx.coroutines.DeferredCoroutine",
            )
            emit(runBlocking, EventKind.SUSPENDED, main, listOf(checkout("main", 24)))

            emit(inventoryJob, EventKind.RESUMED, worker1)
            emit(price, EventKind.RESUMED, worker2)

            // Unstructured: a GlobalScope coroutine is a root; whoever launched it is only a cross-link.
            val metrics = coroutine(
                "launch", 0, inventory("reserve", 31), worker1, name = "metrics", creator = inventoryJob,
                context = listOf(job(), dispatcher("Dispatchers.Default")),
            )
            emit(metrics, EventKind.RESUMED, worker3)
            emit(metrics, EventKind.SUSPENDED, worker3, coroutineStack(inventory("pushMetrics", 77)))

            val io = coroutine(
                "withContext", inventoryJob, inventory("reserve", 35), worker1, kind = NodeKind.CONTEXT_CHANGE,
                implClass = "kotlinx.coroutines.DispatchedCoroutine",
                context = listOf(job(), dispatcher("Dispatchers.IO"), coroutineName("inventory")),
                diff = dispatcherChange("Dispatchers.Default", "Dispatchers.IO"),
            )
            emit(inventoryJob, EventKind.SUSPENDED, worker1, coroutineStack(inventory("reserve", 35)))
            emit(io, EventKind.RESUMED, worker3)
            val socketRead = at("sun.nio.ch.NioSocketImpl", "read", "NioSocketImpl.java", 346)
            emit(worker3, EventKind.THREAD_BLOCKED, worker3, listOf(socketRead, inventory("queryStock", 52), resumeWith, dispatchedRun)) {
                copy(blockReason = BlockReason.IO, otherNodeId = io)
            }
            emit(price, EventKind.SUSPENDED, worker2, coroutineStack(checkout("fetchPrice", 44)))
            emit(worker3, EventKind.THREAD_UNBLOCKED, worker3, advanceMicros = 8200) { copy(blockReason = BlockReason.IO, otherNodeId = io) }
            finish(io, worker3)
            emit(inventoryJob, EventKind.RESUMED, worker1)

            // The reporter has slept long enough.
            emit(reporter, EventKind.THREAD_INTERRUPTED, worker1, listOf(at("java.lang.Thread", "interrupt", "Thread.java", 1717), inventory("reserve", 39), resumeWith)) {
                copy(otherNodeId = inventoryJob)
            }
            emit(reporter, EventKind.THREAD_UNBLOCKED, reporter) { copy(blockReason = BlockReason.SLEEP) }
            finish(reporter, reporter)
            finish(inventoryJob, worker1)

            emit(price, EventKind.RESUMED, worker2, advanceMicros = 900)
            finish(price, worker2)
            emit(runBlocking, EventKind.RESUMED, main)

            // coroutineScope: one child fails, the scope fails, the sibling is cancelled, the caller catches.
            val scope = coroutine(
                "coroutineScope", runBlocking, checkout("charge", 72), main, kind = NodeKind.SCOPE,
                implClass = "kotlinx.coroutines.internal.ScopeCoroutine",
                context = listOf(job(), dispatcher("BlockingEventLoop@5e91993f")),
            )
            val payment = coroutine("launch", scope, checkout("charge", 73), main, name = "payment", diff = toDefault)
            val audit = coroutine("launch", scope, checkout("charge", 78), main, name = "audit", diff = toDefault)
            emit(scope, EventKind.SUSPENDED, main, listOf(checkout("charge", 72)))
            emit(runBlocking, EventKind.SUSPENDED, main, listOf(checkout("main", 27)))
            // Somebody wants to watch the failure happen: paused, two steps, and on.
            pace(paused = true)
            clock += 4_000_000_000
            pace(paused = true, steps = 2)
            emit(payment, EventKind.RESUMED, worker1)
            emit(audit, EventKind.RESUMED, worker2)
            clock += 2_500_000_000
            pace()
            emit(audit, EventKind.SUSPENDED, worker2, coroutineStack(checkout("writeAudit", 91)))
            val declined = exception("java.lang.IllegalStateException", "card declined", checkout("authorize", 84), identity = 0x5ca1ab1e)
            emit(payment, EventKind.EXCEPTION_THROWN, worker1, advanceMicros = 2100) { copy(exception = declined) }
            emit(payment, EventKind.CANCELLING, worker1) { copy(exception = declined) }
            emit(scope, EventKind.EXCEPTION_PROPAGATED, worker1) { copy(exception = declined, otherNodeId = payment, direction = PropagationDirection.CHILD_TO_PARENT) }
            emit(scope, EventKind.CANCELLING, worker1) { copy(exception = declined) }
            val parentCancelled = ExceptionInfo("kotlinx.coroutines.JobCancellationException", "Parent job is Cancelling", identity = 0x0ddba11, cancellation = true)
            emit(audit, EventKind.CANCELLATION_PROPAGATED, worker1) { copy(otherNodeId = scope, direction = PropagationDirection.PARENT_TO_CHILD, exception = parentCancelled) }
            emit(audit, EventKind.CANCELLING, worker1) { copy(exception = parentCancelled) }
            finish(payment, worker1, NodeState.FAILED)
            emit(audit, EventKind.RESUMED, worker2)
            finish(audit, worker2, NodeState.CANCELLED)
            finish(scope, worker2, NodeState.FAILED)
            emit(runBlocking, EventKind.RESUMED, main)
            emit(runBlocking, EventKind.EXCEPTION_HANDLED, main, listOf(checkout("main", 29))) { copy(exception = declined, handledBy = HandledBy.CATCH) }

            // supervisorScope: a failing child reaches its CoroutineExceptionHandler and nobody else notices.
            val supervisor = coroutine(
                "supervisorScope", runBlocking, notifier("notifyAll", 18), main, kind = NodeKind.SCOPE,
                implClass = "kotlinx.coroutines.SupervisorCoroutine",
                context = listOf(job(), dispatcher("BlockingEventLoop@5e91993f")),
            )
            val handler = ContextElement(ContextElementKind.EXCEPTION_HANDLER, "CoroutineExceptionHandler", "demo.shop.Notifier\$special\$\$inlined\$CoroutineExceptionHandler\$1@2f7a2457")
            val email = coroutine(
                "launch", supervisor, notifier("notifyAll", 19), main, name = "email",
                context = listOf(job(), dispatcher("Dispatchers.Default"), handler),
                diff = toDefault + ContextChange(ContextElementKind.EXCEPTION_HANDLER, "CoroutineExceptionHandler", newValue = "Notifier\$…\$1@2f7a2457", added = true),
            )
            val sms = coroutine("launch", supervisor, notifier("notifyAll", 23), main, name = "sms", diff = toDefault)
            emit(supervisor, EventKind.SUSPENDED, main, listOf(notifier("notifyAll", 18)))
            emit(runBlocking, EventKind.SUSPENDED, main, listOf(checkout("main", 33)))
            emit(email, EventKind.RESUMED, worker1)
            emit(sms, EventKind.RESUMED, worker2)
            emit(sms, EventKind.SUSPENDED, worker2, coroutineStack(notifier("sendSms", 47)))
            val smtp = exception("java.net.ConnectException", "smtp.example.com:25 refused", notifier("sendEmail", 38), identity = 0x7e1eca57)
            emit(email, EventKind.EXCEPTION_THROWN, worker1, advanceMicros = 1300) { copy(exception = smtp) }
            emit(email, EventKind.CANCELLING, worker1) { copy(exception = smtp) }
            emit(supervisor, EventKind.EXCEPTION_HANDLED, worker1) { copy(exception = smtp, otherNodeId = email, handledBy = HandledBy.SUPERVISOR) }
            emit(email, EventKind.EXCEPTION_HANDLED, worker1, listOf(notifier("invoke", 12))) { copy(exception = smtp, handledBy = HandledBy.COROUTINE_EXCEPTION_HANDLER) }
            finish(email, worker1, NodeState.FAILED)

            // A library keeps its own scope; one of its coroutines calls back into project code.
            val engineSite = http("<init>", 41)
            val engine = coroutine(
                "SupervisorJob()", 0, engineSite, main, kind = NodeKind.SCOPE, name = "", creator = runBlocking, origin = Origin.LIBRARY,
                implClass = "kotlinx.coroutines.SupervisorJobImpl", context = listOf(job()),
            )
            val selector = coroutine("launch", engine, http("startSelector", 66), main, name = "http-selector", origin = Origin.LIBRARY)
            val pinger = coroutine("launch", engine, http("startKeepAlive", 93), main, name = "http-keepalive", origin = Origin.LIBRARY)
            emit(selector, EventKind.RESUMED, worker3)
            emit(selector, EventKind.SUSPENDED, worker3, coroutineStack(http("select", 120)))
            emit(pinger, EventKind.RESUMED, worker1)
            emit(pinger, EventKind.SUSPENDED, worker1, coroutineStack(http("keepAlive", 99)))
            val callbacks = coroutine(
                "SupervisorJob()", 0, http("callbacks", 140), main, kind = NodeKind.SCOPE, creator = runBlocking, origin = Origin.LIBRARY,
                implClass = "kotlinx.coroutines.SupervisorJobImpl", context = listOf(job()),
            )
            val dispatch = coroutine("launch", callbacks, http("dispatchResponse", 151), worker3, name = "http-response", origin = Origin.LIBRARY)
            emit(dispatch, EventKind.RESUMED, worker3)
            val onReceipt = coroutine("launch", dispatch, notifier("onReceipt", 61), worker3, name = "receipt")
            emit(onReceipt, EventKind.RESUMED, worker1)
            pace(scope = onReceipt, intervalNanos = 2_000_000_000) // one subtree in slow motion, to the end of the trace
            emit(onReceipt, EventKind.SUSPENDED, worker1, coroutineStack(notifier("storeReceipt", 68)))
            emit(dispatch, EventKind.SUSPENDED, worker3, coroutineStack(http("dispatchResponse", 153)))

            // Shutdown begins: main cancels the metrics coroutine it never owned.
            emit(sms, EventKind.RESUMED, worker2, advanceMicros = 3000)
            finish(sms, worker2)
            finish(supervisor, worker2)
            emit(runBlocking, EventKind.RESUMED, main)
            val cancelled = ExceptionInfo("kotlinx.coroutines.JobCancellationException", "shutting down", identity = 0xcafe, cancellation = true)
            emit(metrics, EventKind.CANCELLATION_REQUESTED, main, listOf(at("kotlinx.coroutines.JobSupport", "cancel", "JobSupport.kt", 647), checkout("main", 36))) {
                copy(otherNodeId = runBlocking, exception = cancelled)
            }
            emit(metrics, EventKind.CANCELLING, main) { copy(exception = cancelled) }
            emit(metrics, EventKind.RESUMED, worker3)
            finish(metrics, worker3, NodeState.CANCELLED)

            // The trace stops mid-flight: runBlocking is waiting for the receipt, which is being stored right now.
            emit(runBlocking, EventKind.SUSPENDED, main, listOf(checkout("main", 38)))
            emit(onReceipt, EventKind.RESUMED, worker1)
            emit(worker2, EventKind.THREAD_BLOCKED, worker2, listOf(at("java.lang.Object", "wait", "Object.java", 389), notifier("flush", 80))) {
                copy(blockReason = BlockReason.WAIT)
            }
        }
    }
}
