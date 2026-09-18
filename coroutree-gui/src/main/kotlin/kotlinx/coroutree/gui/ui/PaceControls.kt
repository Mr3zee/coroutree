package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutree.gui.view.SpeedScale
import kotlinx.coroutree.gui.view.TraceViewModel
import kotlinx.coroutree.model.tree.PaceSetting
import kotlinx.coroutree.model.tree.paceLabel

/**
 * Execution control for the whole program (DESIGN §3.1, §6), under the top bar of a live session whose JVM has a gate:
 * pause / resume, step, and the speed. What it shows is the setting as the trace says it is — a command that has been
 * sent shows when it has come back in the stream, which is also when it holds.
 */
@Composable
fun PaceBar(viewModel: TraceViewModel) {
    if (!viewModel.paceable) return
    val setting = viewModel.globalPace
    Row(
        Modifier.fillMaxWidth().height(34.dp).background(palette.header).padding(horizontal = 12.dp).testTag("pace-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("EXECUTION", style = Type.label, color = palette.textDim)
        PaceButtons(setting, "pace", onToggle = { viewModel.togglePause() }, onStep = { viewModel.step() })
        SpeedSlider(setting, "pace-slider", width = 260.dp) { viewModel.setPace(it) }
        Label(settingText(setting), Modifier.testTag("pace-setting"), Type.small.copy(fontWeight = FontWeight.Medium), if (setting?.paused == true) palette.suspended else palette.text)
        Box(Modifier.weight(1f))
        Label("Space pauses · → steps · a node's menu does the same for its subtree", style = Type.small, color = palette.textDim)
    }
    Divider()
}

/** `paused`, `2 events/s per sequence`, `full speed`; before the trace has said anything, that. */
fun settingText(setting: PaceSetting?): String = when {
    setting == null -> "waiting for the agent…"
    setting.paused -> "paused" + if (setting.intervalNanos > 0) " (${paceLabel(setting.intervalNanos)} per sequence when resumed)" else ""
    setting.intervalNanos > 0 -> "${paceLabel(setting.intervalNanos)} per sequence"
    else -> "full speed"
}

/** Pause / resume and step, for the program or for a subtree; [tag] tells the two apart in tests. */
@Composable
fun PaceButtons(setting: PaceSetting?, tag: String, onToggle: () -> Unit, onStep: () -> Unit) {
    val paused = setting?.paused == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PaceButton(if (paused) "Resume" else "Pause", "$tag-toggle", primary = paused, onClick = onToggle) { color ->
            if (paused) PlayGlyph(color) else PauseGlyph(color)
        }
        PaceButton("Step", "$tag-step", primary = false, onClick = onStep) { color -> StepGlyph(color) }
    }
}

@Composable
private fun PaceButton(text: String, tag: String, primary: Boolean, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    val color = if (primary) palette.accent else palette.text
    Row(
        Modifier.clip(RoundedCornerShape(5.dp))
            .background(if (primary) palette.accent.copy(alpha = 0.14f) else Color.Transparent)
            .rowBackground(selected = false).handCursor()
            .clickable(onClick = onClick)
            .testTag(tag)
            .semantics { stateDescription = text }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        glyph(color)
        Label(text, style = Type.small.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

@Composable
private fun PauseGlyph(color: Color) = Canvas(Modifier.size(10.dp)) {
    val bar = Size(size.width * 0.32f, size.height)
    drawRoundRect(color, Offset(size.width * 0.08f, 0f), bar, CornerRadius(1.5f))
    drawRoundRect(color, Offset(size.width * 0.6f, 0f), bar, CornerRadius(1.5f))
}

@Composable
private fun PlayGlyph(color: Color) = Canvas(Modifier.size(10.dp)) {
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(size.width * 0.1f, 0f)
        lineTo(size.width, size.height / 2)
        lineTo(size.width * 0.1f, size.height)
        close()
    }, color)
}

@Composable
private fun StepGlyph(color: Color) = Canvas(Modifier.size(10.dp)) {
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, 0f)
        lineTo(size.width * 0.68f, size.height / 2)
        lineTo(0f, size.height)
        close()
    }, color)
    drawRoundRect(color, Offset(size.width * 0.76f, 0f), Size(size.width * 0.24f, size.height), CornerRadius(1.5f))
}

