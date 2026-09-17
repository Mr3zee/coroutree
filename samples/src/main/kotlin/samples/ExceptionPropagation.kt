package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A child fails inside coroutineScope: the exception goes up to the scope, the scope cancels the sibling,
 * rethrows to its caller, and the caller catches it.
 */
fun main(): Unit = runBlocking {
    try {
        coroutineScope {
            launch(CoroutineName("sibling")) {
                delay(10_000)
            }
            launch(CoroutineName("failing")) {
                delay(20)
                throw IllegalStateException("boom")
            }
        }
    } catch (e: IllegalStateException) {
        println("caught ${e.message}")
    }
}
