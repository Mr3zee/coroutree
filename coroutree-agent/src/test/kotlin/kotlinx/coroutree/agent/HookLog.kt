package kotlinx.coroutree.agent

import java.util.Collections

/**
 * Where the stand-in for `Hooks` that [Lab] generates writes down every call it gets. Patched test classes
 * run against that stand-in, so a test sees exactly which hooks its bytecode called, in order, with which arguments.
 */
object HookLog {
    private val calls: MutableList<Array<Any?>> = Collections.synchronizedList(ArrayList())

    /** Called by generated code: the hook's name and its arguments, boxed. Also by fixtures, to mark their own moments. */
    @JvmStatic
    fun record(hook: String, arguments: Array<Any?>) {
        calls.add(arrayOf(hook, *arguments))
    }

    @JvmStatic
    fun mark(what: String) {
        record(what, emptyArray())
    }

    /** The calls since the last time, each as name and arguments, and forgets them. */
    fun take(): List<Array<Any?>> = synchronized(calls) {
        val taken = calls.toList()
        calls.clear()
        taken
    }

    /** The same as text: `blockEnter(5)`; an argument is its toString, or for a throwable its class and message. */
    fun taken(): List<String> = take().map { call ->
        call.drop(1).joinToString(", ", prefix = "${call[0]}(", postfix = ")") { argument ->
            if (argument is Throwable) "${argument.javaClass.simpleName}:${argument.message}" else argument.toString()
        }
    }
}
