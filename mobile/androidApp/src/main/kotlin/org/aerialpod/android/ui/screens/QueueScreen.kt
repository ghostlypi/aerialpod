package org.aerialpod.android.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aerialpod.android.AppGraph
import org.aerialpod.android.ui.components.EmptyHint
import org.aerialpod.android.ui.components.QueueRow
import org.aerialpod.android.ui.components.ScreenTitle
import org.aerialpod.android.ui.components.rememberQueueReorder

/** The derived queue, in order, with long-press drag to reorder. */
@Composable
fun QueueScreen(graph: AppGraph, onOpenEpisode: (Long) -> Unit) {
    val library = graph.library
    val stored by remember(graph) { library.rows(library.queue) }
        .collectAsStateWithLifecycle(emptyList())

    val listState = rememberLazyListState()
    val reorder = rememberQueueReorder(
        stored = stored,
        listState = listState,
        keyOf = { it.episode.id },
        onMove = graph.actions::moveInQueue,
    )

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item { ScreenTitle("Queue") }

        if (reorder.rows.isEmpty()) {
            item {
                EmptyHint(
                    "The queue is empty.\n\nIt is derived rather than stored — " +
                        "episodes arrive by auto-add, or because you queued them " +
                        "here or on another device."
                )
            }
        }

        items(reorder.rows, key = { it.episode.id }) { row ->
            QueueRow(
                row = row,
                reorder = reorder,
                // Tapping a queue row plays it — the queue is the "what now"
                // screen, and an extra hop to a detail page to press play is
                // one tap too many for its whole purpose.
                onClick = { graph.player.play(row.episode.id) },
                onRemove = { graph.actions.removeFromQueue(row.episode.id) },
                onMarkPlayed = { graph.actions.markPlayed(row.episode.id) },
                onMarkUnplayed = { graph.actions.markUnplayed(row.episode.id) },
            )
        }
    }
}
