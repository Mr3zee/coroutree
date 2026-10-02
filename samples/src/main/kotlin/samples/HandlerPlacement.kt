package samples

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope

/**
 * Where a CoroutineExceptionHandler applies and where it does not. Only the coroutine that has nobody to pass its
 * exception to asks a handler: the outermost one below a supervisor. A handler on a coroutine further down is never
 * asked, nor is one on an `async`; without any, the thread's uncaught exception handler gets it. A failure that
 * comes out of a coroutineScope inside such a coroutine is the coroutine's own failure, thrown once.
 */
fun main(): Unit = runBlocking {
    fun handler(name: String) = CoroutineExceptionHandler { _, e -> println("$name handled ${e.message}") }
    Thread.setDefaultUncaughtExceptionHandler { _, e -> println("uncaught: ${e.message}") }

    supervisorScope {
        launch(CoroutineName("outer") + handler("the outer handler")) {
            launch(CoroutineName("inner") + handler("the inner handler")) {
                delay(10)
                throw IllegalStateException("from the inner one")
            }
            awaitCancellation()
        }
    }

    supervisorScope {
        async(CoroutineName("deferred") + handler("a handler on async")) {
            delay(10)
            throw IllegalStateException("kept in the Deferred")
        }
        launch(CoroutineName("no handler")) {
            delay(10)
            throw IllegalStateException("nobody's")
        }
    }

    supervisorScope {
        launch(CoroutineName("through a scope") + handler("the handler")) {
            coroutineScope {
                launch(CoroutineName("deep")) {
                    delay(10)
                    throw IllegalStateException("from deep down")
                }
                launch(CoroutineName("deep sibling")) { awaitCancellation() }
            }
            println("unreachable")
        }
    }
}
