package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Context changes: a dispatcher switch, a change that keeps the dispatcher, custom and thread-context elements. */
fun main(): Unit = runBlocking(CoroutineName("root")) {
    withContext(Dispatchers.IO) {
        delay(10)
    }
    withContext(CoroutineName("renamed") + RequestId("r-42")) {
        delay(10)
    }
    launch(Dispatchers.Default + TraceTag("tagged")) {
        withContext(Dispatchers.Default) { // same context: no change to report
            delay(10)
        }
    }
}

/** A plain custom context element. */
class RequestId(val id: String) : AbstractCoroutineContextElement(RequestId) {
    companion object Key : CoroutineContext.Key<RequestId>

    override fun toString(): String = "RequestId($id)"
}

/** An element that touches thread state on every resume and suspend, like an MDC or a thread-local bridge. */
class TraceTag(private val tag: String) : ThreadContextElement<String?>, AbstractCoroutineContextElement(TraceTag) {
    companion object Key : CoroutineContext.Key<TraceTag>

    override fun updateThreadContext(context: CoroutineContext): String? = currentTag.get().also { currentTag.set(tag) }

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) = currentTag.set(oldState)

    override fun toString(): String = "TraceTag($tag)"
}

private val currentTag = ThreadLocal<String?>()
