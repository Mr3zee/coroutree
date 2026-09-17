package kotlinx.coroutree.model.tree

import kotlinx.coroutree.model.BlockReason
import kotlinx.coroutree.model.ContextChange
import kotlinx.coroutree.model.ContextElementKind
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.ExceptionInfo
import kotlinx.coroutree.model.HandledBy
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.PropagationDirection
import kotlinx.coroutree.model.StackFrameDef

/*
 * Human-readable wording of nodes and events. Lives next to the model so that the GUI, the golden-tree tests and
 * (later) the static mode all describe the same thing with the same words.
 */

/** `launch "worker"`, `Thread "main"`, `withContext`. */
public val NodeSnapshot.title: String
    get() {
        val what = info.construct.ifEmpty {
            when (info.kind) {
                NodeKind.THREAD -> "Thread"
                NodeKind.POOL -> "Pool"
                NodeKind.UNSPECIFIED -> "node #${info.id}"
                else -> info.kind.name.lowercase().replace('_', ' ')
            }
        }
        return if (info.name.isEmpty()) what else "$what \"${info.name}\""
    }

/** `Main.kt:12`, or an empty string when the frame carries no file name. */
public val StackFrameDef.shortLocation: String
    get() = when {
        fileName.isEmpty() -> ""
        line > 0 -> "$fileName:$line"
        else -> fileName
    }

/**
 * `com.acme.MainKt.main(Main.kt:12)`, the way the JVM prints it; `inline com.acme.UtilKt.retry(Util.kt:7)` for the
 * body of an inline function, which the JVM does not know of.
 */
public val StackFrameDef.qualified: String
    get() {
        val location = shortLocation.ifEmpty { "Unknown Source" }
        return if (inlined) "inline $className${if (methodName.isEmpty()) "" else ".$methodName"}($location)" else "$className.$methodName($location)"
    }

public val ExceptionInfo.simpleName: String get() = className.substringAfterLast('.')

/** `sleep`, `runBlocking`, `io`: the way the reason is written in code. */
public val BlockReason.label: String
    get() = when (this) {
        BlockReason.RUN_BLOCKING -> "runBlocking"
        else -> name.lowercase()
    }

public fun ExceptionInfo.describe(): String = if (message.isEmpty()) simpleName else "$simpleName: $message"

/** `Dispatcher: Dispatchers.Default → Dispatchers.IO`, `+CoroutineName: worker`, `-MyElement: …`, `+CoroutineExceptionHandler`. */
public fun ContextChange.describe(): String {
    val label = if (kind == ContextElementKind.OTHER || kind == ContextElementKind.UNSPECIFIED) key.substringAfterLast('.') else key
    fun valued(sign: String, value: String) = if (value.isEmpty()) "$sign$label" else "$sign$label: $value"
    return when {
        added -> valued("+", newValue)
        removed -> valued("-", oldValue)
        else -> "$label: $oldValue → $newValue"
    }
}

/**
 * Whether the frame belongs to a concurrency runtime (the JDK, the Kotlin standard library, kotlinx.coroutines)
 * rather than to code that uses one. Same rule as the agent applies when it picks a node's source site.
 */
public val StackFrameDef.isRuntimeFrame: Boolean
    get() = RUNTIME_PREFIXES.any { className.startsWith(it) }

private val RUNTIME_PREFIXES = listOf("java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.coroutines.")

/**
 * The frame of a stack that says where in the program something happened: the innermost one outside the concurrency
 * runtime. `delay` suspends in `Delay.kt`; what one wants to know is who called `delay`.
 */
public fun TraceSnapshot.siteOf(stack: List<Int>): StackFrameDef? {
    val frames = stack.mapNotNull(::frame)
    return frames.firstOrNull { !it.isRuntimeFrame } ?: frames.firstOrNull()
}

/**
 * One-line description of [event] as seen from the node [viewpoint] (events naming another node appear on both).
 * Pass 0 for the neutral wording used in the global event log.
 */
public fun TraceSnapshot.describe(event: Event, viewpoint: Long = 0): String {
    fun name(id: Long) = node(id)?.title ?: "node #$id"
    val other = event.otherNodeId
    return when (event.kind) {
        EventKind.LAUNCHED -> "launched"
        EventKind.DISCOVERED -> "first seen"
        EventKind.CONTEXT_CHANGED -> "context changed: " + event.contextDiff.joinToString { it.describe() }
        EventKind.DISPATCHER_CHANGED -> "dispatcher changed: " +
            event.contextDiff.joinToString { "${it.oldValue.ifEmpty { "none" }} → ${it.newValue.ifEmpty { "none" }}" }
        EventKind.SUSPENDED -> "suspended" + (siteOf(event.stack)?.shortLocation?.takeIf { it.isNotEmpty() }?.let { " at $it" } ?: "")
        EventKind.RESUMED -> "resumed on ${name(event.threadId)}"
        EventKind.EXCEPTION_THROWN -> "threw ${event.exception?.describe()}"
        EventKind.EXCEPTION_PROPAGATED ->
            if (viewpoint == other) "${event.exception?.simpleName} propagated to ${name(event.nodeId)}"
            else "${event.exception?.simpleName} propagated from ${name(other)}"
        EventKind.EXCEPTION_HANDLED -> "${event.exception?.simpleName} " + when (event.handledBy) {
            HandledBy.CATCH -> "caught by catch"
            HandledBy.COROUTINE_EXCEPTION_HANDLER -> "handled by CoroutineExceptionHandler"
            HandledBy.SUPERVISOR ->
                if (viewpoint == other) "stopped at supervisor ${name(event.nodeId)}" else "of ${name(other)} stopped here (supervisor)"
            HandledBy.DEFERRED_HELD -> "held in Deferred until awaited"
            HandledBy.UNCAUGHT_EXCEPTION_HANDLER -> "reached the uncaught exception handler"
            HandledBy.UNSPECIFIED -> "handled"
        }
        EventKind.CANCELLATION_REQUESTED ->
            if (viewpoint == other && other != 0L) "requested cancellation of ${name(event.nodeId)}"
            else "cancellation requested" + (if (other != 0L) " by ${name(other)}" else "")
        EventKind.CANCELLATION_PROPAGATED -> when (event.direction) {
            PropagationDirection.PARENT_TO_CHILD ->
                if (viewpoint == other) "cancellation propagated to child ${name(event.nodeId)}" else "cancelled by parent ${name(other)}"
            else ->
                if (viewpoint == other) "cancellation propagated to parent ${name(event.nodeId)}" else "cancelled by child ${name(other)}"
        }
        EventKind.CANCELLING -> "cancelling" + (event.exception?.let { ": ${it.describe()}" } ?: "")
        EventKind.THREAD_BLOCKED -> when {
            viewpoint == other && other != 0L -> "blocked ${name(event.nodeId)} (${event.blockReason.label})"
            other != 0L -> "blocked (${event.blockReason.label}) in ${name(other)}"
            else -> "blocked (${event.blockReason.label})"
        }
        EventKind.THREAD_UNBLOCKED -> if (viewpoint == other && other != 0L) "unblocked ${name(event.nodeId)}" else "unblocked"
        EventKind.THREAD_INTERRUPTED ->
            if (viewpoint == other && other != 0L) "interrupted ${name(event.nodeId)}"
            else "interrupted" + (if (other != 0L) " by ${name(other)}" else "")
        EventKind.FINISHED -> event.finalState.name.lowercase()
        EventKind.UNSPECIFIED -> "unknown event"
    }
}
