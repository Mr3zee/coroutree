package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Coroutines that block a dispatcher's thread, which is theirs to block for the moment: on a lock that the main
 * thread holds, in a `runBlocking` with an event loop and a child of its own, and in the `join` of a thread the
 * coroutine started (a `join` waits inside; it is one blocking call).
 */
fun main(): Unit = runBlocking {
    val lock = ReentrantLock()
    lock.lock() // runBlocking's coroutine never leaves the main thread, so the lock is its thread's to unlock
    launch(Dispatchers.IO + CoroutineName("wants the lock")) {
        lock.lock()
        lock.unlock()
    }
    launch(Dispatchers.Default + CoroutineName("bridge")) {
        runBlocking(CoroutineName("inner loop")) {
            launch(CoroutineName("inner child")) { delay(20) }
        }
    }
    launch(Dispatchers.IO + CoroutineName("joins a thread")) {
        thread(name = "short") { Thread.sleep(20) }.join(10_000)
    }
    delay(150) // in runBlocking's own event loop
    lock.unlock()
}
