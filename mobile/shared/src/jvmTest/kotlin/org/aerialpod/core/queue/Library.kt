package org.aerialpod.core.queue

import org.aerialpod.core.db.AerialPodDatabase
import org.aerialpod.core.db.JvmDriverFactory
import org.aerialpod.core.db.Repo
import org.aerialpod.core.db.openDatabase

/**
 * One install's library, set up the way the desktop's `conftest.py` fixtures do
 * — same feed URL, same `guid-{pid}-{n}`, same pub_date ladder — so the
 * reconcile tests can be read side by side with `tests/test_queue_reconcile.py`.
 */
class Library(private val clock: () -> Long = { 1_700_000_000L }) {
    val db: AerialPodDatabase = openDatabase(JvmDriverFactory())
    val repo = Repo(db, clock)

    var intentChanges = 0
        private set
    var syncRequests = 0
        private set

    val queue = QueueManager(
        repo = repo,
        now = clock,
        onIntentChanged = { intentChanges++ },
        onSyncNeeded = { syncRequests++ },
    )

    /** One subscribed podcast, sync_state clean. */
    fun addPodcast(feedUrl: String = FEED, title: String = "Test Podcast"): Long {
        val id = repo.upsertPodcast(feedUrl, syncState = "clean")
        db.podcastsQueries.updatePodcastMeta(
            title = title, description = null, image_url = null, website = null,
            etag = null, http_last_modified = null, last_refresh = null, id = id,
        )
        return id
    }

    fun makeEpisode(
        podcastId: Long,
        n: Int,
        state: String = "new",
        position: Long = 0,
        total: Long = 0,
        updatedAt: Long = 0,
        pubDate: Long? = null,
    ): Long {
        db.episodesQueries.insertEpisode(
            podcast_id = podcastId,
            guid = "guid-$podcastId-$n",
            media_url = "https://cdn.example.com/ep${n.toString().padStart(3, '0')}.mp3",
            title = "Episode $n",
            description = null,
            pub_date = pubDate ?: (1_700_000_000L + n * 86_400L),
            duration_secs = null,
            mime = null,
            file_size = null,
            image_url = null,
        )
        val id = db.podcastsQueries.lastInsertId().executeAsOne()
        if (state != "new") db.episodesQueries.setEpisodeState(state, id)
        if (position != 0L || total != 0L || updatedAt != 0L) {
            db.episodesQueries.setEpisodePosition(position, total, updatedAt, id)
        }
        return id
    }

    /** Five fresh episodes, ep1 oldest … ep5 newest. */
    fun fiveEpisodes(podcastId: Long): List<Long> = (1..5).map { makeEpisode(podcastId, it) }

    /** A second podcast whose new episodes go to the top. */
    fun addDaily(): Long {
        val id = addPodcast("https://example.com/daily.xml", "Daily News")
        repo.updatePodcastSettings(id) { it.copy(autoAddToQueue = 1, autoQueuePosition = "front") }
        return id
    }

    fun queueIds(): List<Long> = repo.queueItems().map { it.episode_id }

    fun episode(id: Long) = db.episodesQueries.episodeById(id).executeAsOne()

    fun setEpisode(
        id: Long,
        state: String? = null,
        position: Long? = null,
        total: Long? = null,
        updatedAt: Long? = null,
    ) {
        val current = episode(id)
        if (state != null) db.episodesQueries.setEpisodeState(state, id)
        if (position != null || total != null || updatedAt != null) {
            db.episodesQueries.setEpisodePosition(
                position ?: current.position_secs,
                total ?: current.total_secs,
                updatedAt ?: current.position_updated_at,
                id,
            )
        }
    }

    companion object {
        const val FEED = "https://example.com/feed.xml"
    }
}
