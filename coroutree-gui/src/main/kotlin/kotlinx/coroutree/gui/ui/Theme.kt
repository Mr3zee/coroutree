package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutree.gui.view.graph.EdgeKind
import kotlinx.coroutree.model.EventKind
import kotlinx.coroutree.model.NodeState

@Immutable
data class Palette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val header: Color,
    val border: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val selection: Color,
    /** Rows of the log that belong to the selected node. */
    val related: Color,
    val hover: Color,
    val warningSurface: Color,
    val warningText: Color,
    // node states
    val active: Color,
    val suspended: Color,
    val blocked: Color,
    val cancelling: Color,
    val finished: Color,
    val failed: Color,
    val cancelled: Color,
    val contextChange: Color,
    // the graph: its background, structural edges, and one colour per kind of cross-link (each also has a dash pattern
    // of its own). Node kinds have no colours: a box is coloured by its state and nothing else (DESIGN §6.1).
    val canvas: Color,
    val edge: Color,
    val launchedFrom: Color,
    val cancels: Color,
    val interrupts: Color,
    val runsOn: Color,
) {
    fun of(state: NodeState): Color = when (state) {
        NodeState.ACTIVE -> active
        NodeState.SUSPENDED -> suspended
        NodeState.BLOCKED -> blocked
        NodeState.CANCELLING -> cancelling
        NodeState.COMPLETED -> finished
        NodeState.FAILED -> failed
        NodeState.CANCELLED -> cancelled
        NodeState.UNSPECIFIED -> textDim
    }

    fun of(kind: EdgeKind): Color = when (kind) {
        EdgeKind.LAUNCHED_FROM -> launchedFrom
        EdgeKind.CANCELS -> cancels
        EdgeKind.INTERRUPTS -> interrupts
        EdgeKind.RUNS_ON -> runsOn
    }

    /** Events are tinted by what they are about, in the colour of the state they lead to. */
    fun of(kind: EventKind): Color = when (kind) {
        EventKind.LAUNCHED, EventKind.DISCOVERED, EventKind.RESUMED -> active
        EventKind.SUSPENDED -> suspended
        EventKind.CONTEXT_CHANGED, EventKind.DISPATCHER_CHANGED -> contextChange
        EventKind.EXCEPTION_THROWN, EventKind.EXCEPTION_PROPAGATED, EventKind.EXCEPTION_HANDLED -> failed
        EventKind.CANCELLATION_REQUESTED, EventKind.CANCELLATION_PROPAGATED, EventKind.CANCELLING -> cancelling
        EventKind.THREAD_BLOCKED, EventKind.THREAD_UNBLOCKED, EventKind.THREAD_INTERRUPTED -> blocked
        EventKind.FINISHED -> finished
        EventKind.UNSPECIFIED -> textDim
    }
}

val LightPalette = Palette(
    dark = false,
    background = Color(0xFFF2F3F5),
    surface = Color(0xFFFFFFFF),
    header = Color(0xFFF7F8FA),
    border = Color(0xFFD9DCE1),
    text = Color(0xFF1F2328),
    textDim = Color(0xFF6E7681),
    accent = Color(0xFF2F6FDE),
    selection = Color(0xFFD6E4FF),
    related = Color(0xFFFFF3C4),
    hover = Color(0xFFEEF0F3),
    warningSurface = Color(0xFFFFF4D6),
    warningText = Color(0xFF6A4B00),
    active = Color(0xFF1A7F37),
    suspended = Color(0xFF2F6FDE),
    blocked = Color(0xFFBC4C00),
    cancelling = Color(0xFF8250DF),
    finished = Color(0xFF6E7681),
    failed = Color(0xFFCF222E),
    cancelled = Color(0xFF7D5A1E),
    contextChange = Color(0xFFB0620E),
    canvas = Color(0xFFFBFBFC),
    edge = Color(0xFF8C959F),
    launchedFrom = Color(0xFF2F6FDE),
    cancels = Color(0xFF8250DF),
    interrupts = Color(0xFFBC4C00),
    runsOn = Color(0xFF0E7C86),
)

val DarkPalette = Palette(
    dark = true,
    background = Color(0xFF1B1C1F),
    surface = Color(0xFF232529),
    header = Color(0xFF2B2D31),
    border = Color(0xFF3A3D43),
    text = Color(0xFFDFE1E5),
    textDim = Color(0xFF8B9099),
    accent = Color(0xFF6C9BF5),
    selection = Color(0xFF2D4475),
    related = Color(0xFF3F3622),
    hover = Color(0xFF2E3136),
    warningSurface = Color(0xFF3F3622),
    warningText = Color(0xFFF2CF7A),
    active = Color(0xFF5FB865),
    suspended = Color(0xFF6C9BF5),
    blocked = Color(0xFFF0883E),
    cancelling = Color(0xFFB392F0),
    finished = Color(0xFF8B9099),
    failed = Color(0xFFF47067),
    cancelled = Color(0xFFC9A561),
    contextChange = Color(0xFFE3A75A),
    canvas = Color(0xFF1F2023),
    edge = Color(0xFF747A84),
    launchedFrom = Color(0xFF6C9BF5),
    cancels = Color(0xFFB392F0),
    interrupts = Color(0xFFF0883E),
    runsOn = Color(0xFF4DB6BF),
)

private val LocalPalette = staticCompositionLocalOf { LightPalette }

val palette: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

object Type {
    val body = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val small = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val label = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
    val title = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val code = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
    val codeSmall = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun CoroutreeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(primary = colors.accent, background = colors.background, surface = colors.surface, onSurface = colors.text, onBackground = colors.text, outline = colors.border)
    } else {
        lightColorScheme(primary = colors.accent, background = colors.background, surface = colors.surface, onSurface = colors.text, onBackground = colors.text, outline = colors.border)
    }
    CompositionLocalProvider(LocalPalette provides colors) {
        MaterialTheme(colorScheme = scheme, typography = Typography(bodyMedium = Type.body, bodySmall = Type.small, labelLarge = Type.body.copy(fontWeight = FontWeight.Medium))) {
            content()
        }
    }
}
