package kotlinx.coroutree.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/** Concurrent map with weakly held keys compared by identity. For threads and pools, which cannot carry a tag field. */
final class WeakIdentityMap<K, V> {
    private final ConcurrentHashMap<Object, V> map = new ConcurrentHashMap<>();
    private final ReferenceQueue<K> collected = new ReferenceQueue<>();

    V get(K key) {
        return map.get(new Lookup(key));
    }

    /** Returns the value already present, or {@code null} if {@code value} was put. */
    V putIfAbsent(K key, V value) {
        expunge();
        return map.putIfAbsent(new WeakKey<>(key, collected), value);
    }

    private void expunge() {
        for (Object key = collected.poll(); key != null; key = collected.poll()) map.remove(key);
    }

    private static final class WeakKey<K> extends WeakReference<K> {
        private final int hash;

        WeakKey(K referent, ReferenceQueue<K> queue) {
            super(referent, queue);
            hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            Object mine = get();
            if (mine == null) return false;
            if (other instanceof WeakKey<?> that) return that.get() == mine;
            return other instanceof Lookup that && that.referent == mine;
        }
    }

    /** Strong, allocation-cheap key for lookups. */
    private static final class Lookup {
        final Object referent;

        Lookup(Object referent) {
            this.referent = referent;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(referent);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WeakKey<?> that && that.get() == referent;
        }
    }
}
