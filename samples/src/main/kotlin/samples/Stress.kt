package samples

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.time.measureTime

/**
 * Many short coroutines that do nothing but suspend: the worst case for the agent's overhead and a large trace for
 * the GUI. `Stress [coroutines] [yields per coroutine]`; compare the printed time with and without `-Pcoroutree`.
 */
fun main(args: Array<String>) {
    val coroutines = args.getOrNull(0)?.toInt() ?: 20_000
    val yields = args.getOrNull(1)?.toInt() ?: 5
    val elapsed = measureTime {
        runBlocking(Dispatchers.Default) {
            repeat(10) {
                coroutineScope {
                    repeat(coroutines / 10) {
                        launch { repeat(yields) { yield() } }
                    }
                }
            }
        }
    }
    println("$coroutines coroutines × $yields yields: $elapsed")
}
