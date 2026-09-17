package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A root async keeps its exception to itself until somebody awaits it; a root launch has nobody to tell and ends up
 * in the thread's uncaught exception handler. Both are unstructured roots.
 */
@OptIn(DelicateCoroutinesApi::class)
fun main(): Unit = runBlocking {
    val deferred = GlobalScope.async(Dispatchers.Unconfined + CoroutineName("held")) {
        delay(50)
        throw IllegalStateException("kept for later")
    }
    val orphan = GlobalScope.launch(Dispatchers.Unconfined + CoroutineName("orphan")) {
        delay(50)
        throw IllegalStateException("nobody listens")
    }
    orphan.join()
    try {
        deferred.await()
    } catch (e: IllegalStateException) {
        println("await threw ${e.message}")
    }
}
