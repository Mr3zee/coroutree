package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Context changes inside context changes: the same dispatcher again (nothing changes), another one, only a name;
 * a view of a dispatcher with limited parallelism; Unconfined, inherited by a child; a context that has lost an
 * element; and a child that replaces a custom element with another of the same key.
 * The delays outside runBlocking's event loop are long: the first one starts the library's timer thread.
 */
fun main(): Unit = runBlocking(CoroutineName("root") + Tenant("acme")) {
    withContext(Dispatchers.Default) {
        withContext(Dispatchers.Default) { delay(30) }
        withContext(Dispatchers.IO) { delay(30) }
        withContext(CoroutineName("renamed inside")) { delay(30) }
    }

    withContext(Dispatchers.Default.limitedParallelism(1)) {
        launch(CoroutineName("limited")) { delay(30) }
    }

    launch(Dispatchers.Unconfined + CoroutineName("unconfined")) {
        launch { delay(30) } // no name of its own: the name it inherits is its parent's
        delay(30)
    }.join()

    launch(Tenant("globex")) { delay(10) }.join()

    // CoroutineScope() around a context that has a Job makes no new one: a child of runBlocking, minus two elements.
    CoroutineScope(coroutineContext.minusKey(CoroutineName).minusKey(Tenant)).launch { delay(10) }
    coroutineContext.job.children.forEach { it.join() }
}

class Tenant(private val name: String) : AbstractCoroutineContextElement(Tenant) {
    companion object Key : CoroutineContext.Key<Tenant>

    override fun toString(): String = "Tenant($name)"
}
