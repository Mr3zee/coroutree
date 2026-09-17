package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Threads and a coroutine blocked on `synchronized`: the blocking that bytecode instrumentation cannot see and the
 * agent's native monitor probe reports. The holder keeps the lock long enough for everybody else to run into it.
 */
fun main() {
    val lock = Any()
    val holder = thread(name = "holder") {
        synchronized(lock) { Thread.sleep(300) }
    }
    Thread.sleep(50) // the holder has the lock by now
    val contender = thread(name = "contender") {
        synchronized(lock) { println("contender got the lock") }
    }
    runBlocking {
        launch(Dispatchers.Default + CoroutineName("contending coroutine")) {
            synchronized(lock) { println("coroutine got the lock") }
        }
    }
    listOf(holder, contender).forEach { it.join() }
}
