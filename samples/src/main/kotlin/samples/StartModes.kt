package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/** Start modes: a lazy coroutine that is started later, one that is never started, and an undispatched one. */
fun main(): Unit = runBlocking {
    val lazy = async(CoroutineName("lazy"), start = CoroutineStart.LAZY) { 42 }
    val neverStarted = launch(CoroutineName("never started"), start = CoroutineStart.LAZY) { error("unreachable") }
    launch(CoroutineName("undispatched"), start = CoroutineStart.UNDISPATCHED) {
        println("runs before launch returns")
        delay(10)
    }
    yield()
    println(lazy.await())
    neverStarted.cancel()
}
