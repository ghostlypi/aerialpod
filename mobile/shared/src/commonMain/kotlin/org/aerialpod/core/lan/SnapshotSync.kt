package org.aerialpod.core.lan

import org.aerialpod.core.db.Episodes
import org.aerialpod.core.db.Repo
import org.aerialpod.core.epochSeconds
import org.aerialpod.core.sync.Matcher

/**
 * Building snapshots, and merging a peer's version of the truth — the port of
 * `lan/state.py`.
 *
 * Every record carries its own timestamp and the device that wrote it, and the
 * newer one wins: the same last-writer-wins rule the gpodder path already uses
 * for playback positions, extended to the state gpodder cannot carry. Ties
 * break on device id so that two peers resolving the same conflict never
 * disagree — which only works because both ends compare the same two strings
 * the same way.
 */
class SnapshotSync(
    private val repo: Repo,
    private val matcher: Matcher,
    private val now: () -> Long = ::epochSeconds,
) {
    private val db get() = repo.db

    // ---------------------------------------------------------------- building

    fun buildSnapshot(): Snapshot = Snapshot(
        intents = db.queueQueries.intentsForSnapshot().executeAsList().map {
            IntentRecord(
                feed = it.feed_url,
                guid = it.guid,
                media = it.media_url,
                intent = it.intent,
                position = it.position,
                pinned = it.pinned,
                origin = it.origin,
                updatedAt = it.updated_at,
                updatedBy = it.updated_by,
            )
        },
        settings = db.podcastsQueries.settingsForSnapshot().executeAsList().map {
            SettingsRecord(
                feed = it.feed_url,
                customTitle = it.custom_title,
                playbackSpeed = it.playback_speed,
                skipIntroSecs = it.skip_intro_secs,
                skipOutroSecs = it.skip_outro_secs,
                autoAddToQueue = it.auto_add_to_queue,
                autoQueuePosition = it.auto_queue_position,
                updatedAt = it.updated_at,
                updatedBy = it.updated_by,
            )
        },
        positions = db.episodesQueries.positionsForSnapshot(POSITION_LIMIT).executeAsList().map {
            PositionRecord(
                feed = it.feed_url,
                guid = it.guid,
                media = it.media_url,
                position = it.position_secs,
                total = it.total_secs,
                updatedAt = it.position_updated_at,
                finished = it.state == "played",
            )
        },
    )

    /**
     * Newest timestamp anywhere in the state a snapshot carries.
     *
     * Seconds resolution, so two writes in the same second read as one version.
     * On the desktop that is why only the periodic re-broadcast consults this.
     * The mobile peer has no periodic re-broadcast at all — see
     * docs/mobile-lan-sync.md — so this exists for diagnostics and for deciding
     * whether a reconnect has anything new to say.
     */
    fun replicatedVersion(): Long =
        db.syncQueries.replicatedVersion().executeAsOneOrNull()?.MAX ?: 0L

    fun positionMessage(episode: Episodes): PositionMessage? {
        val podcast = repo.podcastById(episode.podcast_id) ?: return null
        return PositionMessage(
            record = PositionRecord(
                feed = podcast.feed_url,
                guid = episode.guid,
                media = episode.media_url,
                position = episode.position_secs,
                total = episode.total_secs,
                updatedAt = if (episode.position_updated_at > 0) episode.position_updated_at else now(),
                finished = episode.state == "played",
            )
        )
    }

    fun positionMessageFor(episodeId: Long): PositionMessage? =
        repo.episodeById(episodeId)?.let(::positionMessage)

    // ---------------------------------------------------------------- merging

    /**
     * Apply a peer's snapshot. Returns per-section counts of what changed.
     *
     * Resolution happens before the transaction opens: the URL matching ladder
     * records aliases as a side effect, and those writes must not ride inside —
     * or be rolled back with — the merge itself.
     */
    fun mergeSnapshot(snapshot: Snapshot): MergeCounts {
        require(snapshot.v == SNAPSHOT_VERSION) {
            "unsupported snapshot version ${snapshot.v}"
        }

        val positions = snapshot.positions.mapNotNull { rec ->
            matcher.resolveEpisode(rec.feed, rec.guid, rec.media)?.let { rec to it }
        }
        val intents = snapshot.intents.mapNotNull { rec ->
            matcher.resolveEpisode(rec.feed, rec.guid, rec.media)?.let { rec to it }
        }
        val settings = snapshot.settings.mapNotNull { rec ->
            matcher.matchPodcast(rec.feed)?.let { rec to it }
        }

        var counts = MergeCounts()
        db.transaction {
            for ((rec, episode) in positions) {
                counts += MergeCounts(positions = applyPosition(rec, episode))
            }
            for ((rec, episode) in intents) {
                counts += MergeCounts(intents = applyIntent(rec, episode))
            }
            for ((rec, podcast) in settings) {
                counts += MergeCounts(settings = applySettings(rec, podcast.id))
            }
            if (counts.intents > 0) renumberQueue()
        }
        return counts
    }

    /** Handle a live position push. True if it moved our copy forward. */
    fun applyPositionMessage(message: PositionMessage): Boolean {
        val record = message.record
        val episode = matcher.resolveEpisode(record.feed, record.guid, record.media) ?: return false
        var changed = 0
        db.transaction { changed = applyPosition(record, episode) }
        return changed > 0
    }

    // ---------------------------------------------------------------- sections

    private fun applyPosition(record: PositionRecord, episode: Episodes): Int {
        if (record.updatedAt <= episode.position_updated_at) return 0
        // A finished episode with no position is a real thing — marked played
        // without listening — and the state is then the whole message.
        if (record.position <= 0 && !record.finished) return 0

        // A sender that says it is done is believed, because a position cannot
        // say it: an episode abandoned partway and then marked played is
        // indistinguishable from one still in progress. Only `true` is acted
        // on — see PositionRecord.finished.
        if (record.finished && episode.state != "played") {
            db.episodesQueries.setEpisodeState("played", episode.id)
        }

        if (record.position <= 0) {
            db.episodesQueries.setEpisodePositionOnly(0, record.updatedAt, episode.id)
            return 1
        }
        if (record.total > 0) {
            db.episodesQueries.setEpisodePosition(
                record.position, record.total, record.updatedAt, episode.id,
            )
        } else {
            db.episodesQueries.setEpisodePositionOnly(
                record.position, record.updatedAt, episode.id,
            )
        }
        return 1
    }

    private fun applyIntent(record: IntentRecord, episode: Episodes): Int {
        if (record.intent != "queued" && record.intent != "excluded") return 0
        val local = db.queueQueries.intentFor(episode.id).executeAsOneOrNull()
        if (local != null && !isNewer(record.updatedAt, record.updatedBy, local.updated_at, local.updated_by)) {
            return 0
        }

        repo.recordIntent(
            episodeId = episode.id,
            intent = record.intent,
            position = record.position,
            pinned = record.pinned,
            origin = record.origin,
            updatedAt = record.updatedAt,
            updatedBy = record.updatedBy,
        )

        if (record.intent == "queued") {
            db.queueQueries.unexcludeEpisode(episode.id)
            db.queueQueries.upsertQueueItem(
                episode_id = episode.id,
                position = record.position,
                origin = record.origin,
                pinned = record.pinned,
                added_at = now(),
            )
        } else {
            db.queueQueries.deleteQueueItem(episode.id)
            db.queueQueries.excludeEpisode(
                episode.id,
                if (record.updatedAt > 0) record.updatedAt else now(),
            )
        }
        return 1
    }

    private fun applySettings(record: SettingsRecord, podcastId: Long): Int {
        val local = db.podcastsQueries.settingsStamp(podcastId).executeAsOneOrNull()
        if (local != null && !isNewer(record.updatedAt, record.updatedBy, local.updated_at, local.updated_by)) {
            return 0
        }
        db.podcastsQueries.upsertAllSettings(
            podcast_id = podcastId,
            custom_title = record.customTitle,
            playback_speed = record.playbackSpeed,
            skip_intro_secs = record.skipIntroSecs,
            skip_outro_secs = record.skipOutroSecs,
            auto_add_to_queue = record.autoAddToQueue,
            auto_queue_position = record.autoQueuePosition,
            updated_at = record.updatedAt,
            updated_by = record.updatedBy ?: "",
        )
        return 1
    }

    /**
     * Last-writer-wins, with a deterministic tie-break so both ends of a
     * conflict reach the same answer without another round trip.
     *
     * The tie-break compares device ids as strings. Kotlin orders by UTF-16
     * code unit and Python by code point; those agree for the hex ids this
     * actually sees, and both sides must stay on that alphabet for the rule to
     * hold — a peer with a non-ASCII device id would be the thing that breaks it.
     */
    private fun isNewer(
        remoteAt: Long,
        remoteBy: String?,
        localAt: Long,
        localBy: String?,
    ): Boolean {
        if (remoteAt != localAt) return remoteAt > localAt
        return (remoteBy ?: "") > (localBy ?: "")
    }

    /**
     * Restore the 1024-gap scheme after a merge.
     *
     * Positions arrive from two devices that numbered independently, so they can
     * collide. The tie-break must be a value both ends agree on, which is why
     * the ordering query sorts on (feed, guid) rather than the local rowid.
     */
    private fun renumberQueue() {
        val ids = db.queueQueries.queueOrderForRenumber().executeAsList()
        for ((index, episodeId) in ids.withIndex()) {
            db.queueQueries.setQueuePosition((index + 1) * QUEUE_GAP, episodeId)
        }
    }
}