/**
 * The speed, on a logarithmic scale from one event in ten seconds to no limit (see [SpeedScale]). While it is being
 * dragged it shows where the thumb is; let go, the pace is sent, and from then on it shows what the trace says again.
 */
@Composable
fun SpeedSlider(setting: PaceSetting?, tag: String, width: Dp, onPace: (Long) -> Unit) {
    var dragged: Float? by remember { mutableStateOf(null) }
    val send by rememberUpdatedState(onPace)
    val position = dragged ?: SpeedScale.positionOf(setting?.intervalNanos ?: 0)
    val track = palette.border
    val fill = palette.accent
    val thumb = if (setting?.paused == true) palette.textDim else palette.accent
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("slow", style = Type.small, color = palette.textDim)
        Canvas(
            Modifier.width(width).height(18.dp).handCursor().testTag(tag)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..1f)
                    stateDescription = paceLabel(SpeedScale.intervalAt(position))
                    setProgress { target ->
                        send(SpeedScale.intervalAt(target))
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { at -> send(SpeedScale.intervalAt(at.x / size.width)) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { at -> dragged = (at.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragged?.let { send(SpeedScale.intervalAt(it)) }
                            dragged = null
                        },
                        onDragCancel = { dragged = null },
                    ) { change, _ ->
                        change.consume()
                        dragged = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val y = size.height / 2
            val x = position * size.width
            val thickness = 3.dp.toPx()
            drawRoundRect(track, Offset(0f, y - thickness / 2), Size(size.width, thickness), CornerRadius(thickness))
            drawRoundRect(fill.copy(alpha = 0.55f), Offset(0f, y - thickness / 2), Size(x, thickness), CornerRadius(thickness))
            // Where "no limit" begins.
            val unlimited = SpeedScale.UNLIMITED_FROM * size.width
            drawLine(track, Offset(unlimited, y - 5.dp.toPx()), Offset(unlimited, y + 5.dp.toPx()), 1.dp.toPx())
            drawCircle(thumb, 6.dp.toPx(), Offset(x.coerceIn(6.dp.toPx(), size.width - 6.dp.toPx()), y))
        }
        Label("∞", style = Type.small, color = palette.textDim)
        if (dragged != null) Label(paceLabel(SpeedScale.intervalAt(position)), style = Type.small, color = palette.accent)
    }
}

/**
 * The mark on a node that carries a setting of its own: paused, or slowed down. It is the *setting*, read from the
 * trace, not an observation: which threads are being held right now nobody outside the JVM knows.
 */
@Composable
fun PaceMarker(setting: PaceSetting, modifier: Modifier = Modifier) {
    val color = if (setting.paused) palette.suspended else palette.accent
    Box(modifier.size(15.dp).clip(RoundedCornerShape(4.dp)).background(palette.surface).background(color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        if (setting.paused) {
            Canvas(Modifier.size(7.dp)) {
                val bar = Size(size.width * 0.32f, size.height)
                drawRect(color, Offset(size.width * 0.08f, 0f), bar)
                drawRect(color, Offset(size.width * 0.6f, 0f), bar)
            }
        } else {
            // A clock face: this subtree has a pace of its own.
            Canvas(Modifier.size(9.dp)) {
                val stroke = 1.3.dp.toPx()
                drawCircle(color, size.minDimension / 2 - stroke / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(color, center, Offset(center.x, center.y - size.height * 0.3f), stroke)
                drawLine(color, center, Offset(center.x + size.width * 0.22f, center.y), stroke)
            }
        }
    }
}

const val THREADS_NOT_COROUTINES = "We hold threads, not coroutines: a held coroutine keeps the thread it is on, and whatever else would run there waits with it."

/** Space pauses and resumes, → steps; with a modifier key held they are somebody else's shortcuts. */
fun controlKeyOf(event: KeyEvent): TraceViewModel.ControlKey? {
    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return null
    return when (event.key) {
        Key.Spacebar -> TraceViewModel.ControlKey.PAUSE_RESUME
        Key.DirectionRight -> TraceViewModel.ControlKey.STEP
        else -> null
    }
}
