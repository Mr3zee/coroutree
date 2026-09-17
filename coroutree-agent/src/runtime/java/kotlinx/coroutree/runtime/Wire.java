package kotlinx.coroutree.runtime;

/**
 * Numbers of the trace format: enum values and the format version. The message layouts themselves are in
 * {@link TraceEncoder}. Mirrors coroutree-model's Trace.kt; see docs/TRACE_FORMAT.md.
 */
public final class Wire {
    private Wire() {}

    public static final int FORMAT_VERSION = 1;

    static final byte[] MAGIC = {'C', 'O', 'R', 'O', 'T', 'R', 'E', 'E'};

    // EventKind
    static final int LAUNCHED = 1;
    static final int DISCOVERED = 2;
    static final int CONTEXT_CHANGED = 3;
    static final int DISPATCHER_CHANGED = 4;
    static final int SUSPENDED = 5;
    static final int RESUMED = 6;
    static final int EXCEPTION_THROWN = 7;
    static final int EXCEPTION_PROPAGATED = 8;
    static final int EXCEPTION_HANDLED = 9;
    static final int CANCELLATION_REQUESTED = 10;
    static final int CANCELLATION_PROPAGATED = 11;
    static final int CANCELLING = 12;
    static final int THREAD_BLOCKED = 13;
    static final int THREAD_UNBLOCKED = 14;
    static final int THREAD_INTERRUPTED = 15;
    static final int FINISHED = 16;

    // NodeKind
    static final int KIND_THREAD = 1;
    static final int KIND_COROUTINE = 2;
    static final int KIND_SCOPE = 3;
    static final int KIND_CONTEXT_CHANGE = 4;
    static final int KIND_TASK = 5;
    static final int KIND_POOL = 6;

    // NodeState, final states only
    static final int STATE_COMPLETED = 5;
    static final int STATE_FAILED = 6;
    static final int STATE_CANCELLED = 7;

    // Origin
    static final int ORIGIN_PROJECT = 1;
    static final int ORIGIN_LIBRARY = 2;

    // BlockReason. Public: instrumented code passes these to Hooks.blockEnter.
    public static final int BLOCK_MONITOR = 1;
    public static final int BLOCK_WAIT = 2;
    public static final int BLOCK_JOIN = 3;
    public static final int BLOCK_PARK = 4;
    public static final int BLOCK_SLEEP = 5;
    public static final int BLOCK_IO = 6;
    public static final int BLOCK_RUN_BLOCKING = 7;

    // HandledBy
    static final int BY_CATCH = 1;
    static final int BY_COROUTINE_EXCEPTION_HANDLER = 2;
    static final int BY_SUPERVISOR = 3;
    static final int BY_DEFERRED_HELD = 4;
    static final int BY_UNCAUGHT_EXCEPTION_HANDLER = 5;

    // PropagationDirection
    static final int PARENT_TO_CHILD = 1;
    static final int CHILD_TO_PARENT = 2;

    // ContextElementKind
    static final int CTX_JOB = 1;
    static final int CTX_DISPATCHER = 2;
    static final int CTX_NAME = 3;
    static final int CTX_EXCEPTION_HANDLER = 4;
    static final int CTX_OTHER = 5;

    // Diagnostic.Severity
    public static final int INFO = 1;
    public static final int WARNING = 2;
    public static final int ERROR = 3;
}
