package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/**
 * Coroutines without a Job: `suspend fun main` before it enters a scope, and one started with the bare
 * `startCoroutine` of the standard library. A generator is neither a node nor a source of events.
 */
suspend fun main() {
    delay(20) // main itself suspends; nothing in its context says where to resume, so it goes on in the timer's thread
    coroutineScope {
        launch(CoroutineName("child")) { delay(10) }
    }
    println(sequence { yield(1); yield(2) }.sum())

    var parked: Continuation<String>? = null
    val raw: suspend () -> Unit = {
        val word = suspendCoroutine { parked = it }
        error("resumed with '$word'")
    }
    raw.startCoroutine(Continuation(CoroutineName("raw")) { result -> println("the raw coroutine ended: $result") })
    parked!!.resume("go")
}
