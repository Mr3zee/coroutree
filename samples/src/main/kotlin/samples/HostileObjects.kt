package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Objects of the program that a tool looking at them must not trust: context elements whose `toString` or `hashCode`
 * throws, goes on for pages or returns null (which Java lets it), and an exception that has no message to give.
 * The program itself never calls any of it, and runs as if nobody had.
 */
fun main(): Unit = runBlocking {
    launch(Hostile("throws") { error("no toString for you") }) { delay(10) }
    launch(Hostile("verbose") { "x".repeat(5_000) }) { delay(10) }
    launch(Hostile("null") { null }) { delay(10) }
    launch(HashBomb()) { delay(10) }

    try {
        coroutineScope {
            launch(CoroutineName("cursed")) { throw Cursed() }
        }
    } catch (e: Cursed) {
        println("caught the cursed one")
    }
}

class Hostile(label: String, describe: () -> String?) : AbstractCoroutineContextElement(Key(label)) {
    /** A key per label, so that the elements do not replace each other in a context. */
    data class Key(val label: String) : CoroutineContext.Key<Hostile>

    // Typed as if it could not return null, so that toString hands on whatever the lambda does.
    @Suppress("UNCHECKED_CAST")
    private val describe = describe as () -> String

    override fun toString(): String = describe()
}

class HashBomb : AbstractCoroutineContextElement(HashBomb) {
    companion object Key : CoroutineContext.Key<HashBomb>

    override fun hashCode(): Int = error("no hashCode for you")
}

class Cursed : RuntimeException() {
    override val message: String get() = error("no message for you")

    override fun toString(): String = error("no toString either")
}
