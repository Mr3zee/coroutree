package kotlinx.coroutree.runtime;

/** Runtime state of a pool node: nothing but its place in the structure, which is what lets a whole pool be paused. */
final class PoolNode extends PaceNode {
    PoolNode(long id) {
        super(id);
    }

    @Override
    boolean isFinished() {
        return false;
    }
}
