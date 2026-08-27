package org.aerialpod.core.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The library, as flows.
 *
 * Every screen reads from here rather than calling [Repo] on a timer. SQLDelight
 * notifies on the tables a query touches, so anything that writes — a queue
 * edit, a feed refresh, a gpodder pull, a peer's snapshot landing — repaints the
 * screens that care without a single line joining the two. That matters most for
 * the case the app cannot predict: a merge arriving from another device while
 * the user is looking at the queue.
 *
 * `mapToList` moves the query off the caller's thread, so these are safe to
 * collect from the UI.
 */
class Library(
    private val repo: Repo,
    private val io: CoroutineDispatcher,
) {
    private val db = repo.db

    val queue: Flow<List<Episodes>> =
        db.queueQueries.queueEpisodes().asFlow().mapToList(io)

    val queuedIds: Flow<Set<Long>> =
        db.queueQueries.queueEpisodeIds().asFlow().mapToList(io).map { it.toSet() }

    val subscriptions: Flow<List<Podcasts>> =
        db.podcastsQueries.selectSubscribed().asFlow().mapToList(io)

    /**
     * Titles and covers for every podcast, keyed by id.
     *
     * Separate from [subscriptions] because episode lists need it for podcasts
     * the user has since unsubscribed from — an episode still in the queue has
     * to keep its name.
     */
    val displayInfo: Flow<Map<Long, Repo.PodcastDisplay>> =
        db.podcastsQueries.podcastDisplayInfo().asFlow().mapToList(io)
            .map { rows -> rows.associate { it.id to Repo.PodcastDisplay(it.id, it.title, it.image_url) } }

    fun inbox(limit: Long = 200): Flow<List<Episodes>> =
        db.episodesQueries.inboxEpisodes(limit).asFlow().mapToList(io)

    fun inProgress(limit: Long = 200): Flow<List<Episodes>> =
        db.episodesQueries.inProgressEpisodes(limit).asFlow().mapToList(io)

    fun episodesFor(podcastId: Long, limit: Long = 500): Flow<List<Episodes>> =
        db.episodesQueries.episodesForPodcast(podcastId, limit).asFlow().mapToList(io)

    /** One episode, following any later edit to it. */
    fun episode(episodeId: Long): Flow<Episodes?> =
        db.episodesQueries.episodeById(episodeId).asFlow().mapToList(io).map { it.firstOrNull() }

    fun podcast(podcastId: Long): Flow<Podcasts?> =
        db.podcastsQueries.podcastById(podcastId).asFlow().mapToList(io).map { it.firstOrNull() }

    /**
     * One podcast's settings, following edits.
     *
     * A flow rather than a one-shot read because the settings screen writes
     * asynchronously: a snapshot taken when the dialog opened would show the
     * old value after every tap.
     */
    fun settings(podcastId: Long): Flow<PodcastSettings> =
        db.podcastsQueries.settingsForPodcast(podcastId).asFlow().mapToList(io)
            .map { PodcastSettings.from(it.firstOrNull()) }

    /** Everything currently on disk or being fetched. */
    val downloaded: Flow<List<Episodes>> =
        db.episodesQueries.downloadedEpisodes().asFlow().mapToList(io)

    /**
     * One `app_state` value, following writes.
     *
     * SQLDelight notifies on the table the query touches, so a setting changed
     * anywhere — including by a peer's merge — reaches the screen showing it
     * without anything wiring the two together.
     */
    fun stateLong(key: String, default: Long): Flow<Long> =
        db.syncQueries.getState(key).asFlow().mapToList(io).map { rows ->
            rows.firstOrNull()?.value_?.toLongOrNull() ?: default
        }

    /**
     * How much of the queue to keep downloaded, following changes.
     *
     * Reads through [Repo.downloadAhead] rather than one row, because the
     * setting falls back to the desktop's integer key when the mobile one has
     * never been written.
     */
    val downloadAhead: Flow<String> =
        db.syncQueries.getState(Repo.DOWNLOAD_AHEAD).asFlow().mapToList(io)
            .map { repo.downloadAhead() }

    /** An episode list with everything a row needs to draw itself. */
    fun rows(episodes: Flow<List<Episodes>>): Flow<List<EpisodeRow>> =
        combine(episodes, displayInfo, queuedIds) { list, info, queued ->
            list.map { ep ->
                EpisodeRow(
                    episode = ep,
                    podcastTitle = info[ep.podcast_id]?.title,
                    fallbackCover = info[ep.podcast_id]?.imageUrl,
                    inQueue = ep.id in queued,
                )
            }
        }

    data class EpisodeRow(
        val episode: Episodes,
        val podcastTitle: String?,
        val fallbackCover: String?,
        val inQueue: Boolean,
    ) {
        val cover: String? get() = episode.image_url ?: fallbackCover
    }
}
