package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Real parallelism: coroutines on Dispatchers.Default hopping between worker threads, one of them blocking a worker.
 * Which worker runs what differs from run to run; the tree does not.
 */
fun main(): Unit = runBlocking {
    repeat(4) { index ->
        launch(Dispatchers.Default + CoroutineName("worker-$index")) {
            repeat(3) { delay(5) }
            if (index == 0) Thread.sleep(20)
        }
    }
    withContext(Dispatchers.IO + CoroutineName("io")) {
        Thread.sleep(10)
    }
}
