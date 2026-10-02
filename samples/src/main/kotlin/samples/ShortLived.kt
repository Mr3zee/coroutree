package samples

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Thousands of coroutines and hundreds of threads that come and go, each with objects of its own — a context
 * element, an exception, a Thread subclass — and then nothing: the program says `idle` and waits for a line on its
 * standard input. Whatever of all that is still in memory by then is kept alive by somebody other than the program.
 * `ShortLived [coroutines] [threads]`.
 */
fun main(args: Array<String>) {
    val coroutines = args.getOrNull(0)?.toInt() ?: 5_000
    val threads = args.getOrNull(1)?.toInt() ?: 300
    churn(coroutines, threads)
    println("idle")
    System.out.flush()
    readlnOrNull()
    churn(10, 2) // still in working order after whoever looked has looked
    println("done")
}

private fun churn(coroutines: Int, threads: Int) {
    runBlocking(Dispatchers.Default) {
        val handler = CoroutineExceptionHandler { _, _ -> }
        supervisorScope {
            repeat(coroutines) { index ->
                launch(Baggage(index) + handler) {
                    yield()
                    when (index % 4) {
                        0 -> throw ShortLivedFailure(index)
                        1 -> withContext(CoroutineName("renamed")) { yield() }
                        2 -> coroutineScope { async { yield() }.await() }
                        else -> cancel()
                    }
                }
            }
        }
    }
    repeat(threads) { index ->
        val thread = ShortLivedThread(index)
        thread.start()
        if (index % 2 == 0) thread.interrupt()
        thread.join()
    }
}

class Baggage(val index: Int) : AbstractCoroutineContextElement(Baggage) {
    companion object Key : CoroutineContext.Key<Baggage>

    private val weight = ByteArray(1024)

    override fun toString(): String = "Baggage($index, ${weight.size})"
}

class ShortLivedFailure(index: Int) : RuntimeException("failure $index")

class ShortLivedThread(index: Int) : Thread("short-lived-$index") {
    init {
        setUncaughtExceptionHandler { _, _ -> }
    }

    override fun run() {
        if (name.endsWith("7")) throw ShortLivedFailure(-1)
    }
}
