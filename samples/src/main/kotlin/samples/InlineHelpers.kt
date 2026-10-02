package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Inline functions of the InlineAcrossFiles sample, in a file of their own: code that is copied into another file's
// class has to be found here again. The line numbers in this file are part of that sample's golden tree and tests.

inline fun CoroutineScope.launchLabelled(label: String, crossinline block: suspend CoroutineScope.() -> Unit): Job =
    launch(CoroutineName(label)) {
        block()
    }

inline fun twice(action: (Int) -> Unit) {
    action(1)
    action(2)
}

suspend inline fun pauseBriefly() {
    delay(10)
}

inline fun failing(message: String): Nothing = throw IllegalStateException(message)

/** A member, and one that calls another inline function of this file. */
class Labeller(val prefix: String) {
    inline fun launchIn(scope: CoroutineScope, crossinline block: suspend CoroutineScope.() -> Unit): Job =
        scope.launchLabelled("$prefix member", block)
}
