package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * More about how a coroutine starts: cancelled before its first dispatch, a default one never gets to run while an
 * ATOMIC one runs up to its first suspension; a lazy one is started by `join` or by `start`; an undispatched one
 * launches another undispatched one inside itself. And two coroutines that take turns with `yield`.
 */
fun main(): Unit = runBlocking {
    val default = launch(CoroutineName("default")) { println("unreachable") }
    default.cancel()
    val atomic = launch(CoroutineName("atomic"), start = CoroutineStart.ATOMIC) {
        println("atomic runs")
        yield()
        println("unreachable")
    }
    atomic.cancel()

    launch(CoroutineName("lazy joined"), start = CoroutineStart.LAZY) { println("started by join") }.join()
    launch(CoroutineName("lazy started"), start = CoroutineStart.LAZY) { println("started by start") }.start()

    launch(CoroutineName("undispatched"), start = CoroutineStart.UNDISPATCHED) {
        launch(CoroutineName("undispatched inside"), start = CoroutineStart.UNDISPATCHED) {
            println("the innermost runs first")
            yield()
        }
        yield()
    }

    launch(CoroutineName("ping")) { repeat(2) { yield() } }
    launch(CoroutineName("pong")) { repeat(2) { yield() } }
}
