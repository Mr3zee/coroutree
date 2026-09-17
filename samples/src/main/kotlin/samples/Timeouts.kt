package samples

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** withTimeout is a scope that cancels itself: once in time, once too late and caught, once turned into null. */
fun main(): Unit = runBlocking {
    withTimeout(1_000) {
        delay(10)
    }
    try {
        withTimeout(20) {
            delay(10_000)
        }
    } catch (e: TimeoutCancellationException) {
        println("timed out")
    }
    val result = withTimeoutOrNull(20) {
        delay(10_000)
        "never"
    }
    println("result = $result")
}
