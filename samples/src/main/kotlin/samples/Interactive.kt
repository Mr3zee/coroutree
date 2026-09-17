package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Keeps running until a line arrives on standard input: something to watch live. A ticker coroutine keeps producing
 * events while a coroutine on Dispatchers.IO blocks its thread reading the line.
 */
fun main(): Unit = runBlocking {
    val ticker = launch(CoroutineName("ticker")) {
        while (isActive) delay(20)
    }
    val line = withContext(Dispatchers.IO + CoroutineName("prompt")) {
        println("press Enter to finish")
        readlnOrNull()
    }
    println("got: $line")
    ticker.cancel()
}
