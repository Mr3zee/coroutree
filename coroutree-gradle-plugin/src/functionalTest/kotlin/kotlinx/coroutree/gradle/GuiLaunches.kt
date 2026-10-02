package kotlinx.coroutree.gradle

import java.io.File

/**
 * What the stand-in GUI (`kotlinx.coroutree.gui.MainKt` of the fake agent jar) was started with.
 * `coroutreeView` returns as soon as the process exists, so its record has to be waited for.
 */
object GuiLaunches {
    /** The arguments of each launch recorded in [dataDir], once there are [count] of them. */
    fun await(dataDir: File, count: Int, timeoutMillis: Long = 60_000): List<List<String>> {
        val record = File(dataDir, "launched.txt")
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            val launches = if (record.isFile) parse(record.readLines()) else emptyList()
            if (launches.size >= count) return launches
            check(System.nanoTime() < deadline) { "Waited for $count launches of the GUI, there are ${launches.size}: $launches" }
            Thread.sleep(50)
        }
    }

    /** Whole launches only: one that is still being written has no `end` yet. */
    private fun parse(lines: List<String>): List<List<String>> {
        val launches = ArrayList<List<String>>()
        var current: ArrayList<String>? = null
        for (line in lines) {
            when {
                line == "launched" -> current = ArrayList()
                line == "end" -> current?.let { launches += it }.also { current = null }
                else -> current?.add(line)
            }
        }
        return launches
    }
}
