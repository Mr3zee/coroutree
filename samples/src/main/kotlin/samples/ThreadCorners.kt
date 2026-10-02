package samples

import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Threads, the less obvious half. Blocking calls made of blocking calls are one blocking call: a lock, a condition
 * and a latch park inside, `join` waits inside. Timed waits that run out. A `park` that returns at once because the
 * permit was there. Interrupts of threads that wait, park and join, and of oneself. A virtual thread that parks and
 * starts a thread. A daemon that is still asleep when the JVM goes.
 */
fun main() {
    val lock = ReentrantLock()
    val condition = lock.newCondition()
    val latch = CountDownLatch(1)
    val monitor = Object()

    lock.lock()
    val locker = thread(name = "locker") { lock.lock(); lock.unlock() }
    val latched = thread(name = "latched") { latch.await() }
    val timedWaiter = thread(name = "timed waiter") { synchronized(monitor) { monitor.wait(20) } }
    val timedParker = thread(name = "timed parker") { LockSupport.parkNanos(20_000_000) }
    val permitted = thread(name = "permitted") {
        LockSupport.unpark(Thread.currentThread())
        LockSupport.park() // returns at once
    }
    val daemon = thread(name = "daemon", isDaemon = true) { Thread.sleep(60_000) }
    Thread.sleep(100) // everybody is where they are going to wait, and the timed ones are through
    locker.join(20) // runs out: the lock is still ours
    lock.unlock()
    latch.countDown()
    listOf(locker, latched, timedWaiter, timedParker, permitted).forEach { it.join() }

    val waiting = thread(name = "interrupted in wait") {
        try {
            synchronized(monitor) { monitor.wait() }
        } catch (e: InterruptedException) {
            println("wait interrupted")
        }
    }
    val parked = thread(name = "interrupted in park") {
        LockSupport.park()
        println("park returned, interrupted = ${Thread.interrupted()}")
    }
    val awaiting = thread(name = "interrupted in await") {
        lock.lock()
        try {
            condition.await()
        } catch (e: InterruptedException) {
            println("await interrupted")
        } finally {
            lock.unlock()
        }
    }
    val joining = thread(name = "interrupted in join") {
        try {
            daemon.join()
        } catch (e: InterruptedException) {
            println("join interrupted")
        }
    }
    val self = thread(name = "interrupts itself") {
        Thread.currentThread().interrupt()
        println("interrupted itself: ${Thread.interrupted()}")
    }
    Thread.sleep(100)
    listOf(waiting, parked, awaiting, joining).forEach { it.interrupt() }
    listOf(waiting, parked, awaiting, joining, self).forEach { it.join() }

    val virtual = Thread.ofVirtual().name("virtual parker").start {
        val child = thread(name = "started by a virtual thread") { Thread.sleep(20) }
        LockSupport.park()
        child.join()
    }
    Thread.sleep(100)
    LockSupport.unpark(virtual)
    virtual.join()
}
