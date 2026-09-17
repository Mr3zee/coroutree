package samples

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** Plain threads: start and join, sleep, wait/notify, park, an interrupt, a thread that starts a thread, an uncaught exception. */
fun main() {
    val lock = Object()
    var ready = false

    val waiter = thread(name = "waiter") {
        synchronized(lock) {
            while (!ready) lock.wait()
        }
    }
    val sleeper = thread(name = "sleeper") {
        try {
            Thread.sleep(60_000)
        } catch (e: InterruptedException) {
            println("sleeper interrupted")
        }
    }
    val released = AtomicBoolean()
    val parker = thread(name = "parker") {
        while (!released.get()) LockSupport.park()
    }
    val outer = Thread({
        val inner = Thread({ Thread.sleep(10) }, "inner")
        inner.start()
        inner.join()
    }, "outer")
    outer.start()

    Thread.sleep(50)
    synchronized(lock) {
        ready = true
        lock.notifyAll()
    }
    sleeper.interrupt()
    released.set(true)
    LockSupport.unpark(parker)

    // Legal, if unusual: the interrupt is remembered and the thread starts with its flag set.
    val interruptedEarly = Thread({ println("started interrupted: ${Thread.interrupted()}") }, "interrupted early")
    interruptedEarly.interrupt()
    interruptedEarly.start()

    val failing = Thread({ throw IllegalStateException("thread failed") }, "failing")
    failing.setUncaughtExceptionHandler { t, e -> println("${t.name} died: ${e.message}") }
    failing.start()

    listOf(waiter, sleeper, parker, outer, interruptedEarly, failing).forEach { it.join() }
}
