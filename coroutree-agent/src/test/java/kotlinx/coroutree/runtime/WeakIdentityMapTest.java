package kotlinx.coroutree.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the runtime keeps its nodes for application objects that cannot carry a tag (threads, pools, continuations):
 * by identity, never by the application's equals and hashCode, and without keeping the object alive.
 */
@Timeout(60)
class WeakIdentityMapTest {
    /** Equal to everything of its kind, and counting how often the application's code was asked. */
    private static final class AllEqual {
        static final AtomicInteger ASKED = new AtomicInteger();

        @Override
        public boolean equals(Object other) {
            ASKED.incrementAndGet();
            return other instanceof AllEqual;
        }

        @Override
        public int hashCode() {
            ASKED.incrementAndGet();
            return 1;
        }
    }

    @Test
    void keysAreComparedByIdentityAndTheApplicationsEqualsIsNeverRun() {
        WeakIdentityMap<Object, String> map = new WeakIdentityMap<>();
        assertTrue(map.isEmpty());
        AllEqual first = new AllEqual(), second = new AllEqual();
        assertNull(map.putIfAbsent(first, "first"));
        assertNull(map.putIfAbsent(second, "second"), "equal is not the same");
        assertEquals("first", map.putIfAbsent(first, "again"), "the value already present is returned and kept");
        assertEquals("first", map.get(first));
        assertEquals("second", map.get(second));
        assertNull(map.get(new AllEqual()));
        assertFalse(map.isEmpty());
        assertEquals(0, AllEqual.ASKED.get(), "application code ran inside the map");

        String a = new String("thread-1"), b = new String("thread-1");
        map.putIfAbsent(a, "a");
        assertNull(map.get(b));
        assertEquals("a", map.get(a));
    }

    @Test
    void aKeyNobodyElseHoldsIsCollectedAndItsEntryGoesWithIt() throws Exception {
        WeakIdentityMap<Object, Object> map = new WeakIdentityMap<>();
        Object kept = new Object();
        map.putIfAbsent(kept, "kept");
        List<WeakReference<Object>> keys = new ArrayList<>(), values = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Object key = new Object(), value = new Object();
            map.putIfAbsent(key, value);
            keys.add(new WeakReference<>(key));
            values.add(new WeakReference<>(value));
        }
        awaitCleared(keys, map, "the map kept its keys alive");
        // Entries of collected keys are swept out when the map is next written to; a node must not outlive its thread for good.
        awaitCleared(values, map, "the map kept the values of collected keys");
        assertEquals("kept", map.get(kept));
    }

    @Test
    void aMapWhoseKeysAreAllGoneIsEmptyWithoutAnybodyWritingToIt() throws Exception {
        // isEmpty: "whether nothing was ever put, or all of it is gone". It is what lets a lookup on a hot path cost
        // nothing, so it has to become true by itself: a program that started one jobless coroutine long ago puts no more.
        WeakIdentityMap<Object, Object> map = new WeakIdentityMap<>();
        List<WeakReference<Object>> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Object key = new Object();
            map.putIfAbsent(key, "value");
            keys.add(new WeakReference<>(key));
        }
        assertFalse(map.isEmpty());
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean alive = false;
            for (WeakReference<Object> key : keys) alive |= key.get() != null;
            if (!alive) break;
            System.gc();
            Thread.sleep(10);
        }
        for (WeakReference<Object> key : keys) assertNull(key.get(), "the keys were not collected: the test cannot tell");
        // The references are enqueued a moment after they are cleared; give the map that moment, and no write.
        for (int i = 0; i < 200 && !map.isEmpty(); i++) Thread.sleep(10);
        assertTrue(map.isEmpty(), "every key is gone and the map still says it holds something");
    }

    private static void awaitCleared(List<WeakReference<Object>> references, WeakIdentityMap<Object, Object> map, String otherwise) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (true) {
            int alive = 0;
            for (WeakReference<Object> reference : references) if (reference.get() != null) alive++;
            if (alive == 0) return;
            if (System.nanoTime() > deadline) throw new AssertionError(otherwise + ": " + alive + " of " + references.size());
            System.gc();
            Thread.sleep(10);
            map.putIfAbsent(new Object(), "another write");
        }
    }

    @Test
    void threadsRacingToPutOneKeyAgreeOnOneValue() throws Exception {
        WeakIdentityMap<Object, Integer> map = new WeakIdentityMap<>();
        int threads = 8, keys = 2_000;
        Object[] shared = new Object[keys];
        for (int i = 0; i < keys; i++) shared[i] = new Object();
        Set<String> disagreements = ConcurrentHashMap.newKeySet();
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> racers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int mine = t;
            Thread racer = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < keys; i++) {
                    // What Hooks does for a thread it has not seen: make a node, put it, take whichever got there first.
                    Integer raced = map.putIfAbsent(shared[i], mine);
                    if (raced == null) winners.incrementAndGet();
                    int settled = raced == null ? mine : raced;
                    Integer read = map.get(shared[i]);
                    if (read == null || read != settled) disagreements.add("key " + i + ": put said " + settled + ", get says " + read);
                }
            });
            racer.start();
            racers.add(racer);
        }
        go.countDown();
        for (Thread racer : racers) racer.join();
        assertEquals(Set.of(), disagreements);
        assertEquals(keys, winners.get(), "exactly one put per key was the first");
        for (Object key : shared) assertSame(map.get(key), map.get(key));
    }
}
