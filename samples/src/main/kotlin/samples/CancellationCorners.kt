package samples

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Cancellation where it is easy to tell a wrong story: coroutines cancelled while they are suspended in `delay`,
 * `join` and `await`; a cause with a cause; a job that is cancelled twice and one that is cancelled after it is over;
 * a coroutine that cancels itself and one that just throws a CancellationException, which nobody asked for;
 * `cancelChildren`, which leaves the parent alone.
 */
fun main(): Unit = runBlocking {
    val forever = async(CoroutineName("forever")) { awaitCancellation() }
    val delayed = launch(CoroutineName("in delay")) { delay(10_000) }
    val joining = launch(CoroutineName("in join")) { forever.join() }
    val awaiting = launch(CoroutineName("in await")) { forever.await() }
    yield()
    delayed.cancel()
    joining.cancel()
    awaiting.cancel()
    joinAll(delayed, joining, awaiting)
    delayed.cancel() // over already: nothing happens, and nothing is to be reported

    forever.cancel(CancellationException("no longer needed", IllegalStateException("the reason behind it")))
    forever.cancel() // asked again while it is on its way out
    forever.join()

    launch(CoroutineName("cancels itself")) {
        coroutineContext.cancel()
        println("still running: isActive = $isActive")
        yield() // and this is where it ends
        println("unreachable")
    }.join()

    launch(CoroutineName("gives up")) {
        yield()
        throw CancellationException("gave up")
    }.join()

    launch(CoroutineName("parent")) {
        launch(CoroutineName("first")) { awaitCancellation() }
        launch(CoroutineName("second")) { awaitCancellation() }
        yield()
        coroutineContext.cancelChildren()
        yield()
        println("the parent goes on: isActive = $isActive")
    }
}
