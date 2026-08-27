package org.aerialpod.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import org.aerialpod.android.AppGraph
import org.aerialpod.android.ui.components.Cover
import org.aerialpod.android.ui.components.formatDate
import org.aerialpod.android.ui.components.formatDuration
import org.aerialpod.android.ui.components.formatRemaining
import org.aerialpod.android.ui.components.plainText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailScreen(
    graph: AppGraph,
    episodeId: Long,
    onBack: () -> Unit,
    onOpenPodcast: (Long) -> Unit,
) {
    val library = graph.library
    val episode by remember(episodeId) { library.episode(episodeId) }
        .collectAsStateWithLifecycle(null)
    val info by remember(graph) { library.displayInfo }
        .collectAsStateWithLifecycle(emptyMap())
    val queued by remember(episodeId) {
        library.queuedIds.map { episodeId in it }
    }.collectAsStateWithLifecycle(false)

    val ep = episode

    Scaffold(
        // Zero insets: the app's outer Scaffold has already inset the NavHost
        // for the status bar, and a nested Scaffold would add it again — which
        // shows up as a band of empty space above the title.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Episode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (ep == null) {
            Text(
                "This episode is no longer here.",
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }

        val display = info[ep.podcast_id]
        val total = if (ep.total_secs > 0) ep.total_secs else ep.duration_secs ?: 0
        val played = ep.state == "played"

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Cover(ep.image_url ?: display?.imageUrl, size = 96.dp, corner = 12.dp)
                Column {
                    Text(ep.title ?: "(untitled)", style = MaterialTheme.typography.titleMedium)
                    if (display != null) {
                        TextButton(
                            onClick = { onOpenPodcast(ep.podcast_id) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) { Text(display.title) }
                    }
                    Text(
                        listOfNotNull(
                            formatDate(ep.pub_date).takeIf { it.isNotEmpty() },
                            formatDuration(total).takeIf { it.isNotEmpty() },
                            "Played".takeIf { played },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (ep.position_secs > 0 && total > 0 && !played) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { (ep.position_secs.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatDuration(ep.position_secs)} in  ·  " +
                            formatRemaining(ep.position_secs, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { graph.player.play(ep.id) }) {
                    Text(if (ep.position_secs > 0 && !played) "Resume" else "Play")
                }
                OutlinedButton(onClick = { graph.actions.toggleQueue(ep.id) }) {
                    Text(if (queued) "Remove from queue" else "Add to queue")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        if (played) graph.actions.markUnplayed(ep.id)
                        else graph.actions.markPlayed(ep.id)
                    },
                ) {
                    Text(if (played) "Mark unplayed" else "Mark played")
                }
                TextButton(
                    onClick = { graph.actions.setKeepDownload(ep.id, ep.keep_download == 0L) },
                ) {
                    Text(if (ep.keep_download != 0L) "Unpin download" else "Keep downloaded")
                }
            }
            Text(
                when {
                    ep.keep_download != 0L -> "Pinned — kept on this device until you unpin it."
                    ep.download_state == "done" -> "Downloaded."
                    ep.download_state == "downloading" -> "Downloading…"
                    else -> "Streams. The first few queue items download ahead automatically."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val notes = plainText(ep.description)
            if (notes.isNotEmpty()) {
                Text("Show notes", style = MaterialTheme.typography.titleSmall)
                Text(notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
