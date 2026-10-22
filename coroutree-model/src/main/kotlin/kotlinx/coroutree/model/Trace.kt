@file:UseSerializers(
    EventKindSerializer::class, NodeKindSerializer::class, NodeStateSerializer::class, OriginSerializer::class,
    BlockReasonSerializer::class, HandledBySerializer::class, PropagationDirectionSerializer::class,
    ContextElementKindSerializer::class, SeveritySerializer::class, PaceReasonSerializer::class,
)

package kotlinx.coroutree.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

/*
 * Schema in .proto notation: docs/TRACE_FORMAT.md.
 *
 * The agent encodes the same messages by hand in kotlinx.coroutree.runtime.TraceEncoder, so the field and enum
 * numbers are the contract between the two. WireCompatibilityTest checks that they agree.
 *
 * Proto3 conventions:
 *
 * - Fields with zero defaults are omitted from the wire.
 * - Node id 0 means "none". Line number 0 means "unknown".
 * - An enum number a reader doesn't know reads as `UNSPECIFIED` (OpenEnums.kt): proto3 enums are open.
 * - Enums start with UNSPECIFIED at 0.
 */

/** One length-delimited record of a trace file or live stream. Exactly one field is set. */
@Serializable
public data class Frame(
    @ProtoNumber(1) val header: TraceHeader? = null,
    @ProtoNumber(2) val event: Event? = null,
    @ProtoNumber(3) val stackFrame: StackFrameDef? = null,
    @ProtoNumber(4) val diagnostic: Diagnostic? = null,
    @ProtoNumber(5) val pace: PaceDef? = null,
)

/** Always the first frame. */
@Serializable
public data class TraceHeader(
    @ProtoNumber(1) val formatVersion: Int = 0,
    @ProtoNumber(2) val agentVersion: String = "",
    /** Shared by all JVMs forked by one Gradle invocation. */
    @ProtoNumber(3) val buildId: String = "",
    /** Gradle path of the task that forked the JVM, empty when the agent was attached by hand. */
    @ProtoNumber(4) val taskPath: String = "",
    @ProtoNumber(5) val jvm: JvmInfo = JvmInfo(),
    @ProtoNumber(6) val sourceIndex: SourceIndex = SourceIndex(),
    /** Package prefixes tagged as project code. Empty means "everything that isn't a known runtime". */
    @ProtoNumber(7) val includePackages: List<String> = emptyList(),
    @ProtoNumber(8) val excludePackages: List<String> = emptyList(),
    @ProtoNumber(9) val startedAtEpochMillis: Long = 0,
    /** Absolute path of the root project directory, empty when unknown. */
    @ProtoNumber(10) val projectDir: String = "",
    /**
     * Whether the agent can hold the program at its events for execution control. Default is false.
     * Only paceable traces use these fields and frames:
     *
     * - [Event.heldNanos]
     * - [Event.sameStep]
     * - [PaceDef]
     */
    @ProtoNumber(11) val paceable: Boolean = false,
) {
    public companion object {
        public const val FORMAT_VERSION: Int = 1
    }
}

@Serializable
public data class JvmInfo(
    @ProtoNumber(1) val pid: Long = 0,
    @ProtoNumber(2) val javaVersion: String = "",
    @ProtoNumber(3) val vmName: String = "",
    @ProtoNumber(4) val vmVersion: String = "",
    /** `sun.java.command`: main class (or jar) and program arguments. */
    @ProtoNumber(5) val command: String = "",
)

/**
 * Maps `(package, source file name)` to a file on disk.
 * Serialized module indexes can be concatenated byte by byte because protobuf merges the repeated field.
 */
@Serializable
public data class SourceIndex(
    @ProtoNumber(1) val modules: List<SourceModule> = emptyList(),
)

@Serializable
public data class SourceModule(
    /** Gradle project path, for example `:app`. */
    @ProtoNumber(1) val path: String = "",
    /** Absolute path of the module's project directory. */
    @ProtoNumber(2) val rootDir: String = "",
    @ProtoNumber(3) val files: List<SourceFile> = emptyList(),
)

@Serializable
public data class SourceFile(
    @ProtoNumber(1) val packageName: String = "",
    @ProtoNumber(2) val fileName: String = "",
    /** Path relative to [SourceModule.rootDir], `/`-separated. */
    @ProtoNumber(3) val path: String = "",
)

