package org.aerialpod.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aerialpod.android.AppGraph
import org.aerialpod.android.ui.components.Cover
import org.aerialpod.android.ui.components.EmptyHint
import org.aerialpod.android.ui.components.EpisodeRow
import org.aerialpod.android.ui.components.plainText
import org.aerialpod.core.db.PodcastSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    graph: AppGraph,
    podcastId: Long,
    onBack: () -> Unit,
    onOpenEpisode: (Long) -> Unit,
) {
    val library = graph.library
    val podcast by remember(podcastId) { library.podcast(podcastId) }
        .collectAsStateWithLifecycle(null)
    val rows by remember(podcastId) { library.rows(library.episodesFor(podcastId)) }
        .collectAsStateWithLifecycle(emptyList())
    val info by remember(graph) { library.displayInfo }
        .collectAsStateWithLifecycle(emptyMap())

    var showSettings by remember { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }

    val title = info[podcastId]?.title ?: podcast?.feed_url ?: "Podcast"

    Scaffold(
        // Zero insets: the app's outer Scaffold has already inset the NavHost
        // for the status bar, and a nested Scaffold would add it again — which
        // shows up as a band of empty space above the title.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { graph.actions.refreshPodcast(podcastId) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh this feed")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Cover(info[podcastId]?.imageUrl, size = 96.dp, corner = 12.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        val blurb = plainText(podcast?.description)
                        if (blurb.isNotEmpty()) {
                            Text(
                                blurb,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            TextButton(onClick = { showSettings = true }) { Text("Settings") }
                            TextButton(onClick = { confirmUnsubscribe = true }) { Text("Unsubscribe") }
                        }
                    }
                }
            }

            if (rows.isEmpty()) {
                item { EmptyHint("No episodes yet — pull the feed with the refresh button.") }
            }

            items(rows, key = { it.episode.id }) { row ->
                EpisodeRow(
                    row = row,
                    showPodcast = false,
                    onClick = { onOpenEpisode(row.episode.id) },
                    onToggleQueue = { graph.actions.toggleQueue(row.episode.id) },
                    onMarkPlayed = { graph.actions.markPlayed(row.episode.id) },
                    onMarkUnplayed = { graph.actions.markUnplayed(row.episode.id) },
                )
            }
        }
    }

    if (showSettings) {
        PodcastSettingsDialog(graph, podcastId) { showSettings = false }
    }

    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text("Unsubscribe?") },
            text = {
                Text(
                    "Its episodes leave the queue immediately. The unsubscribe is " +
                        "queued for gpodder.net, so your other devices follow."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    graph.actions.unsubscribe(podcastId)
                    onBack()
                }) { Text("Unsubscribe") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnsubscribe = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The three replicated per-podcast settings.
 *
 * Each offers "Default" as well as an explicit value, because null and the
 * global default are genuinely different: null follows the global setting when
 * it changes, an explicit value does not.
 */
@Composable
private fun PodcastSettingsDialog(graph: AppGraph, podcastId: Long, onDismiss: () -> Unit) {
    val current by remember(podcastId) { graph.library.settings(podcastId) }
        .collectAsStateWithLifecycle(PodcastSettings())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Podcast settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add new episodes to the queue", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<Pair<String, Long?>>(
                        "Default" to null, "Yes" to 1L, "No" to 0L,
                    ).forEach { (label, value) ->
                        FilterChip(
                            selected = current.autoAddToQueue == value,
                            onClick = { graph.actions.setPodcastAutoAdd(podcastId, value) },
                            label = { Text(label) },
                        )
                    }
                }

                Text("Where new episodes land", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<Pair<String, String?>>(
                        "Default" to null, "Front" to "front", "Back" to "back",
                    ).forEach { (label, value) ->
                        FilterChip(
                            selected = current.autoQueuePosition == value,
                            onClick = { graph.actions.setPodcastQueuePosition(podcastId, value) },
                            label = { Text(label) },
                        )
                    }
                }

                Text("Playback speed", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<Pair<String, Double?>>(
                        "Default" to null, "1×" to 1.0, "1.25×" to 1.25, "1.5×" to 1.5,
                    ).forEach { (label, value) ->
                        FilterChip(
                            selected = current.playbackSpeed == value,
                            onClick = { graph.actions.setPodcastSpeed(podcastId, value) },
                            label = { Text(label) },
                        )
                    }
                }

                Text(
                    "These replicate to your other devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
