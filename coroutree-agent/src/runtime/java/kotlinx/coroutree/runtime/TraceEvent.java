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
    /** How long the gate held this thread since its previous event. Always 0 without a gate. */
    long heldNanos;
    /** Not the first event of its step: there was no program code between the previous event of this thread and this one. */
    boolean sameStep;

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

    /**
     * A setting of the gate as it is from now on. Travels through the queue like an event but takes no sequence number:
     * {@code afterSeq}, the latest one handed out when it was made, says where among the events it belongs.
     */
    static final class PaceDef {
        long timeNanos;
        long afterSeq;
        /** 0: the global setting. */
        long scopeNodeId;
        long intervalNanos;
        boolean paused;
        /** Permits this change granted ({@code step n}), 0 for any other. */
        int steps;
        int reason;
        /** The node's setting is gone; it goes by its parent's again. */
        boolean dropped;
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
