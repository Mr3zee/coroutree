package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The basics: runBlocking, launch, async/await and a nested coroutineScope, all on runBlocking's event loop. */
fun main(): Unit = runBlocking {
    val greeting = async(CoroutineName("greeting")) {
        delay(20)
        "hello"
    }
    launch(CoroutineName("printer")) {
        println(greeting.await())
    }
    val total = sumInParallel()
    println("total = $total")
}

private suspend fun sumInParallel(): Int = coroutineScope {
    val left = async(CoroutineName("left")) { delay(10); 1 }
    val right = async(CoroutineName("right")) { delay(10); 2 }
    left.await() + right.await()
}
