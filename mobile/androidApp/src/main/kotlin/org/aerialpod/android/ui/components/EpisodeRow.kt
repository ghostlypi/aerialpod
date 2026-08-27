package org.aerialpod.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.aerialpod.core.db.Library

/**
 * One episode: cover, title, a meta line, and progress if it has been started.
 *
 * The row never queries anything — `Library.rows()` has already joined the
 * podcast title, the fallback cover and queue membership for the whole list.
 * That is the rule the desktop arrived at after per-row lookups made opening a
 * podcast hang, and it applies harder on a phone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(
    row: Library.EpisodeRow,
    onClick: () -> Unit,
    onToggleQueue: () -> Unit,
    onMarkPlayed: () -> Unit,
    onMarkUnplayed: () -> Unit,
    showPodcast: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ep = row.episode
    val played = ep.state == "played"
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                // The press is reported the moment it becomes a long press,
                // before the finger lifts — without it there is no way to tell
                // a menu that is about to open from a tap that did nothing.
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DownloadBadgeCover(row, size = 56.dp)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                ep.title ?: "(untitled)",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (played) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            val total = if (ep.total_secs > 0) ep.total_secs else ep.duration_secs ?: 0
            // A started episode shows what is left; an untouched one shows how
            // long it is. Showing the full duration on something half-finished
            // is the reading that is actually wrong.
            val length = when {
                ep.position_secs > 0 && total > 0 -> formatRemaining(ep.position_secs, total)
                else -> formatDuration(total)
            }
            val meta = listOfNotNull(
                row.podcastTitle?.takeIf { showPodcast },
                formatDate(ep.pub_date).takeIf { it.isNotEmpty() },
                length.takeIf { it.isNotEmpty() },
                "Played".takeIf { played },
            ).joinToString("  ·  ")

            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (ep.position_secs > 0 && total > 0 && !played) {
                LinearProgressIndicator(
                    progress = { (ep.position_secs.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }

        if (trailing != null) {
            trailing()
        } else {
            IconButton(onClick = onToggleQueue) {
                Icon(
                    imageVector = if (row.inQueue) Icons.Filled.Remove else Icons.Filled.Add,
                    contentDescription = if (row.inQueue) "Remove from queue" else "Add to queue",
                    tint = if (row.inQueue) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (played && trailing == null) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Played",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }

        // Same shape as the desktop's right-click menu, minus the entries that
        // duplicate a control already on the row: play is the row's own tap and
        // the queue button is an inch to the right, so repeating them here
        // would be two ways to reach the same thing and none to reach this.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (played) {
                DropdownMenuItem(
                    text = { Text("Mark as unplayed") },
                    onClick = { menuOpen = false; onMarkUnplayed() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Mark as played") },
                    onClick = { menuOpen = false; onMarkPlayed() },
                )
                // Same action, said plainly: on a part-listened episode this
                // throws away a position, and a menu that does not say so is
                // how you lose your place with no way back.
                if (ep.position_secs > 0) {
                    DropdownMenuItem(
                        text = { Text("Mark as unplayed (reset progress)") },
                        onClick = { menuOpen = false; onMarkUnplayed() },
                    )
                }
            }
        }
    }
}

/**
 * Artwork with a download badge in the corner.
 *
 * A badge rather than a glyph in the meta line, and in the accent colour rather
 * than the muted grey the meta uses: "is this on my phone?" is a question you
 * ask while scanning a list — before boarding, or before starting something on
 * mobile data — and an answer you have to hunt for is no answer.
 *
 * Nothing is drawn for an episode that streams. A badge on every row would be
 * noise, and absence is the common case.
 */
@Composable
private fun DownloadBadgeCover(row: Library.EpisodeRow, size: Dp) {
    val ep = row.episode
    val badge: Pair<ImageVector, String>? = when {
        // A pin implies downloaded, so it replaces the tick rather than adding
        // a second badge.
        ep.keep_download != 0L && ep.download_state == "done" ->
            Icons.Filled.PushPin to "Kept on this device"
        ep.download_state == "done" -> Icons.Filled.DownloadDone to "Downloaded"
        ep.download_state == "downloading" -> Icons.Filled.Downloading to "Downloading"
        else -> null
    }

    Box {
        Cover(row.cover, size = size)
        if (badge != null) {
            val downloading = ep.download_state == "downloading"
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    // Outlined against the row so the badge reads as a badge on
                    // any artwork, including art that happens to be the accent.
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(
                        if (downloading) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary
                    ),
            ) {
                Icon(
                    badge.first,
                    contentDescription = badge.second,
                    tint = if (downloading) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
