package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Timer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.schedule

/**
 * Threads the program did not start itself: an executor's, the common ForkJoinPool's, a timer's, and virtual threads
 * of an executor. They are the libraries' threads (tasks as nodes are yet to come), but what the program's own code
 * does on them is the program's: here a `runBlocking` on a pool thread.
 */
fun main() {
    // A daemon, and never shut down: a shutdown interrupts an idle worker once or twice, as it happens.
    val pool = Executors.newFixedThreadPool(1) { runnable -> Thread(runnable, "pool-worker").apply { isDaemon = true } }
    pool.submit {
        runBlocking(CoroutineName("on the pool")) {
            launch(CoroutineName("pool child")) { delay(30) }
        }
    }.get()

    CompletableFuture.supplyAsync { Thread.sleep(30); "async" }.get()

    val fired = CountDownLatch(1)
    val timer = Timer("timer", true)
    timer.schedule(30) { fired.countDown() }
    fired.await()
    timer.cancel()

    // Not closed: close() waits for the thread to be gone, which it may or may not be by now.
    Executors.newVirtualThreadPerTaskExecutor().submit { Thread.sleep(30) }.get()
}
