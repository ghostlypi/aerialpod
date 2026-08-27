package org.aerialpod.core.lan

import org.aerialpod.core.db.AerialPodDatabase
import org.aerialpod.core.db.JvmDriverFactory
import org.aerialpod.core.db.Repo
import org.aerialpod.core.db.openDatabase
import org.aerialpod.core.sync.Matcher

/**
 * Two installs with identical libraries — exactly like two devices signed into
 * one gpodder.net account — so a test can check what crossing the wire does,
 * and doesn't, change.
 *
 * Mirrors the desktop's `tests/test_lan_merge.py` harness, including its
 * episode ids: both databases are seeded the same way, so `episode 3` means the
 * same episode on either side.
 */
const val FEED = "https://example.com/feed.xml"

class Device(deviceId: String, private val clock: () -> Long = { 1_700_000_000L }) {
    val db: AerialPodDatabase = openDatabase(JvmDriverFactory())
    val repo = Repo(db, clock)
    val sync: SnapshotSync

    init {
        repo.setState(Repo.LAN_DEVICE_ID, deviceId)
        sync = SnapshotSync(repo, Matcher(repo), clock)
        seed()
    }

    private fun seed() {
        db.podcastsQueries.insertPodcast(FEED, "clean", 1, clock())
        val podcastId = db.podcastsQueries.lastInsertId().executeAsOne()
        db.podcastsQueries.updatePodcastMeta(
            title = "Test Podcast", description = null, image_url = null, website = null,
            etag = null, http_last_modified = null, last_refresh = null, id = podcastId,
        )
        for (n in 1..5) {
            db.episodesQueries.insertEpisode(
                podcast_id = podcastId,
                guid = "guid-$podcastId-$n",
                media_url = "https://cdn.example.com/ep${n.toString().padStart(3, '0')}.mp3",
                title = "Episode $n",
                description = null,
                pub_date = 1_700_000_000L + n * 86_400L,
                duration_secs = null,
                mime = null,
                file_size = null,
                image_url = null,
            )
        }
    }

    val podcastId: Long get() = db.podcastsQueries.podcastByFeedUrl(FEED).executeAsOne().id

    fun queueIds(): List<Long> =
        db.queueQueries.queueItems().executeAsList().map { it.episode_id }

    fun queuePositions(): List<Long> =
        db.queueQueries.queueItems().executeAsList().map { it.position }

    fun isExcluded(episodeId: Long): Boolean =
        db.queueQueries.isExcluded(episodeId).executeAsOneOrNull() != null

    fun queueRow(episodeId: Long) =
        db.queueQueries.queueItemFor(episodeId).executeAsOneOrNull()

    fun episode(id: Long) = db.episodesQueries.episodeById(id).executeAsOne()

    /**
     * Write an intent with an explicit timestamp and apply it locally the way
     * the queue manager would — so a device set up this way looks like one
     * where the user really did press the button.
     */
    fun setIntent(
        episodeId: Long,
        intent: String,
        at: Long,
        by: String = "peer",
        position: Long = 0,
        pinned: Long = 0,
        origin: String = "manual",
    ) {
        db.transaction {
            repo.recordIntent(episodeId, intent, position, pinned, origin, at, by)
            if (intent == "excluded") {
                db.queueQueries.deleteQueueItem(episodeId)
                db.queueQueries.excludeEpisode(episodeId, at)
            } else {
                db.queueQueries.unexcludeEpisode(episodeId)
                db.queueQueries.upsertQueueItem(episodeId, position, origin, pinned, at)
            }
        }
    }

    fun setPosition(episodeId: Long, position: Long, total: Long, at: Long) {
        db.episodesQueries.setEpisodePosition(position, total, at, episodeId)
    }

    fun setEpisodeState(episodeId: Long, state: String) {
        db.episodesQueries.setEpisodeState(state, episodeId)
    }

    fun setSettings(
        customTitle: String? = null,
        speed: Double? = null,
        autoQueuePosition: String? = null,
        at: Long,
        by: String,
    ) {
        db.podcastsQueries.upsertAllSettings(
            podcast_id = podcastId,
            custom_title = customTitle,
            playback_speed = speed,
            skip_intro_secs = null,
            skip_outro_secs = null,
            auto_add_to_queue = null,
            auto_queue_position = autoQueuePosition,
            updated_at = at,
            updated_by = by,
        )
    }

    fun settings() = db.podcastsQueries.settingsForPodcast(podcastId).executeAsOneOrNull()
}
