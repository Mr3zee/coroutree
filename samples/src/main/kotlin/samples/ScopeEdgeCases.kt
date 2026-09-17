package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Corners of the scope machinery that are easy to get wrong when watching it from outside:
 * a failure that comes back from another dispatcher, a coroutine born under a parent that is already cancelled,
 * cleanup in NonCancellable (a scope whose parent is not a real job), and an exception that passes through `use`.
 */
fun main(): Unit = runBlocking {
    try {
        withContext(Dispatchers.Default + CoroutineName("remote")) {
            delay(20) // long enough for the caller to be suspended by the time this fails, in every run
            error("failed on another thread")
        }
    } catch (e: IllegalStateException) {
        println("caught: ${e.message}")
    }

    val cancelled = Job().apply { cancel() }
    launch(cancelled + CoroutineName("stillborn")) { println("never runs") }.join()

    val worker = launch(CoroutineName("worker")) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { delay(10) }
        }
    }
    yield()
    worker.cancelAndJoin()

    try {
        AutoCloseable { println("closed") }.use { error("passes through use") }
    } catch (e: IllegalStateException) {
        println("caught: ${e.message}")
    }
}
