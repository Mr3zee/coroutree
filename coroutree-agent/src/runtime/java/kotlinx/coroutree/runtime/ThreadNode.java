package kotlinx.coroutree.runtime;

/** Runtime state of a thread node. */
final class ThreadNode extends PaceNode {
    /** Died of an uncaught exception. */
    volatile boolean failed;
    volatile boolean finished;
    /** Whether the definition is in the trace; false for a thread that was heard of (interrupted) before it was started. */
    volatile boolean defined;

    ThreadNode(long id) {
        super(id);
    }

    @Override
    boolean isFinished() {
        return finished;
    }
}
