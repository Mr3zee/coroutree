package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Coroutines that block the thread they run on: Thread.sleep, a nested runBlocking, and a coroutine that starts a
 * thread and joins it. All on runBlocking's single thread, so every blocking call stalls every other coroutine.
 */
fun main(): Unit = runBlocking {
    launch(CoroutineName("sleeper")) {
        Thread.sleep(20)
    }
    launch(CoroutineName("nested")) {
        runBlocking(CoroutineName("inner")) {
            delay(10)
        }
    }
    launch(CoroutineName("spawner")) {
        val helper = thread(name = "helper") { Thread.sleep(10) }
        helper.join()
    }
}
