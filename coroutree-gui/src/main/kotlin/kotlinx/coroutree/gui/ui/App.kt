package kotlinx.coroutree.gui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutree.gui.AppState
import kotlinx.coroutree.gui.OpenTrace

@Composable
fun App(state: AppState, onChooseTraceFile: () -> Unit, onChooseDir: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        val open = state.current
        if (open == null) {
            StartScreen(state.dir, rememberDirListing(state.dir), state::open, onChooseTraceFile, onChooseDir)
        } else {
            OpenTraceScreen(open, state)
        }
        state.message?.let { message ->
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).clip(RoundedCornerShape(8.dp))
                    .background(palette.text).padding(horizontal = 14.dp, vertical = 8.dp).testTag("message"),
            ) {
                Label(message, color = palette.surface, maxLines = 2)
            }
        }
    }
}

@Composable
private fun OpenTraceScreen(open: OpenTrace, state: AppState) {
    val snapshot by open.feed.snapshot.collectAsState()
    val status by open.feed.status.collectAsState()
    val failure by open.feed.failure.collectAsState()
    // The view model is what the panes read; hand it each published snapshot.
    LaunchedEffect(snapshot) { open.viewModel.snapshot = snapshot }
    TraceScreen(open.viewModel, open.feed.source.title, status, failure, state::openInIde, state::close)
}
