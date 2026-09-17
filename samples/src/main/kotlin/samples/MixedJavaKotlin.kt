package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Java threads that enter coroutines: every Java-started thread runs its own runBlocking, the second one fails and
 * the exception travels out of the coroutine, through runBlocking, into a Java catch block.
 */
fun main() {
    JavaWorkers.runAll(2) {
        val worker = Thread.currentThread().name
        runBlocking(CoroutineName("call from $worker")) {
            launch { delay(10) }
            if (worker.endsWith("1")) error("$worker failed")
        }
    }
}