/** Interned stack frame. Defined once, before the first event that refers to it. Ids start at 1. */
@Serializable
public data class StackFrameDef(
    @ProtoNumber(1) val id: Int = 0,
    @ProtoNumber(2) val className: String = "",
    @ProtoNumber(3) val methodName: String = "",
    @ProtoNumber(4) val fileName: String = "",
    @ProtoNumber(5) val line: Int = 0,
    /**
     * Whether this frame is the body of an inline function that runs in the method of the next frame. Default is false.
     * [className] identifies the class that declares the function.
     * [methodName] identifies the function, or is empty when unknown.
     */
    @ProtoNumber(6) val inlined: Boolean = false,
)

/** A capture diagnostic, such as an unsupported library version. */
@Serializable
public data class Diagnostic(
    @ProtoNumber(1) val severity: Severity = Severity.UNSPECIFIED,
    @ProtoNumber(2) val message: String = "",
) {
    @Serializable
    public enum class Severity {
        @ProtoNumber(0) UNSPECIFIED,
        @ProtoNumber(1) INFO,
        @ProtoNumber(2) WARNING,
        @ProtoNumber(3) ERROR,
    }
}

@Serializable
public data class Event(
    /** Global, dense, strictly increasing order of events across all threads. Starts at 1. */
    @ProtoNumber(1) val seq: Long = 0,
    /** Nanoseconds since the trace started. */
    @ProtoNumber(2) val timeNanos: Long = 0,
    /** Node this event belongs to. */
    @ProtoNumber(3) val nodeId: Long = 0,
    @ProtoNumber(4) val kind: EventKind = EventKind.UNSPECIFIED,
    /** Node id of the thread the event was captured on. */
    @ProtoNumber(5) val threadId: Long = 0,
    /**
     * Stack frame ids, innermost first.
     * For [EventKind.SUSPENDED], this is the coroutine's logical stack from continuation debug metadata.
     * Other event kinds use the JVM stack.
     */
    @ProtoNumber(6) @ProtoPacked val stack: List<Int> = emptyList(),
    /** Definition of the node, for [EventKind.LAUNCHED] and [EventKind.DISCOVERED]. */
    @ProtoNumber(7) val node: NodeInfo? = null,
    /**
     * The other node involved, or 0 if none. The event belongs to [nodeId], the node it happens to.
     * This field identifies where it came from:
     *
     * - [EventKind.CANCELLATION_PROPAGATED]: the parent or child that propagated cancellation.
     * - [EventKind.EXCEPTION_PROPAGATED]: the child that propagated the exception.
     * - [EventKind.EXCEPTION_HANDLED] with [HandledBy.SUPERVISOR]: the child whose exception the supervisor handled.
     * - [EventKind.CANCELLATION_REQUESTED] or [EventKind.THREAD_INTERRUPTED]: the requester.
     * - [EventKind.THREAD_BLOCKED] or [EventKind.THREAD_UNBLOCKED]: the coroutine that the thread was running.
     */
    @ProtoNumber(8) val otherNodeId: Long = 0,
    @ProtoNumber(9) val exception: ExceptionInfo? = null,
    /** For [EventKind.CONTEXT_CHANGED] and [EventKind.DISPATCHER_CHANGED]. */
    @ProtoNumber(10) val contextDiff: List<ContextChange> = emptyList(),
    @ProtoNumber(11) val blockReason: BlockReason = BlockReason.UNSPECIFIED,
    @ProtoNumber(12) val handledBy: HandledBy = HandledBy.UNSPECIFIED,
    @ProtoNumber(13) val direction: PropagationDirection = PropagationDirection.UNSPECIFIED,
    /** For [EventKind.FINISHED]: one of COMPLETED, FAILED, CANCELLED. */
    @ProtoNumber(14) val finalState: NodeState = NodeState.UNSPECIFIED,
    /**
     * Nanoseconds the agent held the thread for execution control since the thread's previous event. Default is 0.
     * A hold is neither an event nor a state. This field is its only record.
     */
    @ProtoNumber(15) val heldNanos: Long = 0,
    /**
     * Whether this event is in the same step as the previous event of [threadId]. Default is false.
     * No program code runs between the events of one step, such as *launched* and *context changed*.
     * Pacing and `step n` count steps.
     * Only written by a JVM that is [TraceHeader.paceable].
     */
    @ProtoNumber(16) val sameStep: Boolean = false,
)

/**
 * An execution-control setting, in effect after the event [afterSeq].
 * [scopeNodeId] 0 selects the global setting. A nonzero id selects that node's structural subtree.
 * A setting has no [Event.seq] because it isn't a program event.
 */
