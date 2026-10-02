package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Inlined code that was written in another file (InlineHelpers.kt), lambdas inlined into lambdas, an exception out
 * of inlined code, a thread started in an inlined lambda, and the same inline functions called from Java, where
 * nothing is inlined at all. A construct has its site where it was written, whoever's class the code ended up in.
 */
fun main(): Unit = runBlocking {
    twice { n -> launch(CoroutineName("lambda-$n")) { delay(10) } }

    launchLabelled("elsewhere") { pauseBriefly() }.join()

    twice { outer ->
        twice { inner ->
            if (outer == 2 && inner == 1) launch(CoroutineName("deep")) { pauseBriefly() }
        }
    }

    Labeller("a").launchIn(this) { delay(10) }.join()

    try {
        twice { n -> if (n == 2) failing("thrown in inlined code") }
    } catch (e: IllegalStateException) {
        println("caught: ${e.message}")
    }

    twice { n -> if (n == 1) thread(name = "from a lambda") { Thread.sleep(10) }.join() }

    JavaInlineCaller.launchFromJava(this).join()
}
