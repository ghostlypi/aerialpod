package org.aerialpod.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.aerialpod.android.AppActions
import org.aerialpod.android.AppGraph
import org.aerialpod.android.ui.components.Cover
import org.aerialpod.android.ui.components.EmptyHint
import org.aerialpod.android.ui.components.EpisodeRow
import org.aerialpod.android.ui.components.QueueRow
import org.aerialpod.android.ui.components.rememberQueueReorder
import org.aerialpod.android.ui.components.ScreenTitle
import org.aerialpod.android.ui.components.SectionHeader

private const val PREVIEW_LIMIT = 5

/** The desktop's four sections, same keys and same default order. */
private val SECTION_TITLES = mapOf(
    "queue" to "Queue",
    "continue" to "Continue Listening",
    "inbox" to "Inbox",
    "subscriptions" to "Subscriptions",
)
private val DEFAULT_SECTIONS = listOf("queue", "continue", "inbox", "subscriptions")

@Composable
fun HomeScreen(
    graph: AppGraph,
    onOpenEpisode: (Long) -> Unit,
    onOpenPodcast: (Long) -> Unit,
    onSeeAll: (String) -> Unit,
) {
    val library = graph.library
    val queue by remember(graph) { library.rows(library.queue) }
        .collectAsStateWithLifecycle(emptyList())
    val continues by remember(graph) { library.rows(library.inProgress(PREVIEW_LIMIT.toLong())) }
        .collectAsStateWithLifecycle(emptyList())
    val inbox by remember(graph) { library.rows(library.inbox(PREVIEW_LIMIT.toLong())) }
        .collectAsStateWithLifecycle(emptyList())
    val subscriptions by remember(graph) { library.subscriptions }
        .collectAsStateWithLifecycle(emptyList())
    val info by remember(graph) { library.displayInfo }
        .collectAsStateWithLifecycle(emptyMap())

    // Section order lives in the same `app_state` row the desktop writes, so a
    // device that syncs its settings keeps the same home screen.
    val order by produceState(DEFAULT_SECTIONS, graph) {
        value = withContext(Dispatchers.IO) {
            graph.repo.stateStringList(AppActions.STATE_HOME_SECTIONS, DEFAULT_SECTIONS)
                .filter { it in SECTION_TITLES }
                .ifEmpty { DEFAULT_SECTIONS }
        }
    }

    // The preview is the top of the same order the Queue screen shows, so an
    // index within it is the same index in the queue — which is what makes
    // reordering here mean the same thing as reordering there.
    val listState = rememberLazyListState()
    val reorder = rememberQueueReorder(
        stored = queue.take(PREVIEW_LIMIT),
        listState = listState,
        keyOf = { "queue-${it.episode.id}" },
        onMove = graph.actions::moveInQueue,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ScreenTitle("Home") }

        order.forEach { key ->
            when (key) {
                "queue" -> {
                    item { SectionHeader(SECTION_TITLES.getValue(key), "See all") { onSeeAll("queue") } }
                    if (reorder.rows.isEmpty()) {
                        item { EmptyHint("Queue is empty — add an episode from a podcast.") }
                    } else {
                        items(reorder.rows, key = { "queue-${it.episode.id}" }) { row ->
                            QueueRow(
                                row = row,
                                reorder = reorder,
                                onClick = { graph.player.play(row.episode.id) },
                                onRemove = { graph.actions.removeFromQueue(row.episode.id) },
                                onMarkPlayed = { graph.actions.markPlayed(row.episode.id) },
                                onMarkUnplayed = { graph.actions.markUnplayed(row.episode.id) },
                            )
                        }
                    }
                }
                "continue" -> episodeSection(
                    key = key,
                    title = SECTION_TITLES.getValue(key),
                    rows = continues,
                    empty = "Nothing in progress.",
                    onSeeAll = null,
                    onOpenEpisode = onOpenEpisode,
                    onToggleQueue = graph.actions::toggleQueue,
                    onMarkPlayed = graph.actions::markPlayed,
                    onMarkUnplayed = graph.actions::markUnplayed,
                )
                "inbox" -> episodeSection(
                    key = key,
                    title = SECTION_TITLES.getValue(key),
                    rows = inbox,
                    empty = "No new episodes.",
                    onSeeAll = { onSeeAll("inbox") },
                    onOpenEpisode = onOpenEpisode,
                    onToggleQueue = graph.actions::toggleQueue,
                    onMarkPlayed = graph.actions::markPlayed,
                    onMarkUnplayed = graph.actions::markUnplayed,
                )
                "subscriptions" -> {
                    item { SectionHeader(SECTION_TITLES.getValue(key), "See all") { onSeeAll("podcasts") } }
                    if (subscriptions.isEmpty()) {
                        item { EmptyHint("No subscriptions yet — add a feed from the Podcasts tab.") }
                    } else {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(subscriptions, key = { it.id }) { podcast ->
                                    Column(
                                        modifier = Modifier
                                            .width(96.dp)
                                            .clickable { onOpenPodcast(podcast.id) },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Cover(info[podcast.id]?.imageUrl, size = 96.dp, corner = 10.dp)
                                        Text(
                                            info[podcast.id]?.title ?: podcast.feed_url,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.episodeSection(
    key: String,
    title: String,
    rows: List<org.aerialpod.core.db.Library.EpisodeRow>,
    empty: String,
    onSeeAll: (() -> Unit)?,
    onOpenEpisode: (Long) -> Unit,
    onToggleQueue: (Long) -> Unit,
    onMarkPlayed: (Long) -> Unit,
    onMarkUnplayed: (Long) -> Unit,
) {
    item {
        SectionHeader(
            title = title,
            actionLabel = if (onSeeAll != null) "See all" else null,
            onAction = onSeeAll,
        )
    }
    if (rows.isEmpty()) {
        item { EmptyHint(empty) }
    } else {
        // Keys are namespaced by section, because all three share one
        // LazyColumn and one episode can legitimately be in two of them — every
        // queued episode is also in the inbox until it is played. A bare
        // episode id then appears twice and Compose throws.
        items(rows, key = { "$key-${it.episode.id}" }) { row ->
            EpisodeRow(
                row = row,
                onClick = { onOpenEpisode(row.episode.id) },
                onToggleQueue = { onToggleQueue(row.episode.id) },
                onMarkPlayed = { onMarkPlayed(row.episode.id) },
                onMarkUnplayed = { onMarkUnplayed(row.episode.id) },
            )
        }
    }
}
