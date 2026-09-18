package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Something to slow down, stop and step from outside (execution control, DESIGN §3.1): it keeps going until it is told
 * to `stop`, and does what it is told on standard input, one word per line, saying `<word> done` when it has.
 *
 * - `left` and `right`: two threads that each run an event loop with coroutines ticking in it: two subtrees with a
 *   thread of their own. `left` has two tickers, two sequences that share a thread; `right` has one.
 *   `cancel-left` cancels the first left ticker from the main thread, an outsider to that subtree.
 * - `parent` starts `orphan`, which ticks on a thread of its own, and ends at `end-parent`: a node that ends while
 *   what it started lives on.
 * - `target` and `locked-target` are coroutines that wait to be cancelled, by `victim` (`cancel-target`) and by
 *   `owner` while it owns a monitor that `contender` wants (`cancel-locked`). `interrupt-victim` interrupts `victim`
 *   from the main thread; `victim` says afterwards whether it still has the interrupt and what the wait cost it.
 * - `exit` starts `exiter`, which does nothing the agent could see for two seconds and then calls `System.exit(3)`;
 *   a shutdown hook says `hook ran`. For leaving a program that is paused.
 */
private fun say(what: String) {
    println(what)
    System.out.flush()
}

@OptIn(DelicateCoroutinesApi::class)
fun main() {
    val stopped = CountDownLatch(1)
    val leftTicker = CompletableFuture<Job>()

    fun eventLoop(name: String, tickers: Int, first: CompletableFuture<Job>?) = thread(name = name) {
        runBlocking(CoroutineName("$name-loop")) {
            val jobs = (1..tickers).map { n -> launch(CoroutineName("$name-ticker-$n")) { while (isActive) delay(5) } }
            first?.complete(jobs.first())
            launch(Dispatchers.IO) { stopped.await() }.join()
            jobs.forEach { it.cancel() }
        }
    }

    val left = eventLoop("left", 2, leftTicker)
    val right = eventLoop("right", 1, null)

    val endParent = CountDownLatch(1)
    val orphan = CompletableFuture<Thread>()
    val parent = thread(name = "parent") {
        orphan.complete(thread(name = "orphan") { while (stopped.count > 0) Thread.sleep(5) })
        endParent.await()
    }

    val target = GlobalScope.launch(CoroutineName("target")) { awaitCancellation() }
    val lockedTarget = GlobalScope.launch(CoroutineName("locked-target")) { awaitCancellation() }

    val victimOrders = LinkedBlockingQueue<String>()
    val victim = thread(name = "victim") {
        while (true) {
            val order = try {
                victimOrders.take()
            } catch (e: InterruptedException) {
                say("victim interrupted while idle")
                continue
            }
            if (order != "cancel-target") break
            val cpuBefore = ManagementFactory.getThreadMXBean().currentThreadCpuTime
            target.cancel() // under a paused "target" this is where the thread is held
            val cpuMillis = (ManagementFactory.getThreadMXBean().currentThreadCpuTime - cpuBefore) / 1_000_000
            say("victim interrupted=${Thread.interrupted()} cpuMillis=$cpuMillis")
        }
    }

    val lock = Any()
    val owns = CountDownLatch(1)
    val lockedOrders = LinkedBlockingQueue<String>()
    val owner = thread(name = "owner") {
        if (lockedOrders.take() == "cancel-locked") {
            synchronized(lock) {
                owns.countDown()
                lockedTarget.cancel()
            }
            say("owner released")
        }
    }
    val contender = thread(name = "contender") {
        owns.await()
        synchronized(lock) { say("contender entered") }
    }

    Runtime.getRuntime().addShutdownHook(thread(start = false, name = "app-hook") { say("hook ran") })

    say("ready")
    while (true) {
        val word = readlnOrNull() ?: "stop"
        when (word) {
            "cancel-left" -> leftTicker.get().cancel()
            "end-parent" -> endParent.countDown()
            "cancel-target" -> victimOrders.put(word)
            "interrupt-victim" -> victim.interrupt()
            "cancel-locked" -> lockedOrders.put(word)
            "exit" -> thread(name = "exiter") {
                // Busy on purpose: whatever waits is an event, and the point is to get from here into System.exit without one.
                val deadline = System.nanoTime() + 2_000_000_000
                while (System.nanoTime() < deadline) Thread.onSpinWait()
                exitProcess(3)
            }
            "stop" -> break
        }
        say("$word done")
    }
    stopped.countDown()
    endParent.countDown()
    victimOrders.put("stop")
    lockedOrders.put("stop")
    owns.countDown()
    target.cancel()
    lockedTarget.cancel()
    for (t in listOf(left, right, parent, orphan.get(), victim, owner, contender)) t.join()
    say("stopped")
}
