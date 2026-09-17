package kotlinx.coroutree.gui

import kotlinx.coroutree.gui.view.TreeExpansion
import kotlinx.coroutree.gui.view.TreeRows
import kotlinx.coroutree.model.Event
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.Frame
import kotlinx.coroutree.model.NodeInfo
import kotlinx.coroutree.model.NodeKind
import kotlinx.coroutree.model.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeRowsTest {
    private var seq = 0L

    private fun node(id: Long, parent: Long, origin: Origin = Origin.PROJECT, kind: NodeKind = NodeKind.COROUTINE) =
        Frame(event = Event(seq = ++seq, nodeId = id, kind = EventKind.LAUNCHED, node = NodeInfo(id = id, kind = kind, parentId = parent, origin = origin)))

    @Test
    fun flattensDepthFirstInCreationOrder() {
        val snapshot = snapshotOf(listOf(node(1, 0), node(2, 1), node(3, 0), node(4, 1), node(5, 2)))
        val rows = TreeRows.flatten(snapshot, TreeExpansion())
        assertEquals(listOf(1L to 0, 2L to 1, 5L to 2, 4L to 1, 3L to 0), rows.map { it.node.id to it.depth })
        assertTrue(rows.first().expanded)
        assertFalse(rows.last().expanded, "a leaf is never 'expanded'")
    }

    @Test
    fun poolsAndLibraryInternalsStartCollapsedAndDimmed() {
        val snapshot = snapshotOf(
            listOf(
                node(1, 0, Origin.LIBRARY, NodeKind.POOL), node(2, 1, Origin.LIBRARY, NodeKind.THREAD),
                node(3, 0, Origin.LIBRARY), node(4, 3, Origin.LIBRARY), node(5, 4, Origin.LIBRARY),
                node(6, 0, Origin.LIBRARY), node(7, 6, Origin.LIBRARY), node(8, 7, Origin.PROJECT),
                node(9, 0, Origin.UNSPECIFIED), node(10, 9),
            ),
        )
        val rows = TreeRows.flatten(snapshot, TreeExpansion())
        assertEquals(listOf(1L, 3L, 6L, 7L, 8L, 9L, 10L), rows.map { it.node.id })
        assertEquals(setOf(1L, 3L), rows.filter { it.dimmed }.map { it.node.id }.toSet(), "library code leading to project code is not dimmed")
    }

    @Test
    fun explicitChoicesOverrideDefaultsAndSurviveNewSnapshots() {
        val frames = mutableListOf(node(1, 0), node(2, 1), node(3, 0, Origin.LIBRARY), node(4, 3, Origin.LIBRARY))
        var rows = TreeRows.flatten(snapshotOf(frames), TreeExpansion())
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.node.id })

        val expansion = TreeExpansion()
            .toggled(1, currentlyExpanded = rows[0].expanded)
            .toggled(3, currentlyExpanded = rows[2].expanded)
        frames += node(5, 1)
        frames += node(6, 3, Origin.LIBRARY)
        rows = TreeRows.flatten(snapshotOf(frames), expansion)
        assertEquals(listOf(1L, 3L, 4L, 6L), rows.map { it.node.id })
    }

    @Test
    fun ancestorsNearestFirst() {
        val snapshot = snapshotOf(listOf(node(1, 0), node(2, 1), node(3, 2)))
        assertEquals(listOf(2L, 1L), TreeRows.ancestors(snapshot, 3))
        assertEquals(emptyList(), TreeRows.ancestors(snapshot, 1))
        assertEquals(emptyList(), TreeRows.ancestors(snapshot, 42))
    }

    @Test
    fun survivesVeryDeepTrees() {
        val depth = 50_000
        val snapshot = snapshotOf((1L..depth).map { node(it, it - 1, Origin.LIBRARY) })
        assertEquals(1, TreeRows.flatten(snapshot, TreeExpansion()).size)
        assertEquals(depth, TreeRows.flatten(snapshot, TreeExpansion().expanded(1L..depth)).size)
    }
}
