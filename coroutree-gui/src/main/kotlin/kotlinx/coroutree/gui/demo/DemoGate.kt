package kotlinx.coroutree.gui.demo

import kotlinx.coroutines.delay
import kotlinx.coroutree.gui.view.PaceCommand
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.PaceDef
import kotlinx.coroutree.model.tree.PaceSetting

/**
 * Stands in for the agent's gate while the demo trace is played as a live session, so that execution control can be
 * tried, shown and tested without a JVM: it takes the same commands, answers them the same way — with a [PaceDef] in
 * the stream and nothing else — and holds the playback where the agent would hold the program.
 *
 * A played script has one order, so it is cruder than the real thing: a paused subtree stops the playback when its
 * next event is due, where a real program's other threads would run on.
 */
class DemoGate(private val emit: (Frame) -> Unit) {
    private var global = PaceSetting()
    private var globalPermits = 0
    private val nodes = HashMap<Long, PaceSetting>()
    private val nodePermits = HashMap<Long, Int>()
    private val parents = HashMap<Long, Long>()
    private val lastStep = HashMap<Long, Long>()
    private var lastSeq = 0L
    private var clockNanos = 0L

    @Synchronized
    fun announce() = say(0, global, steps = 0, PaceDef.Reason.CONFIG, dropped = false)

    @Synchronized
    fun command(command: PaceCommand) {
        val node = command.node
        if (node != 0L && node !in parents) return // a node nobody knows: ignored, as the agent does
        if (command is PaceCommand.Inherit) {
            if (nodes.remove(node) != null) {
                nodePermits.remove(node)
                say(node, PaceSetting(), 0, PaceDef.Reason.CONTROLLER, dropped = true)
            }
            return
        }
        val before = if (node == 0L) global else nodes[node] ?: governing(node)
        var steps = 0
        val after = when (command) {
            is PaceCommand.SetPace -> before.copy(intervalNanos = command.intervalNanos.coerceAtLeast(0))
            is PaceCommand.Pause -> before.copy(paused = true).also { setPermits(node, 0) }
            is PaceCommand.Resume -> before.copy(paused = false).also { setPermits(node, 0) }
            is PaceCommand.Step -> {
                steps = command.count.coerceAtLeast(1)
                setPermits(node, (if (before.paused) permits(node) else 0) + steps)
                before.copy(paused = true)
            }
            is PaceCommand.Inherit -> before
        }
        if (node == 0L) global = after else nodes[node] = after
        say(node, after, steps, PaceDef.Reason.CONTROLLER, dropped = false)
    }

    /** Returns when [event] may be shown. */
    suspend fun await(event: Event) {
        while (true) {
            val wait = synchronized(this) { tryPass(event) }
            if (wait == 0L) return
            delay(wait)
        }
    }

    private fun tryPass(event: Event): Long {
        event.node?.takeIf { event.kind == EventKind.LAUNCHED || event.kind == EventKind.DISCOVERED }?.let { parents[it.id] = it.parentId }
        val scope = scopeOf(event.nodeId)
        val setting = if (scope == 0L) global else nodes.getValue(scope)
        val now = System.nanoTime()
        if (setting.paused) {
            if (permits(scope) <= 0) return TICK_MILLIS
            setPermits(scope, permits(scope) - 1)
        } else if (setting.intervalNanos > 0) {
            val last = lastStep[event.nodeId]
            if (last != null && now - last < setting.intervalNanos) return ((setting.intervalNanos - (now - last)) / 1_000_000).coerceIn(1, TICK_MILLIS)
        }
        lastStep[event.nodeId] = now
        lastSeq = event.seq
        clockNanos = event.timeNanos
        return 0
    }

    private fun governing(node: Long): PaceSetting = scopeOf(node).let { if (it == 0L) global else nodes.getValue(it) }

    private fun scopeOf(node: Long): Long {
        var id = node
        var hops = 0
        while (id != 0L && hops++ < 10_000) {
            if (id in nodes) return id
            id = parents[id] ?: 0L
        }
        return 0
    }

    private fun permits(scope: Long) = if (scope == 0L) globalPermits else nodePermits[scope] ?: 0

    private fun setPermits(scope: Long, permits: Int) {
        if (scope == 0L) globalPermits = permits else nodePermits[scope] = permits
    }

    private fun say(scope: Long, setting: PaceSetting, steps: Int, reason: PaceDef.Reason, dropped: Boolean) {
        emit(Frame(pace = PaceDef(clockNanos, lastSeq, scope, if (dropped) 0 else setting.intervalNanos, !dropped && setting.paused, steps, reason, dropped)))
    }

    private companion object {
        const val TICK_MILLIS = 10L
    }
}
