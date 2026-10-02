package samples

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield

/**
 * Where the failure of an `async` goes. Under a supervisor it stays in the Deferred: awaited, it is thrown at the
 * awaiter (who catches it here, or fails with it in turn); never awaited, nobody hears of it. Under an ordinary scope
 * it is held in the Deferred and takes the scope down as well. And an awaiter that is cancelled while it waits leaves
 * the Deferred alone.
 */
fun main(): Unit = runBlocking {
    val handler = CoroutineExceptionHandler { _, e -> println("handled ${e.message}") }

    supervisorScope {
        val awaited = async(CoroutineName("awaited")) {
            delay(10)
            throw IllegalStateException("awaited")
        }
        try {
            awaited.await()
        } catch (e: IllegalStateException) {
            println("await threw ${e.message}")
        }
        async(CoroutineName("never awaited")) {
            delay(10)
            throw IllegalStateException("lost")
        }
        launch(CoroutineName("awaiter") + handler) {
            awaited.await() // it has failed already: the awaiter fails with it without ever suspending
        }
    }

    try {
        coroutineScope {
            async(CoroutineName("structured")) {
                delay(10)
                throw IllegalArgumentException("structured")
            }
            launch(CoroutineName("bystander")) { awaitCancellation() }
        }
    } catch (e: IllegalArgumentException) {
        println("the scope threw ${e.message}")
    }

    val answer = CompletableDeferred<Int>()
    val slow = async(CoroutineName("slow")) { answer.await() }
    val waiter = launch(CoroutineName("waiter")) { slow.await() }
    yield()
    waiter.cancelAndJoin()
    answer.complete(42)
    println(slow.await())
}
