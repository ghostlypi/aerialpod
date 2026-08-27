package org.aerialpod.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aerialpod.android.AppGraph
import org.aerialpod.android.ui.components.Cover
import org.aerialpod.android.ui.components.formatDuration
import org.aerialpod.core.db.Repo

/** The desktop's preset ladder (`repo.DEFAULTS["speed_presets"]`). */
private val SPEEDS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(graph: AppGraph, onBack: () -> Unit) {
    val state by graph.player.state.collectAsStateWithLifecycle()
    val skipForward by remember(graph) { graph.library.stateLong(Repo.SKIP_FWD_SECS, 30) }
        .collectAsStateWithLifecycle(30L)
    val skipBack by remember(graph) { graph.library.stateLong(Repo.SKIP_BACK_SECS, 10) }
        .collectAsStateWithLifecycle(10L)
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var showSleep by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(state.podcast, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(onClick = { showSleep = true }) {
                        Icon(Icons.Filled.Bedtime, contentDescription = "Sleep timer")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Cover(state.artworkUri, size = 240.dp, corner = 16.dp)

            Text(
                state.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (state.sleep.isNotEmpty()) {
                Text(
                    state.sleep,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // While a finger is down the slider shows where it is, not where
            // playback is — otherwise the 1 Hz tick fights the drag.
            val fraction = scrubbing ?: if (state.durationMs > 0) {
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            Slider(
                value = fraction,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    val target = scrubbing
                    scrubbing = null
                    if (target != null && state.durationMs > 0) {
                        graph.player.seekTo((target * state.durationMs).toLong())
                    }
                },
                enabled = state.durationMs > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    formatDuration((fraction * state.durationMs).toLong() / 1000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatDuration(state.durationMs / 1000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { graph.player.skipBack() }) {
                    Icon(
                        replayIcon(skipBack),
                        contentDescription = "Skip back ${'$'}skipBack seconds",
                        modifier = Modifier.size(34.dp),
                    )
                }
                FilledIconButton(
                    onClick = { graph.player.togglePlayPause() },
                    modifier = Modifier.size(68.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { graph.player.skipForward() }) {
                    Icon(
                        forwardIcon(skipForward),
                        contentDescription = "Skip forward ${'$'}skipForward seconds",
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            Text("Speed", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(state.speed - speed) < 0.01f,
                        onClick = { graph.player.setSpeed(speed) },
                        label = { Text(speedLabel(speed)) },
                    )
                }
            }
            Text(
                "Speed is saved for this podcast and replicates to your other devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showSleep) {
        AlertDialog(
            onDismissRequest = { showSleep = false },
            title = { Text("Sleep timer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(5, 15, 30, 45, 60).forEach { minutes ->
                        TextButton(onClick = {
                            graph.player.startSleepTimer(minutes)
                            showSleep = false
                        }) { Text("$minutes minutes") }
                    }
                    TextButton(onClick = {
                        graph.player.sleepAtEndOfEpisode()
                        showSleep = false
                    }) { Text("End of episode") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    graph.player.cancelSleepTimer()
                    showSleep = false
                }) { Text("Off") }
            },
            dismissButton = { TextButton(onClick = { showSleep = false }) { Text("Close") } },
        )
    }
}

/** "1×", "1.25×" — no trailing ".0". */
private fun speedLabel(speed: Float): String {
    val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
    return "$text×"
}

/**
 * The icon that matches the configured skip, where one exists.
 *
 * Material ships numbered icons only for 5, 10 and 30, and the setting goes to
 * 300 — so anything else gets the plain double-arrow rather than an icon that
 * confidently states the wrong number. The content description always carries
 * the real value.
 */
private fun forwardIcon(seconds: Long): ImageVector = when (seconds) {
    5L -> Icons.Filled.Forward5
    10L -> Icons.Filled.Forward10
    30L -> Icons.Filled.Forward30
    else -> Icons.Filled.FastForward
}

private fun replayIcon(seconds: Long): ImageVector = when (seconds) {
    5L -> Icons.Filled.Replay5
    10L -> Icons.Filled.Replay10
    30L -> Icons.Filled.Replay30
    else -> Icons.Filled.FastRewind
}
