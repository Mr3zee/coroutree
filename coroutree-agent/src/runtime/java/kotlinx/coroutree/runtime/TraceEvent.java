package kotlinx.coroutree.runtime;

/**
 * An event on its way from a hook to the writer thread. Not touched by the hook once enqueued. Application objects
 * were turned into strings and numbers on the thread where the event happened; the writer never calls into them.
 */
final class TraceEvent {
    /** Assigned by {@link Tracer#emit} at the moment the event is enqueued, so that no number is ever skipped. */
    long seq;
    long timeNanos;
    final long nodeId;
    final int kind;
    final long threadNodeId;

    StackFrameRef[] stack;
    NodeDef node;
    long otherNodeId;
    ExceptionDef exception;
    ContextChangeDef[] contextDiff;
    int blockReason;
    int handledBy;
    int direction;
    int finalState;

    TraceEvent(long nodeId, int kind, long threadNodeId) {
        this.nodeId = nodeId;
        this.kind = kind;
        this.threadNodeId = threadNodeId;
    }

    static final class NodeDef {
        long id;
        int kind;
        String construct;
        String name;
        long parentId;
        long creatorId;
        StackFrameRef siteFrame;
        int origin;
        ContextEntry[] context;
        String implClass;
        boolean isThread;
        long tid;
        boolean virtual;
        boolean daemon;
    }

    static final class ExceptionDef {
        String className;
        String message;
        StackFrameRef[] stack;
        int identity;
        boolean cancellation;
    }

    static final class ContextChangeDef {
        int kind;
        String key;
        String oldValue;
        String newValue;
        boolean added;
        boolean removed;
    }

    /** A diagnostic message travels through the same queue so that it lands in the trace in order. */
    static final class DiagnosticDef {
        final int severity;
        final String message;

        DiagnosticDef(int severity, String message) {
            this.severity = severity;
            this.message = message;
        }
    }
}
