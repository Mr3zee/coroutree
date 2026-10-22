package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.jvm.internal.CoroutineStackFrame
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/**
 * The shared-driver pattern, the way Ktor's pipelines do it: every interceptor of a chain is handed *one* continuation,
 * the driver's own, instead of the continuation of the coroutine that is running the chain. `proceed()` saves the
 * caller's continuation on a stack and runs the next interceptors in a loop, on the same JVM stack, until one suspends;
 * the driver then resumes the saved callers from the top of the stack, one after the other. Nothing in an interceptor's
 * frames leads to the coroutine they run for but the driver's `context`, which is the context of the last saved caller.
 *
 * Each case runs in a coroutine of its own, named for it, and ends in a line on the output the tests compare to.
 */
fun main() {
    runBlocking(CoroutineName("pipelines")) {
        launch(CoroutineName("nested")) {
            report("nested", Driver("n").run(listOf(enter("A"), enter("B"), enter("C"))))
        }.join()

        launch(CoroutineName("return")) {
            report("return", Driver("r").run(listOf(enter("A"), enter("B"), pause("C"), pause("D"), enter("E"))))
        }.join()

        launch(CoroutineName("context")) {
            val inContext: Interceptor = {
                withContext(CoroutineName("wrapped")) {
                    trail += "wrapped:${coroutineContext[CoroutineName]?.name}"
                    proceed()
                    trail += "wrapped back"
                }
            }
            report("context", Driver("c").run(listOf(enter("A"), inContext, pause("B"), enter("C"))))
        }.join()

        launch(CoroutineName("pool")) {
            val inPool: Interceptor = { withContext(Dispatchers.Default) { proceed() } }
            report("pool", Driver("p").run(listOf(enter("A"), inPool, pause("B"), enter("C"))))
        }.join()

        launch(CoroutineName("thread")) {
            report("thread", Driver("t").run(listOf(enter("A"), enter("B"), resumeFromThread("C", "resumer"), enter("D"))))
        }.join()

        launch(CoroutineName("caught")) {
            val catching: Interceptor = {
                try {
                    proceed()
                } catch (e: IllegalStateException) {
                    trail += "caught ${e.message}"
                }
            }
            val failing: Interceptor = { error("boom") }
            report("caught", Driver("e").run(listOf(enter("A"), catching, enter("B"), failing)))
        }.join()

        launch(CoroutineName("late failure")) {
            val failing: Interceptor = {
                delay(10)
                throw IllegalArgumentException("late")
            }
            val driver = Driver("l")
            val result = runCatching { driver.run(listOf(enter("A"), cleanup("B"), enter("C"), failing)) }
            report("late failure", driver.trail + "${result.exceptionOrNull()}")
        }.join()
    }

    // A coroutine that has no Job: the chain runs in it, suspends in it, and the saved callers are resumed from the
    // timer's thread and from a thread of its own.
    val done = CountDownLatch(1)
    val body: suspend () -> Unit = {
        report("jobless", Driver("j").run(listOf(enter("A"), pause("B"), resumeFromThread("C", "jobless resumer"), enter("D"))))
    }
    body.startCoroutine(Continuation(CoroutineName("jobless pipeline")) { done.countDown() })
    done.await()
}

private fun report(case: String, trail: List<String>) = println("$case: " + trail.joinToString(" "))

typealias Interceptor = suspend Driver.() -> Unit

private fun enter(name: String): Interceptor = {
    trail += "$name>"
    proceed()
    trail += "<$name"
}

/** Suspends in the middle: the whole chain above it is parked on the driver's stack of saved callers. */
private fun pause(name: String): Interceptor = {
    trail += "$name>"
    delay(10)
    proceed()
    trail += "<$name"
}

private fun resumeFromThread(name: String, threadName: String): Interceptor = {
    trail += "$name>"
    suspendCoroutine { parked ->
        thread(name = threadName) {
            Thread.sleep(50) // the interceptor has suspended by then
            parked.resume(Unit)
        }
    }
    proceed()
    trail += "<$name"
}

private fun cleanup(name: String): Interceptor = {
    try {
        trail += "$name>"
        proceed()
    } finally {
        trail += "<$name finally"
    }
}

class Driver(private val tag: String) {
    val trail = mutableListOf<String>()

    private lateinit var blocks: List<Interceptor>
    private lateinit var saved: Array<Continuation<Unit>?>
    private var last = -1
    private var index = -1

    // Like Ktor's: it stands in for every caller saved on the stack, so a debugger can walk through it to the coroutine.
    private val driver = object : Continuation<Unit>, CoroutineStackFrame {
        // Every interceptor has this driver as its completion, so a frame that answered with the top of the stack would send
        // a walker round in circles (the top caller is an interceptor too). Ktor's steps down the stack one caller per
        // question, which leaves the walk half done when a walker stops early; this one names the outermost caller, the one
        // that started the chain, whose frames lead on to the coroutine.
        override val callerFrame: CoroutineStackFrame? get() = saved.getOrNull(0) as? CoroutineStackFrame

        override fun getStackTraceElement(): StackTraceElement? = null

        override val context: CoroutineContext get() = saved[last]?.context ?: error("$tag: nobody to run in")

        override fun resumeWith(result: Result<Unit>) {
            if (result.isFailure) resumeCaller(result) else loop(direct = false)
        }
    }

    suspend fun run(chain: List<Interceptor>): List<String> {
        blocks = chain
        saved = arrayOfNulls(chain.size + 1)
        index = 0
        proceed()
        return trail
    }

    suspend fun proceed() {
        suspendCoroutineUninterceptedOrReturn { caller ->
            if (index == blocks.size) return@suspendCoroutineUninterceptedOrReturn Unit
            saved[++last] = caller.intercepted()
            if (loop(direct = true)) {
                saved[last--] = null
                Unit
            } else {
                COROUTINE_SUSPENDED
            }
        }
    }

    /** True when the rest of the chain ran to its end without suspending. */
    private fun loop(direct: Boolean): Boolean {
        while (true) {
            val at = index
            if (at == blocks.size) {
                if (direct) return true
                resumeCaller(Result.success(Unit))
                return false
            }
            index = at + 1
            try {
                @Suppress("UNCHECKED_CAST")
                val outcome = (blocks[at] as (Driver, Continuation<Unit>) -> Any?)(this, driver)
                if (outcome === COROUTINE_SUSPENDED) return false
            } catch (e: Throwable) {
                resumeCaller(Result.failure(e))
                return false
            }
        }
    }

    private fun resumeCaller(result: Result<Unit>) {
        check(last >= 0) { "$tag: no more callers to resume" }
        val next = saved[last]!!
        saved[last--] = null
        result.fold({ next.resume(Unit) }, { next.resumeWithException(it) })
    }
}
