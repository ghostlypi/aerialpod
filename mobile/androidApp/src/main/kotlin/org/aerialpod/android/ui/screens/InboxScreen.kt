package org.aerialpod.android.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aerialpod.android.AppGraph
import org.aerialpod.android.ui.components.EmptyHint
import org.aerialpod.android.ui.components.EpisodeRow
import org.aerialpod.android.ui.components.ScreenTitle

/** Everything unplayed and unstarted, newest first. */
@Composable
fun InboxScreen(graph: AppGraph, onOpenEpisode: (Long) -> Unit) {
    val library = graph.library
    val rows by remember(graph) { library.rows(library.inbox()) }
        .collectAsStateWithLifecycle(emptyList())

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { ScreenTitle("Inbox") }
        if (rows.isEmpty()) {
            item {
                EmptyHint(
                    "Nothing new. Episodes arrive here when a feed is refreshed " +
                        "or a peer's subscription lands."
                )
            }
        }
        items(rows, key = { it.episode.id }) { row ->
            EpisodeRow(
                row = row,
                onClick = { onOpenEpisode(row.episode.id) },
                onToggleQueue = { graph.actions.toggleQueue(row.episode.id) },
                onMarkPlayed = { graph.actions.markPlayed(row.episode.id) },
                onMarkUnplayed = { graph.actions.markUnplayed(row.episode.id) },
            )
        }
    }
}
