package kotlinx.coroutree.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where the runtime keeps its nodes for application objects that cannot carry a tag (threads, pools, continuations):
 * by identity, never by the application's equals and hashCode, and without keeping the object alive.
 */
@Timeout(60)
internal class WeakIdentityMapTest {
    /** Equal to everything of its kind, and counting how often the application's code was asked. */
    private class AllEqual {
        override fun equals(other: Any?): Boolean {
            ASKED.incrementAndGet()
            return other is AllEqual
        }

        override fun hashCode(): Int {
            ASKED.incrementAndGet()
            return 1
        }

        companion object {
            val ASKED = AtomicInteger()
        }
    }

    @Test
    fun keysAreComparedByIdentityAndTheApplicationsEqualsIsNeverRun() {
        val map = WeakIdentityMap<Any, String>()
        assertTrue(map.isEmpty())
        val first = AllEqual()
        val second = AllEqual()
        assertNull(map.putIfAbsent(first, "first"))
        assertNull(map.putIfAbsent(second, "second"), "equal is not the same")
        assertEquals("first", map.putIfAbsent(first, "again"), "the value already present is returned and kept")
        assertEquals("first", map.get(first))
        assertEquals("second", map.get(second))
        assertNull(map.get(AllEqual()))
        assertFalse(map.isEmpty())
        assertEquals(0, AllEqual.ASKED.get(), "application code ran inside the map")

        // Two strings that are equal and not the same object.
        val a = String(charArrayOf('t', 'h', 'r', 'e', 'a', 'd'))
        val b = String(charArrayOf('t', 'h', 'r', 'e', 'a', 'd'))
        map.putIfAbsent(a, "a")
        assertNull(map.get(b))
        assertEquals("a", map.get(a))
    }

    @Test
    fun aKeyNobodyElseHoldsIsCollectedAndItsEntryGoesWithIt() {
        val map = WeakIdentityMap<Any, Any>()
        val kept = Any()
        map.putIfAbsent(kept, "kept")
        val keys = ArrayList<WeakReference<Any>>()
        val values = ArrayList<WeakReference<Any>>()
        repeat(100) {
            val key = Any()
            val value = Any()
            map.putIfAbsent(key, value)
            keys.add(WeakReference(key))
            values.add(WeakReference(value))
        }
        awaitCleared(keys, map, "the map kept its keys alive")
        // Entries of collected keys are swept out when the map is next written to; a node must not outlive its thread for good.
        awaitCleared(values, map, "the map kept the values of collected keys")
        assertEquals("kept", map.get(kept))
    }

    @Test
    fun aMapWhoseKeysAreAllGoneIsEmptyWithoutAnybodyWritingToIt() {
        // isEmpty: "whether nothing was ever put, or all of it is gone". It is what lets a lookup on a hot path cost
        // nothing, so it has to become true by itself: a program that started one jobless coroutine long ago puts no more.
        val map = WeakIdentityMap<Any, Any>()
        val keys = ArrayList<WeakReference<Any>>()
        repeat(10) {
            val key = Any()
            map.putIfAbsent(key, "value")
            keys.add(WeakReference(key))
        }
        assertFalse(map.isEmpty())
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline && keys.any { it.get() != null }) {
            System.gc()
            Thread.sleep(10)
        }
        for (key in keys) assertNull(key.get(), "the keys were not collected: the test cannot tell")
        // The references are enqueued a moment after they are cleared; give the map that moment, and no write.
        var i = 0
        while (i < 200 && !map.isEmpty()) {
            Thread.sleep(10)
            i++
        }
        assertTrue(map.isEmpty(), "every key is gone and the map still says it holds something")
    }

    @Test
    fun threadsRacingToPutOneKeyAgreeOnOneValue() {
        val map = WeakIdentityMap<Any, Int>()
        val threads = 8
        val keys = 2000
        val shared = Array<Any>(keys) { Any() }
        val disagreements: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val winners = AtomicInteger()
        val go = CountDownLatch(1)
        val racers = (0 until threads).map { mine ->
            Thread {
                go.await()
                for (i in 0 until keys) {
                    // What Hooks does for a thread it has not seen: make a node, put it, take whichever got there first.
                    val raced = map.putIfAbsent(shared[i], mine)
                    if (raced == null) winners.incrementAndGet()
                    val settled = raced ?: mine
                    val read = map.get(shared[i])
                    if (read == null || read != settled) disagreements.add("key $i: put said $settled, get says $read")
                }
            }.apply { start() }
        }
        go.countDown()
        for (racer in racers) racer.join()
        assertEquals(emptySet<String>(), disagreements)
        assertEquals(keys, winners.get(), "exactly one put per key was the first")
        for (key in shared) assertSame(map.get(key), map.get(key))
    }

    companion object {
        private fun awaitCleared(references: List<WeakReference<Any>>, map: WeakIdentityMap<Any, Any>, otherwise: String) {
            val deadline = System.nanoTime() + 20_000_000_000L
            while (true) {
                val alive = references.count { it.get() != null }
                if (alive == 0) return
                if (System.nanoTime() > deadline) throw AssertionError("$otherwise: $alive of ${references.size}")
                System.gc()
                Thread.sleep(10)
                map.putIfAbsent(Any(), "another write")
            }
        }
    }
}
