package kotlinx.coroutree.runtime;

/**
 * Implemented by {@code kotlinx.coroutines.JobSupport} after instrumentation: a slot on every job where the runtime
 * keeps its per-node state, so that finding the node of a job costs a field read instead of a weak-map lookup.
 */
public interface Tagged {
    Object coroutree$tag();

    void coroutree$tag(Object tag);
}
