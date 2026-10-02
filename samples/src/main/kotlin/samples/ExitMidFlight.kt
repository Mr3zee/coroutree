package samples

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * A program that does not get to its end: in the middle of everything — coroutines suspended, a thread asleep, the
 * main thread blocked — one coroutine ends the JVM. `ExitMidFlight exit` calls `System.exit(7)`, which runs the
 * shutdown hooks; `ExitMidFlight halt` calls `Runtime.halt(7)`, which runs nothing.
 */
fun main(args: Array<String>): Unit = runBlocking {
    thread(name = "sleeper") { Thread.sleep(60_000) }
    launch(CoroutineName("ticker")) { while (true) delay(5) }
    launch(CoroutineName("waiting")) { delay(60_000) }
    launch(Dispatchers.Default + CoroutineName("quitter")) {
        delay(200)
        println("leaving")
        System.out.flush()
        if (args.firstOrNull() == "halt") Runtime.getRuntime().halt(7) else exitProcess(7)
    }
}