@Serializable
public data class PaceDef(
    /** Nanoseconds since the trace started. */
    @ProtoNumber(1) val timeNanos: Long = 0,
    /** The latest sequence number issued when the setting changed, or 0 before the first event. */
    @ProtoNumber(2) val afterSeq: Long = 0,
    @ProtoNumber(3) val scopeNodeId: Long = 0,
    /** Minimum interval between two steps of one sequence, in nanoseconds. Default is 0 for no limit. */
    @ProtoNumber(4) val intervalNanos: Long = 0,
    @ProtoNumber(5) val paused: Boolean = false,
    /** Steps this change let through a paused gate (`step n`), 0 for any other change. */
    @ProtoNumber(6) val steps: Int = 0,
    @ProtoNumber(7) val reason: Reason = Reason.UNSPECIFIED,
    /** Whether the node lost its own setting, so its parent's governs it again. Default is false. Never true for the global setting. */
    @ProtoNumber(8) val dropped: Boolean = false,
) {
    @Serializable
    public enum class Reason {
        @ProtoNumber(0) UNSPECIFIED,
        /** What the run was configured to start with. */
        @ProtoNumber(1) CONFIG,
        /** A command of a live client. */
        @ProtoNumber(2) CONTROLLER,
        /** The last controller left, or the JVM has no live socket for one. Restores the configured pace, with nothing paused. */
        @ProtoNumber(3) FAIL_OPEN,
        /** JVM shutdown releases all holds. */
        @ProtoNumber(4) SHUTDOWN,
        /** The node that carried the setting has ended. */
        @ProtoNumber(5) NODE_FINISHED,
    }
}

@Serializable
public enum class EventKind {
    @ProtoNumber(0) UNSPECIFIED,
    /** The node was created under observation. Carries [Event.node]. */
    @ProtoNumber(1) LAUNCHED,
    /** The node already existed when the agent reached it, such as the main thread. Carries [Event.node]. */
    @ProtoNumber(2) DISCOVERED,
    @ProtoNumber(3) CONTEXT_CHANGED,
    @ProtoNumber(4) DISPATCHER_CHANGED,
    @ProtoNumber(5) SUSPENDED,
    @ProtoNumber(6) RESUMED,
    @ProtoNumber(7) EXCEPTION_THROWN,
    @ProtoNumber(8) EXCEPTION_PROPAGATED,
    @ProtoNumber(9) EXCEPTION_HANDLED,
    @ProtoNumber(10) CANCELLATION_REQUESTED,
    @ProtoNumber(11) CANCELLATION_PROPAGATED,
    /** The job entered the cancelling state. [Event.exception] is the cause. */
    @ProtoNumber(12) CANCELLING,
    @ProtoNumber(13) THREAD_BLOCKED,
    @ProtoNumber(14) THREAD_UNBLOCKED,
    @ProtoNumber(15) THREAD_INTERRUPTED,
    /** End of the node's lifetime, with the outcome in [Event.finalState]. */
    @ProtoNumber(16) FINISHED,
}

@Serializable
public enum class NodeKind {
    @ProtoNumber(0) UNSPECIFIED,
    @ProtoNumber(1) THREAD,
    @ProtoNumber(2) COROUTINE,
    @ProtoNumber(3) SCOPE,
    @ProtoNumber(4) CONTEXT_CHANGE,
    @ProtoNumber(5) TASK,
    @ProtoNumber(6) POOL,
}

@Serializable
public enum class NodeState {
    @ProtoNumber(0) UNSPECIFIED,
    @ProtoNumber(1) ACTIVE,
    @ProtoNumber(2) SUSPENDED,
    @ProtoNumber(3) BLOCKED,
    @ProtoNumber(4) CANCELLING,
    @ProtoNumber(5) COMPLETED,
    @ProtoNumber(6) FAILED,
    @ProtoNumber(7) CANCELLED;

