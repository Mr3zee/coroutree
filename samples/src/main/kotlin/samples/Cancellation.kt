package samples

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Cancellation three ways: one coroutine cancels another, a parent's cancellation reaches its children, and a whole
 * scope is cancelled from outside. One child swallows its CancellationException on the way out.
 */
fun main(): Unit = runBlocking {
    val victim = launch(CoroutineName("victim")) {
        awaitCancellation()
    }
    launch(CoroutineName("assassin")) {
        delay(10)
        victim.cancel()
    }.join()

    val parent = launch(CoroutineName("parent")) {
        launch(CoroutineName("child")) { awaitCancellation() }
        launch(CoroutineName("stubborn child")) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                println("swallowed: ${e.message}")
            }
        }
        awaitCancellation()
    }
    delay(10)
    parent.cancelAndJoin()

    // Unconfined, so that the worker reacts to the cancellation right inside cancel() and the trace does not depend
    // on how fast a dispatcher thread happens to be.
    val scope = CoroutineScope(Job() + Dispatchers.Unconfined + CoroutineName("service"))
    val worker = scope.launch { awaitCancellation() }
    scope.cancel("shutting down")
    worker.join()
}
