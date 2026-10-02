package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.concurrent.thread

/**
 * Structure that is not where the code is. A coroutine launched with a `Job()` of its own is that job's child, not
 * its launcher's; one launched with another coroutine's job is that coroutine's child and goes down with it;
 * `GlobalScope` inside a coroutine makes a root; and a scope whose `Job(parent)` hangs under this coroutine is
 * cancelled from another thread.
 */
@OptIn(DelicateCoroutinesApi::class)
fun main(): Unit = runBlocking {
    launch(Job() + CoroutineName("detached")) { delay(10) }.join()

    val host = launch(CoroutineName("host")) { awaitCancellation() }
    val adopted = launch(host + CoroutineName("adopted")) { awaitCancellation() }
    yield()
    host.cancel()
    joinAll(host, adopted)

    GlobalScope.launch(Dispatchers.Unconfined + CoroutineName("global")) { delay(30) }.join()

    // The dispatcher is runBlocking's, so that what the component's coroutine does is in step with this one.
    val component = CoroutineScope(coroutineContext + Job(coroutineContext.job) + CoroutineName("component"))
    component.launch { awaitCancellation() }
    yield()
    thread(name = "canceller") { component.cancel() }.join()
    component.coroutineContext.job.join()
}