    public val isFinal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

@Serializable
public enum class Origin {
    @ProtoNumber(0) UNSPECIFIED,
    @ProtoNumber(1) PROJECT,
    @ProtoNumber(2) LIBRARY,
}

@Serializable
public enum class BlockReason {
    @ProtoNumber(0) UNSPECIFIED,
    @ProtoNumber(1) MONITOR,
    @ProtoNumber(2) WAIT,
    @ProtoNumber(3) JOIN,
    @ProtoNumber(4) PARK,
    @ProtoNumber(5) SLEEP,
    @ProtoNumber(6) IO,
    @ProtoNumber(7) RUN_BLOCKING,
}

@Serializable
public enum class HandledBy {
    @ProtoNumber(0) UNSPECIFIED,
    @ProtoNumber(1) CATCH,
    @ProtoNumber(2) COROUTINE_EXCEPTION_HANDLER,
    /** The exception stopped at a supervisor (`supervisorScope` / `SupervisorJob`) and didn't cancel it. */
    @ProtoNumber(3) SUPERVISOR,
    /** Stored in a `Deferred` until somebody awaits it. */
    @ProtoNumber(4) DEFERRED_HELD,
    @ProtoNumber(5) UNCAUGHT_EXCEPTION_HANDLER,
}

@Serializable
public enum class PropagationDirection {
    @ProtoNumber(0) UNSPECIFIED,
    @ProtoNumber(1) PARENT_TO_CHILD,
    @ProtoNumber(2) CHILD_TO_PARENT,
}

@Serializable
public data class NodeInfo(
    /** Unique in a trace, never reused. Starts at 1. */
    @ProtoNumber(1) val id: Long = 0,
    @ProtoNumber(2) val kind: NodeKind = NodeKind.UNSPECIFIED,
    /** The source construct that created the node: `launch`, `withContext`, `Thread.start`, `Job()`, … */
    @ProtoNumber(3) val construct: String = "",
    /** `CoroutineName` or thread name. Empty when the node is anonymous. */
    @ProtoNumber(4) val name: String = "",
    /** Structural parent (Job hierarchy / starting thread / owning pool), 0 for a root. */
    @ProtoNumber(5) val parentId: Long = 0,
    /** Execution unit whose code created this node, 0 if unknown. A cross-link, not a tree edge. */
    @ProtoNumber(6) val creatorId: Long = 0,
    /** Stack frame id of the source site: the innermost frame of the creating stack outside the concurrency runtime. */
    @ProtoNumber(7) val siteFrame: Int = 0,
    @ProtoNumber(8) val origin: Origin = Origin.UNSPECIFIED,
    @ProtoNumber(9) val context: List<ContextElement> = emptyList(),
    /** Runtime class that implements the node, for example `kotlinx.coroutines.StandaloneCoroutine`. */
    @ProtoNumber(10) val implClass: String = "",
    @ProtoNumber(11) val thread: ThreadInfo? = null,
)

@Serializable
public data class ThreadInfo(
    @ProtoNumber(1) val tid: Long = 0,
    @ProtoNumber(2) val virtual: Boolean = false,
    @ProtoNumber(3) val daemon: Boolean = false,
)

@Serializable
public enum class ContextElementKind {
    @ProtoNumber(0) UNSPECIFIED,
    @ProtoNumber(1) JOB,
    @ProtoNumber(2) DISPATCHER,
    @ProtoNumber(3) NAME,
    @ProtoNumber(4) EXCEPTION_HANDLER,
    @ProtoNumber(5) OTHER,
}

@Serializable
public data class ContextElement(
    @ProtoNumber(1) val kind: ContextElementKind = ContextElementKind.UNSPECIFIED,
    /** Short well-known key (`Job`, `Dispatcher`, `CoroutineName`, `CoroutineExceptionHandler`) or the element's class name. */
    @ProtoNumber(2) val key: String = "",
    /** The element's `toString()` value, verbatim. Empty for the Job element because the node identifies the job. */
    @ProtoNumber(3) val value: String = "",
    /** Whether the element is a `ThreadContextElement`, which changes thread state on every resume and suspend. Default is false. */
    @ProtoNumber(4) val threadContextElement: Boolean = false,
)

/** One element of a context diff. Empty [oldValue] with [added], or empty [newValue] with [removed]. */
@Serializable
public data class ContextChange(
    @ProtoNumber(1) val kind: ContextElementKind = ContextElementKind.UNSPECIFIED,
    @ProtoNumber(2) val key: String = "",
    @ProtoNumber(3) val oldValue: String = "",
    @ProtoNumber(4) val newValue: String = "",
    @ProtoNumber(5) val added: Boolean = false,
    @ProtoNumber(6) val removed: Boolean = false,
)

@Serializable
public data class ExceptionInfo(
    @ProtoNumber(1) val className: String = "",
    @ProtoNumber(2) val message: String = "",
    /** The throwable's own stack trace (frame ids, innermost first): where it was thrown, not where it was seen. */
    @ProtoNumber(3) @ProtoPacked val stack: List<Int> = emptyList(),
    /** Identity of the throwable instance, to follow one exception across events. Unique among live throwables only. */
    @ProtoNumber(4) val identity: Int = 0,
    @ProtoNumber(5) val cancellation: Boolean = false,
)
