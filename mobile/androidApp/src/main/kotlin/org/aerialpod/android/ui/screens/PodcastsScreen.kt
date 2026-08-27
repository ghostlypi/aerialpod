package org.aerialpod.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.aerialpod.android.ui.components.ScreenTitle

@Composable
fun PodcastsScreen(graph: AppGraph, onOpenPodcast: (Long) -> Unit) {
    val library = graph.library
    val podcasts by remember(graph) { library.subscriptions }
        .collectAsStateWithLifecycle(emptyList())
    val info by remember(graph) { library.displayInfo }
        .collectAsStateWithLifecycle(emptyMap())

    var showAdd by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("Podcasts", modifier = Modifier.weight(1f))
                IconButton(onClick = { graph.actions.refreshAll() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh all feeds")
                }
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add a feed")
                }
            }
        }

        if (podcasts.isEmpty()) {
            item {
                EmptyHint(
                    "No subscriptions yet.\n\nAdd a feed URL with +, or sign in to " +
                        "gpodder.net from Settings to pull the ones you already have."
                )
            }
        }

        items(podcasts, key = { it.id }) { podcast ->
            val display = info[podcast.id]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPodcast(podcast.id) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Cover(display?.imageUrl, size = 56.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        display?.title ?: podcast.feed_url,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        podcast.feed_url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddFeedDialog(
            onDismiss = { showAdd = false },
            onAdd = { url ->
                showAdd = false
                graph.actions.addPodcast(url)
            },
        )
    }
}

@Composable
private fun AddFeedDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Feed URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The subscription is queued for gpodder.net too, so your other " +
                        "devices pick it up on their next sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(url) }, enabled = url.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
