package samples

/** Virtual threads are threads: started, blocked, interrupted and finished like platform ones, on carriers that are not shown. */
fun main() {
    val sleeper = Thread.ofVirtual().name("virtual sleeper").start {
        Thread.sleep(20)
    }
    val interrupted = Thread.ofVirtual().name("virtual interrupted").start {
        try {
            Thread.sleep(60_000)
        } catch (e: InterruptedException) {
            println("virtual thread interrupted")
        }
    }
    val failing = Thread.ofVirtual().name("virtual failing").unstarted {
        throw IllegalStateException("virtual thread failed")
    }
    failing.setUncaughtExceptionHandler { t, e -> println("${t.name} died: ${e.message}") }
    failing.start()

    Thread.sleep(50)
    interrupted.interrupt()
    listOf(sleeper, interrupted, failing).forEach { it.join() }
}
