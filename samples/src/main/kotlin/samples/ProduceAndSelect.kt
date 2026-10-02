package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select

/**
 * Coroutines behind a channel, and `select`. A producer that runs dry ends by itself; one that never would is
 * cancelled through its channel, which is a cancellation of the coroutine like any other. `select` is a suspension
 * of the selecting coroutine; the loser is cancelled afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun main(): Unit = runBlocking {
    val numbers = produce(CoroutineName("numbers")) { repeat(2) { send(it) } }
    for (number in numbers) println(number)

    val endless = produce(CoroutineName("endless")) {
        var next = 0
        while (true) send(next++)
    }
    println(endless.receive())
    endless.cancel()

    val fast = async(CoroutineName("fast")) { delay(10); "fast" }
    val slow = async(CoroutineName("slow")) { delay(10_000); "slow" }
    val winner = select {
        fast.onAwait { it }
        slow.onAwait { it }
    }
    println("$winner wins")
    slow.cancel()
}
