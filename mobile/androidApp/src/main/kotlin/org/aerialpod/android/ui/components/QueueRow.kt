package org.aerialpod.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import org.aerialpod.core.db.Library

/**
 * A queue row with a drag handle, used wherever the queue is reorderable.
 *
 * Lifted while dragging so the row being moved is obviously the one under the
 * finger rather than one of several that shuffled.
 */
@Composable
fun QueueRow(
    row: Library.EpisodeRow,
    reorder: QueueReorder,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMarkPlayed: () -> Unit,
    onMarkUnplayed: () -> Unit,
    showPodcast: Boolean = true,
) {
    val dragging = reorder.isDragging(row)
    Surface(
        tonalElevation = if (dragging) 6.dp else 0.dp,
        shadowElevation = if (dragging) 6.dp else 0.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.graphicsLayer {
            translationY = if (dragging) reorder.offsetY else 0f
        },
    ) {
        EpisodeRow(
            row = row,
            showPodcast = showPodcast,
            onClick = onClick,
            onToggleQueue = onRemove,
            onMarkPlayed = onMarkPlayed,
            onMarkUnplayed = onMarkUnplayed,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Filled.Remove,
                            contentDescription = "Remove from queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = with(reorder) { Modifier.size(44.dp).dragHandle(row) },
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "Reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
}
