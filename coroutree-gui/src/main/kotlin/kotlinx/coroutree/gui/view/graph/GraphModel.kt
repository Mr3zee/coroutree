package kotlinx.coroutree.gui.view.graph

/** The cross-links the graph draws (DESIGN §2.4); the structural parent → child edge is not one of them. */
enum class EdgeKind {
    LAUNCHED_FROM,
    CANCELS,
    INTERRUPTS,

    /** Coroutine → the thread it runs on right now. Every running coroutine has one, so only the selection's is drawn. */
    RUNS_ON,
}

/** A box to lay out. Sibling order is the order of [GraphModel.nodes]. */
data class GraphNode(
    val id: Long,
    /** 0, or an id that is not in the graph, for a root. */
    val parentId: Long,
    val title: String = "",
    /** Width the content asks for; the engine widens a box that needs more room for its ports. */
    val width: Int = GraphMetrics.MIN_NODE_WIDTH,
    /** Library machinery, shown because "show library / pools" is on. */
    val dimmed: Boolean = false,
)

/** A cross-link to route, drawn with an arrowhead at [to]. */
data class GraphLink(
    val kind: EdgeKind,
    val from: Long,
    val to: Long,
    /** Width of the label the edge would like to carry, 0 for none. The engine may grant less (DESIGN §6.2, rule 5). */
    val labelWidth: Int = 0,
)

/**
 * What is in the graph at one moment: the visible part of the structural forest and the cross-links between its nodes.
 * Plain data with structural equality: a snapshot that changes no box and no edge yields an equal model, and the
 * layout is not computed again.
 */
data class GraphModel(val nodes: List<GraphNode>, val links: List<GraphLink>) {
    companion object {
        val EMPTY = GraphModel(emptyList(), emptyList())
    }
}

/**
 * Sizes and distances of the drawing, in dp at zoom 1.
 *
 * Separation of lines is by construction, and this is what it is constructed from. Every vertical piece of a line
 * has an x of one of three residue classes modulo [COLUMN]: structural ports (box centres) are multiples of [COLUMN],
 * the cross-link ports and gap tracks that belong to a layer are at `+UNIT` on even layers and `+2·UNIT` on odd ones.
 * Two verticals that can meet in one channel are either of different classes, and then at least [UNIT] apart, or of
 * the same class and then [COLUMN] apart (or the trunk and a drop of one parent, which are one shape).
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

    /** Minimum distance between a line and a box it does not end at. */
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

    /** A label narrower than this says nothing; the edge goes without. */
    const val MIN_LABEL_WIDTH = 28

    /** Empty space around the drawing. */
    const val MARGIN = 24

    /** Residue class modulo [COLUMN] of the cross-link verticals that belong to the layer at [depth]. */
    fun linkClass(depth: Int): Int = if (depth % 2 == 0) UNIT else 2 * UNIT

    private const val TITLE_CHAR_WIDTH = 7.6
    private const val LABEL_CHAR_WIDTH = 6.4

    /** Box width for a title of [chars] monospace characters: icon, text and padding, within the allowed range, even. */
    fun nodeWidth(chars: Int): Int {
        val wanted = 8 + 16 + 6 + Math.ceil(chars * TITLE_CHAR_WIDTH).toInt() + 10
        return wanted.coerceIn(MIN_NODE_WIDTH, MAX_NODE_WIDTH).let { it + it % 2 }
    }

    fun labelWidth(chars: Int): Int = if (chars == 0) 0 else Math.ceil(chars * LABEL_CHAR_WIDTH).toInt() + 8
}
