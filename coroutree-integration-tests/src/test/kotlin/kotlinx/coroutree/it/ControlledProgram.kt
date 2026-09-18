package kotlinx.coroutree.it

import kotlinx.coroutree.model.Diagnostic
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.PaceDef
import java.io.Closeable
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The `PaceControl` sample under the agent with its live socket on: a program that goes on until it is told to stop,
 * takes orders on its standard input, and can be slowed down, stopped and stepped through [LiveClient]s.
 */
class ControlledProgram(val started: AgentProcess, val session: LiveSession) : Closeable {
    private val stdin = started.process.outputStream
    private val clients = ArrayList<LiveClient>()

    fun connect(): LiveClient = session.connect().also { clients += it }

    /** One word on the program's standard input, see the sample for what it understands. */
    fun tell(word: String) {
        stdin.write("$word\n".toByteArray())
        stdin.flush()
    }

    val output: String get() = started.output

    fun awaitOutput(text: String, timeoutMillis: Long = 30_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (text in output) return
            if (!started.process.isAlive) break
            Thread.sleep(10)
        }
        if (text !in output) fail("The program did not say '$text'. Output:\n$output\n\nThreads:\n${started.threadDump()}")
    }

    /** Whether the program says [text] within [millis]; for "and it does not". */
    fun saysWithin(millis: Long, text: String): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (text in output) return true
            Thread.sleep(10)
        }
        return text in output
    }

    /** Tells the program to stop and expects it to end well, with a trace the agent has no complaints in. */
    fun stopAndAwaitExit(): AgentRun {
        tell("stop")
        val run = started.await(60)
        assertEquals(0, run.exitCode, run.output)
        if ("stopped" !in run.output) fail("The program did not run to its end:\n${run.output}")
        val problems = run.snapshot.diagnostics.filter { it.severity != Diagnostic.Severity.INFO }
        if (problems.isNotEmpty()) fail("The agent reported problems:\n" + problems.joinToString("\n") { it.message } + "\n\n" + run.output)
        return run
    }

    override fun close() {
        clients.forEach { it.close() }
        started.process.destroyForcibly()
    }
}

fun startPaceControl(
    runName: String,
    agentOptions: Map<String, String> = emptyMap(),
    jvmArgs: List<String> = emptyList(),
): ControlledProgram {
    val started = startUnderAgent(
        "samples.PaceControlKt",
        runName = runName,
        agentOptions = mapOf("live" to "true") + agentOptions,
        jvmArgs = jvmArgs,
        keepStdinOpen = true,
    )
    return try {
        ControlledProgram(started, started.awaitSession())
    } catch (e: Throwable) {
        started.process.destroyForcibly()
        throw e
    }
}

// ------------------------------------------------------------------ reading a live stream

/** Id of the node called [name] (a thread's name, a CoroutineName), once its definition has come by. */
fun LiveClient.nodeNamed(name: String, timeoutMillis: Long = 30_000): Long {
    fun find(frames: List<Frame>) = frames.firstNotNullOfOrNull { frame -> frame.event?.node?.takeIf { it.name == name }?.id }
    return find(await("the node \"$name\"", timeoutMillis) { find(it) != null })!!
}

/** [root] and everything structurally below it, as far as the stream has told. */
fun List<Event>.subtreeOf(root: Long): Set<Long> {
    val children = mapNotNull { it.node }.groupBy({ it.parentId }, { it.id })
    val result = LinkedHashSet<Long>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (result.add(id)) queue += children[id].orEmpty()
    }
    return result
}

/** Steps: events that are not the continuation of the step before them. */
val List<Event>.steps: List<Event> get() = filter { !it.sameStep }

/** The first setting of the gate in the stream, past or yet to come, that [condition] holds for. */
fun LiveClient.awaitPace(what: String, condition: (PaceDef) -> Boolean): PaceDef =
    await(what) { frames -> frames.any { it.pace?.let(condition) == true } }.mapNotNull { it.pace }.first(condition)

/** Sends a command and waits until it has come back as the setting it made, which is when it is in force. */
fun LiveClient.command(line: String, scope: Long = 0): PaceDef {
    val before = paceDefs.size
    send(if (scope == 0L) line else "$line $scope")
    return await("the answer to '$line'") { frames -> frames.mapNotNull { it.pace }.drop(before).any { it.scopeNodeId == scope } }
        .mapNotNull { it.pace }.drop(before).first { it.scopeNodeId == scope }
}

/**
 * Waits until nothing has happened for [quietMillis]. A hook that was past the gate when the program was paused
 * still completes, so "paused" is followed by a moment of events; this is how a test waits that moment out.
 */
fun LiveClient.awaitQuiet(quietMillis: Long = 200, timeoutMillis: Long = 20_000, of: (Event) -> Boolean = { true }): Int {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    var count = events.count(of)
    var since = System.nanoTime()
    while (System.nanoTime() < deadline) {
        Thread.sleep(10)
        val now = events.count(of)
        if (now != count) {
            count = now
            since = System.nanoTime()
        } else if (System.nanoTime() - since >= quietMillis * 1_000_000) {
            return count
        }
    }
    fail("The program did not come to rest within $timeoutMillis ms: ${events.takeLast(10).map { "#${it.seq} ${it.kind} node=${it.nodeId}" }}")
}

/** That [of] events keep coming: at least [atLeast] more within [withinMillis]. */
fun LiveClient.assertGoesOn(what: String, atLeast: Int = 5, withinMillis: Long = 10_000, of: (Event) -> Boolean = { true }) {
    val before = events.count(of)
    if (!holdsWithin(withinMillis) { frames -> frames.count { f -> f.event?.let(of) == true } >= before + atLeast }) {
        fail("$what: only ${events.count(of) - before} events in $withinMillis ms")
    }
}

/** That no [of] event comes within [millis]. */
fun LiveClient.assertStandsStill(what: String, millis: Long = 400, of: (Event) -> Boolean = { true }) {
    val before = events.count(of)
    Thread.sleep(millis)
    val after = events.filter(of)
    if (after.size != before) {
        fail("$what, but ${after.size - before} events came: " + after.drop(before).map { "#${it.seq} ${it.kind} node=${it.nodeId} thread=${it.threadId}" })
    }
}

fun Event.isOneOf(vararg kinds: EventKind): Boolean = kind in kinds
