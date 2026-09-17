package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Inlined code. The compiler copies the body of an inline function into its caller under line numbers the caller's
 * file does not have; the tree has to show where the code was written all the same. A coroutine launched inside the
 * project's own inline function has its site there, also through two levels of inlining, and so has a suspension.
 * A suspension inside a library's inline function (`withLock`) is at the project's call of it. The block handed to
 * [launchNamed] is compiled into a copy of that function's lambda, and still suspends at the line it was written on.
 */
fun main(): Unit = runBlocking {
    launchNamed("direct") { delay(10) }.join()
    launchAndPause()

    val mutex = Mutex(locked = true)
    launch(CoroutineName("unlocker")) { mutex.unlock() }
    mutex.withLock { println("locked") }
}

inline fun CoroutineScope.launchNamed(name: String, crossinline block: suspend () -> Unit): Job =
    launch(CoroutineName(name)) {
        block()
    }

suspend inline fun CoroutineScope.launchAndPause() {
    launchNamed("nested") { delay(10) }.join()
    pause()
}

suspend inline fun pause() {
    delay(10)
}
