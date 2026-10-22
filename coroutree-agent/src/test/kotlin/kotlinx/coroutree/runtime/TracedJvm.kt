package kotlinx.coroutree.runtime

import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.NodeInfo
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.StackFrameDef
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/**
 * The test JVM as a traced one: the tracer is started once (it has no stop), with a trace file that the tests read
 * back with coroutree-model, the other implementation of the format. Nothing is instrumented here. Tests call the
 * hooks the way instrumented code does.
 *
 * [Pace.GATE] is made once for each JVM, from `Tracer.config` when `Pace` loads. This class and [PaceTest] set the
 * same [GATE_OPTIONS] before that, so the JVM has the same gate whichever of the two loads first.
 */
internal object TracedJvm {
    const val GATE_OPTIONS = "live=false,pace.events.per.second=1000"
    const val INTERVAL_NANOS = 1_000_000L
    const val STACK_DEPTH = 12

    val DIR: File
    val TRACE: File
    val CONFIG: AgentConfig

    /** The node id of the events this class emits to find its place in the file. No node of a trace gets an id this high. */
    private const val SENTINEL = Long.MAX_VALUE - 7

    init {
        if (Tracer.config == null) Tracer.config = AgentConfig.parse(GATE_OPTIONS)
        checkNotNull(Pace.GATE) { "the test JVM was to have a gate" }
        DIR = Files.createTempDirectory("coroutree-agent-test").toFile()
        TRACE = File(DIR, "unit.ctrace")
        DIR.deleteOnExit()
        TRACE.deleteOnExit() // deleted after the agent's own shutdown hook has closed it
        // sessions.dir: a test JVM must not register itself where the user's GUI looks.
        CONFIG = AgentConfig.parse(
            "trace.file=$TRACE,sessions.dir=${File(DIR, "sessions")},$GATE_OPTIONS" +
                ",include=com.acme,exclude=com.acme.generated,stack.depth=$STACK_DEPTH,build.id=unit,task.path=:agent:test,project.dir=/work"
        )
        Tracer.start(CONFIG)
    }

    /** Loads this class, which is what starts the tracer. */
    fun start() {
    }

    /**
     * Runs the scenario on a new thread, which the agent has never seen and whose [ThreadState] no test has touched,
     * while tracing is on. Returns what went into the trace file meanwhile.
     */
    fun record(threadName: String, scenario: () -> Unit): Recording {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread({
            try {
                scenario()
            } catch (e: Throwable) {
                failure.set(e)
            }
        }, threadName)
        thread.isDaemon = true
        val recording = record {
            thread.start()
            thread.join(60_000)
            if (thread.isAlive) throw AssertionError("the scenario is stuck: ${thread.stackTrace.toList()}")
        }
        failure.get()?.let { throw AssertionError("the scenario failed", it) }
        return recording
    }

    /** The same on the calling thread, for scenarios that bring their own threads. */
    fun record(scenario: () -> Unit): Recording {
        Tracer.config = CONFIG // PaceTest sets its own when it is loaded
        val begin = sentinel()
        val first = awaitFrames(begin).size
        Tracer.active = true
        try {
            scenario()
        } finally {
            Tracer.active = false
        }
        val end = sentinel()
        return Recording(awaitFrames(end), first, begin, end)
    }

    /** An event of no kind on no node. It takes a sequence number, so when it is in the file, every event before it is too. */
    private fun sentinel(): Long {
        val ts = ThreadState.current()
        ts.awaited = true // what a hook's await leaves, and what emit requires
        val event = TraceEvent(SENTINEL, 0, 0)
        Tracer.emit(ts, event)
        Pace.settle(ts)
        ts.heldNanos = 0
        return event.seq
    }

    /** The frames of the file after the sentinel and every event before it are in it. The list ends with the sentinel. */
    private fun awaitFrames(sentinelSeq: Long): List<Frame> {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (true) {
            val frames = TraceFiles.read(TRACE)
            val events = frames.mapNotNull { it.event }
            val at = frames.indexOfFirst { it.event?.seq == sentinelSeq }
            val before = events.count { it.seq <= sentinelSeq }.toLong()
            // Sequence numbers are dense: that many events up to the sentinel means all of them.
            if (at >= 0 && before == sentinelSeq) return frames.subList(0, at + 1)
            if (System.nanoTime() > deadline) throw AssertionError("the trace file never got to event $sentinelSeq: $before events before it")
            Thread.sleep(5)
        }
    }

    /** What one scenario left in the trace. */
    class Recording internal constructor(frames: List<Frame>, first: Int, begin: Long, end: Long) {
        /** In the order they happened: by sequence number. */
        val events: List<Event>
        val diagnostics: List<Diagnostic>
        val paces: List<PaceDef>

        /** Every stack frame the file has defined so far. */
        val stackFrames: Map<Int, StackFrameDef> = frames.mapNotNull { it.stackFrame }.associateBy { it.id }

        init {
            val own = frames.drop(first)
            diagnostics = own.mapNotNull { it.diagnostic }
            paces = own.mapNotNull { it.pace }
            events = own.mapNotNull { it.event }.filter { it.seq > begin && it.seq < end }.sortedBy { it.seq }
        }

        /** The definition of the one node of that name. */
        fun node(name: String): NodeInfo {
            val found = events.mapNotNull { it.node }.filter { it.name == name }
            if (found.size > 1) throw AssertionError("two definitions of $name: $events")
            return found.firstOrNull() ?: throw AssertionError("no node named $name in $events")
        }

        fun of(nodeId: Long): List<Event> = events.filter { it.nodeId == nodeId }

        /** The kinds of the node's events in order, with the detail that distinguishes two of a kind: `THREAD_BLOCKED(SLEEP)`. */
        fun story(nodeId: Long): List<String> = of(nodeId).map(::describe)

        fun errors(): List<String> = diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }.map { it.message }

        /** The stack as `Class.method`, innermost first. */
        fun stack(ids: List<Int>): List<String> = ids.map { id ->
            val frame = stackFrames[id] ?: throw AssertionError("frame $id is referred to and not defined")
            "${frame.className}.${frame.methodName}"
        }

        companion object {
            fun describe(event: Event): String {
                val detail = when (event.kind) {
                    EventKind.THREAD_BLOCKED -> event.blockReason.name
                    EventKind.EXCEPTION_HANDLED -> event.handledBy.name
                    EventKind.FINISHED -> event.finalState.name
                    else -> ""
                }
                return if (detail.isEmpty()) event.kind.name else "${event.kind.name}($detail)"
            }
        }
    }
}
