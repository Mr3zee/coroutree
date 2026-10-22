package kotlinx.coroutree.agent

import java.util.Collections

/**
 * The record of every call to the `Hooks` that [Lab] generates. Patched test classes run against that class, so a
 * test sees which hooks its bytecode called, in which order, with which arguments.
 */
object HookLog {
    private val calls: MutableList<Array<Any?>> = Collections.synchronizedList(ArrayList())

    /** Called by the generated `Hooks` with the hook's name and its arguments, boxed, and by fixtures to mark their own steps. */
    @JvmStatic
    fun record(hook: String, arguments: Array<Any?>) {
        calls.add(arrayOf(hook, *arguments))
    }

    @JvmStatic
    fun mark(what: String) {
        record(what, emptyArray())
    }

    /** Returns the calls since the last `take` and clears them. Each call is the hook's name followed by its arguments. */
    fun take(): List<Array<Any?>> = synchronized(calls) {
        val taken = calls.toList()
        calls.clear()
        taken
    }

    /** `take` as text, for example `blockEnter(5)`. A throwable argument is its class and message, any other its `toString`. */
    fun taken(): List<String> = take().map { call ->
        call.drop(1).joinToString(", ", prefix = "${call[0]}(", postfix = ")") { argument ->
            if (argument is Throwable) "${argument.javaClass.simpleName}:${argument.message}" else argument.toString()
        }
    }
}
