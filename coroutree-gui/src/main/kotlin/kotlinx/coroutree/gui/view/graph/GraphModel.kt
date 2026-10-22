package kotlinx.coroutree.gui.view.graph

/** Cross-link kinds (DESIGN §2.4). Excludes structural parent-child edges. */
enum class EdgeKind {
    LAUNCHED_FROM,
    CANCELS,
    INTERRUPTS,

    /** Only the selected coroutine's current thread link is drawn. */
    RUNS_ON,
}

/** Sibling order follows [GraphModel.nodes]. */
data class GraphNode(
    val id: Long,
    /** 0 or an id absent from the graph means a root. */
    val parentId: Long,
    val title: String = "",
    /** Requested width. The engine can widen the box for its ports. */
    val width: Int = GraphMetrics.MIN_NODE_WIDTH,
    /** Library machinery, shown because "show library / pools" is on. */
    val dimmed: Boolean = false,
)

/** A cross-link to route, drawn with an arrowhead at [to]. */
data class GraphLink(
    val kind: EdgeKind,
    val from: Long,
    val to: Long,
    /** Requested label width, or 0 for none. The engine can truncate the label (DESIGN §6.2, rule 5). */
    val labelWidth: Int = 0,
)

data class GraphModel(val nodes: List<GraphNode>, val links: List<GraphLink>) {
    companion object {
        val EMPTY = GraphModel(emptyList(), emptyList())
    }
}

/**
 * All dimensions are in dp at zoom 1. Vertical lines use three residue classes modulo [COLUMN]:
 *
 * - Structural ports and box centers: 0.
 * - Cross-link ports and gap tracks on even layers: [UNIT].
 * - Cross-link ports and gap tracks on odd layers: `2 * UNIT`.
 *
 * Distinct verticals in one channel are at least [UNIT] apart across classes and [COLUMN] apart in the same class.
 * A parent's trunk and drops count as one shape and can share x coordinates.
 */
object GraphMetrics {
    /** Minimum distance between two lines. */
    const val UNIT = 6
    const val COLUMN = 3 * UNIT
    const val LINE_GAP = UNIT

    const val NODE_HEIGHT = 40
    const val MIN_NODE_WIDTH = 96
    const val MAX_NODE_WIDTH = 264

    /** Minimum free space between two boxes. */
    const val BOX_GAP = 24

    /** Minimum distance between a line and a box that isn't its endpoint. */
    const val BOX_CLEARANCE = 8

    /** Between a vertical track in the gap of two boxes and either of them. */
    const val GAP_CLEARANCE = 10

    /** Between a box's corner and the outermost port on that side. */
    const val PORT_INSET = 8

    /** Between a layer of boxes and the nearest horizontal track of the channel next to it. */
    const val CHANNEL_PAD = 14
    const val MIN_CHANNEL_HEIGHT = 36

    const val LABEL_HEIGHT = 14

    /** Free space kept above and below a label, on top of [LINE_GAP]. */
    const val LABEL_PAD = 2

    /** Between a label and the nearest vertical line, its own bends included. */
    const val LABEL_CLEARANCE = 4

    /** Labels below this width are omitted. */
    const val MIN_LABEL_WIDTH = 28

    /** Empty space around the drawing. */
    const val MARGIN = 24

    /** Residue class modulo [COLUMN] of the cross-link verticals that belong to the layer at [depth]. */
    fun linkClass(depth: Int): Int = if (depth % 2 == 0) UNIT else 2 * UNIT

    private const val TITLE_CHAR_WIDTH = 7.6
    private const val LABEL_CHAR_WIDTH = 6.4

    fun nodeWidth(chars: Int): Int {
        val wanted = 8 + 16 + 6 + Math.ceil(chars * TITLE_CHAR_WIDTH).toInt() + 10
        return wanted.coerceIn(MIN_NODE_WIDTH, MAX_NODE_WIDTH).let { it + it % 2 }
    }

    fun labelWidth(chars: Int): Int = if (chars == 0) 0 else Math.ceil(chars * LABEL_CHAR_WIDTH).toInt() + 8
}
