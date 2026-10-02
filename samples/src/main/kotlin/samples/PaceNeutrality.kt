package samples

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.LockSupport

/**
 * Threads for which being held at an event (execution control, DESIGN §3.1) could go wrong if the hold touched what
 * is theirs: the park permit and the interrupt flag. Like `PaceControl` it does what it is told on standard input, one
 * line at a time, and says `<line> done`:
 *
 * - `go <name>` lets the thread of that name do its one thing; `unpark <name>` unparks it; `stop` ends the program.
 * - `parker-early`, `parker-late` and `virtual-parker` wait for their `go`, call `LockSupport.park()` once and say
 *   `<name> passed`. With the permit of an `unpark` they must not block, whenever the unpark came.
 * - `flagged` waits for its `go`, interrupts itself and goes to sleep for a minute, which the interrupt must end at
 *   once; it says `flagged sleep interrupted=<whether it was>`.
 */
private fun say(what: String) {
    println(what)
    System.out.flush()
}

fun main() {
    val orders = ConcurrentHashMap<String, LinkedBlockingQueue<String>>()
    val threads = ConcurrentHashMap<String, Thread>()

    fun actor(name: String, virtual: Boolean = false, act: () -> Unit) {
        val queue = LinkedBlockingQueue<String>()
        orders[name] = queue
        val body = Runnable { if (queue.take() == "go") act() }
        threads[name] = if (virtual) Thread.ofVirtual().name(name).start(body) else Thread(body, name).apply { start() }
    }

    fun parker(name: String, virtual: Boolean = false) = actor(name, virtual) {
        LockSupport.park()
        say("$name passed")
    }
    parker("parker-early")
    parker("parker-late")
    parker("virtual-parker", virtual = true)
    actor("flagged") {
        Thread.currentThread().interrupt()
        val interrupted = try {
            Thread.sleep(60_000)
            false
        } catch (e: InterruptedException) {
            true
        }
        say("flagged sleep interrupted=$interrupted")
    }

    say("ready")
    while (true) {
        val line = readlnOrNull() ?: "stop"
        val name = line.substringAfter(' ', "")
        when (line.substringBefore(' ')) {
            "go" -> orders.getValue(name).put("go")
            "unpark" -> LockSupport.unpark(threads.getValue(name))
            "stop" -> break
        }
        say("$line done")
    }
    for (queue in orders.values) queue.put("stop")
    for (thread in threads.values) {
        LockSupport.unpark(thread) // whoever is still parked, which is a failure the test has seen by now
        thread.interrupt()
        thread.join()
    }
    say("stopped")
}
