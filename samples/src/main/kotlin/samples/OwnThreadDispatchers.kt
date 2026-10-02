package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Dispatchers that the program makes out of threads of its own: `newSingleThreadContext`, and an executor turned into
 * a dispatcher. Such a dispatcher prints itself as its executor does, object identity and all. Neither is closed:
 * shutting an executor down interrupts its idle thread once or twice, as it happens, and the threads are daemons.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main(): Unit = runBlocking {
    val own = newSingleThreadContext("own thread")
    withContext(own + CoroutineName("on its own thread")) { delay(30) }

    val executor = Executors.newSingleThreadExecutor { Thread(it, "executor thread").apply { isDaemon = true } }.asCoroutineDispatcher()
    withContext(executor + CoroutineName("on an executor")) { delay(30) }
}
