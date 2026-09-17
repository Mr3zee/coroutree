package samples

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope

/**
 * Supervision: a failing child does not take its supervisor or its sibling down. Once with supervisorScope and a
 * CoroutineExceptionHandler on the child, once with a SupervisorJob scope whose handler sits in the scope.
 */
fun main(): Unit = runBlocking {
    val handler = CoroutineExceptionHandler { _, e -> println("handled ${e.message}") }

    supervisorScope {
        launch(CoroutineName("failing") + handler) {
            delay(10)
            throw IllegalArgumentException("first")
        }
        launch(CoroutineName("survivor")) {
            delay(50)
        }
    }

    val scope = CoroutineScope(SupervisorJob() + handler)
    val jobs = listOf(
        scope.launch(CoroutineName("failing")) { delay(50); throw IllegalArgumentException("second") },
        scope.launch(CoroutineName("survivor")) { delay(150) },
    )
    jobs.joinAll()
}
