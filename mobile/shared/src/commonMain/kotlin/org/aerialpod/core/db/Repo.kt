package org.aerialpod.core.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.aerialpod.core.epochSeconds
import org.aerialpod.core.iso8601Utc
import org.aerialpod.core.lan.secureRandomBytes
import org.aerialpod.core.lan.toHex

/**
 * Typed accessors over the database — the port of `db/repo.py` that the sync
 * layer needs.
 *
 * `app_state` holds JSON-encoded values exactly as the desktop writes them, so
 * a database copied between the two would still read correctly. That costs one
 * parse per lookup and buys a schema that does not fork.
 */
class Repo(
    val db: AerialPodDatabase,
    private val now: () -> Long = ::epochSeconds,
) {
    private val json = Json

    // ---------------------------------------------------------------- app_state

    fun rawState(key: String): String? =
        db.syncQueries.getState(key).executeAsOneOrNull()?.value_

    fun setState(key: String, value: String) {
        db.syncQueries.setState(key, json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value)))
    }

    fun setState(key: String, value: Long) {
        db.syncQueries.setState(key, value.toString())
    }

    fun setState(key: String, value: Boolean) {
        db.syncQueries.setState(key, if (value) "true" else "false")
    }

    fun stateString(key: String, default: String): String =
        rawState(key)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: default

    fun stateLong(key: String, default: Long): Long =
        rawState(key)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?.let { (it as? JsonPrimitive)?.longOrNull } ?: default

    fun stateDouble(key: String, default: Double): Double =
        rawState(key)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: default

    /**
     * A JSON array of strings, which is how the desktop stores `home_sections`
     * and friends. Shape mismatches fall back rather than throw — the row is
     * shared with another program and editable by hand.
     */
    fun stateStringList(key: String, default: List<String>): List<String> {
        val raw = rawState(key) ?: return default
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return default
        return parsed.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    fun setState(key: String, value: List<String>) {
        db.syncQueries.setState(
            key,
            json.encodeToString(JsonArray.serializer(), JsonArray(value.map(::JsonPrimitive))),
        )
    }

    fun stateBool(key: String, default: Boolean): Boolean =
        rawState(key)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: default

    /**
     * Stable identity for this install on the peer mesh.
     *
     * Deliberately not the gpodder device id: that one is user-visible on
     * gpodder.net and can be renamed there, while this must stay stable for
     * peer bookkeeping and for the last-writer-wins tie-break — where two
     * devices comparing the same pair of ids have to reach the same answer.
     *
     * 16 random bytes as hex, which is the shape the desktop's `uuid4().hex`
     * produces, so neither side has to special-case the other's.
     */
    fun lanDeviceId(): String {
        rawState(LAN_DEVICE_ID)?.let { raw ->
            val existing = (runCatching { json.parseToJsonElement(raw) }.getOrNull()
                as? JsonPrimitive)?.contentOrNull
            if (!existing.isNullOrEmpty()) return existing
        }
        val generated = secureRandomBytes(16).toHex()
        setState(LAN_DEVICE_ID, generated)
        return generated
    }

    fun lanPort(): Int = stateLong(LAN_PORT, DEFAULT_LAN_PORT).toInt()

    fun lanSyncEnabled(): Boolean = stateBool(LAN_SYNC_ENABLED, true)

    // ---------------------------------------------------------------- podcasts

    fun podcastById(id: Long): Podcasts? =
        db.podcastsQueries.podcastById(id).executeAsOneOrNull()

    fun podcastByFeedUrl(url: String): Podcasts? =
        db.podcastsQueries.podcastByFeedUrl(url).executeAsOneOrNull()

    fun allPodcasts(): List<Podcasts> = db.podcastsQueries.selectAllPodcasts().executeAsList()

    fun subscribedPodcasts(): List<Podcasts> =
        db.podcastsQueries.selectSubscribed().executeAsList()

    /** Insert, or resubscribe an existing feed; returns its id. */
    fun upsertPodcast(
        feedUrl: String,
        syncState: String = "add_pending",
        subscribed: Long = 1,
    ): Long = db.transactionWithResult {
        val existing = podcastByFeedUrl(feedUrl)
        if (existing != null) {
            db.podcastsQueries.resubscribePodcast(subscribed, syncState, existing.id)
            existing.id
        } else {
            db.podcastsQueries.insertPodcast(feedUrl, syncState, subscribed, now())
            db.podcastsQueries.lastInsertId().executeAsOne()
        }
    }

    /**
     * Unsubscribe, and drop the podcast's episodes from the queue.
     *
     * The queue rows go immediately rather than waiting for reconcile: an
     * unsubscribed podcast has no claim on the queue, and leaving them until
     * the next pass would show the user episodes from a feed they just removed.
     */
    fun unsubscribePodcast(podcastId: Long) {
        db.transaction {
            db.podcastsQueries.unsubscribePodcast(podcastId)
            db.queueQueries.deleteQueueForPodcast(podcastId)
        }
    }

    // ---------------------------------------------------------------- episodes

    fun episodeById(id: Long): Episodes? =
        db.episodesQueries.episodeById(id).executeAsOneOrNull()

    /** Unplayed and unstarted, newest first — the Inbox. */
    fun inboxEpisodes(limit: Long = 200): List<Episodes> =
        db.episodesQueries.inboxEpisodes(limit).executeAsList()

    /** Started but not finished, most recently touched first. */
    fun inProgressEpisodes(limit: Long = 200): List<Episodes> =
        db.episodesQueries.inProgressEpisodes(limit).executeAsList()

    fun episodesForPodcast(podcastId: Long, limit: Long = 500): List<Episodes> =
        db.episodesQueries.episodesForPodcast(podcastId, limit).executeAsList()

    fun queueEpisodeIds(): Set<Long> =
        db.queueQueries.queueEpisodeIds().executeAsList().toSet()

    /**
     * `{podcast_id: (display title, cover)}` in one query.
     *
     * The desktop learned this the hard way — a per-row lookup while rendering
     * an episode list is what made opening a podcast hang.
     */
    fun podcastDisplayInfo(): Map<Long, PodcastDisplay> =
        db.podcastsQueries.podcastDisplayInfo().executeAsList()
            .associate { it.id to PodcastDisplay(it.id, it.title, it.image_url) }

    fun downloadedEpisodes(): List<Episodes> =
        db.episodesQueries.downloadedEpisodes().executeAsList()

    fun setDownloadState(episodeId: Long, state: String, path: String? = null) {
        db.episodesQueries.setDownloadState(path, state, episodeId)
    }

    /** Pin a download so the policy never evicts it. */
    fun setKeepDownload(episodeId: Long, keep: Boolean) {
        db.episodesQueries.setKeepDownload(if (keep) 1L else 0L, episodeId)
    }

    /**
     * How much of the queue to keep downloaded: a count, a fraction like
     * `1/4`, or `all`.
     *
     * A deliberate mobile-only extension. The desktop's `download_ahead_n` is
     * an integer and stays one — a fraction written into that key would crash
     * its `int()` — so this lives beside it and falls back to it, which keeps
     * an upgraded install on whatever it was already set to.
     */
    fun downloadAhead(): String =
        rawState(DOWNLOAD_AHEAD)?.let { stateString(DOWNLOAD_AHEAD, "") }?.takeIf { it.isNotEmpty() }
            ?: stateLong(DOWNLOAD_AHEAD_N, 1).toString()

    fun setDownloadAhead(value: String) {
        setState(DOWNLOAD_AHEAD, value)
        // Keep the desktop's key readable and roughly right, so a database that
        // ever meets the desktop does not hand it something it cannot parse.
        setState(DOWNLOAD_AHEAD_N, value.toLongOrNull() ?: 1L)
    }

    fun addAlias(episodeId: Long, url: String) {
        db.episodesQueries.addAlias(episodeId, url)
    }

    // ---------------------------------------------------------------- intent

    /**
     * Upsert one intent row — the replicated half of the queue.
     *
     * `updatedAt`/`updatedBy` are parameters rather than always-now because a
     * merge has to preserve the *peer's* stamp: re-stamping an incoming record
     * with our own clock would make it look locally authored and win every
     * subsequent conflict against the device that actually wrote it.
     */
    fun recordIntent(
        episodeId: Long,
        intent: String,
        position: Long = 0,
        pinned: Long = 0,
        origin: String = "manual",
        updatedAt: Long? = null,
        updatedBy: String? = null,
    ) {
        require(intent == "queued" || intent == "excluded") { "unknown intent $intent" }
        db.queueQueries.upsertIntent(
            episode_id = episodeId,
            intent = intent,
            position = position,
            pinned = pinned,
            origin = origin,
            updated_at = updatedAt ?: now(),
            updated_by = updatedBy ?: lanDeviceId(),
        )
    }

    fun dropIntent(episodeId: Long) {
        db.queueQueries.dropIntent(episodeId)
    }

    /**
     * Drop intents that can no longer change anything: the episode is played
     * and the decision is old enough that no peer is still catching up.
     */
    fun pruneIntents(maxAgeSecs: Long = 90L * 86_400): Long {
        val cutoff = now() - maxAgeSecs
        val doomed = db.queueQueries.countPrunableIntents(cutoff).executeAsOne()
        db.queueQueries.pruneIntents(cutoff)
        return doomed
    }

    // ---------------------------------------------------------------- settings

    fun podcastSettings(podcastId: Long): Podcast_settings? =
        db.podcastsQueries.settingsForPodcast(podcastId).executeAsOneOrNull()

    /**
     * Change one per-podcast setting, leaving the rest alone.
     *
     * Read-modify-write because the only upsert the schema offers writes every
     * column — which is what a snapshot merge wants (it carries the peer's
     * whole record) and exactly what a local edit must not do, or setting the
     * speed would silently blank the custom title. The read and the write share
     * a transaction so two edits in flight cannot lose one another.
     *
     * Stamps `updated_at`/`updated_by` with this device, which is what makes
     * the change win the next merge against an older copy.
     */
    fun updatePodcastSettings(podcastId: Long, mutate: (PodcastSettings) -> PodcastSettings) {
        db.transaction {
            val current = PodcastSettings.from(podcastSettings(podcastId))
            val next = mutate(current)
            db.podcastsQueries.upsertAllSettings(
                podcast_id = podcastId,
                custom_title = next.customTitle,
                playback_speed = next.playbackSpeed,
                skip_intro_secs = next.skipIntroSecs,
                skip_outro_secs = next.skipOutroSecs,
                auto_add_to_queue = next.autoAddToQueue,
                auto_queue_position = next.autoQueuePosition,
                updated_at = now(),
                updated_by = lanDeviceId(),
            )
        }
    }

    fun effectiveAutoAdd(podcastId: Long): Boolean =
        podcastSettings(podcastId)?.auto_add_to_queue?.let { it != 0L }
            ?: stateBool(AUTO_ADD_TO_QUEUE, true)

    /** 'front' or 'back' — where this podcast's new episodes enter the queue. */
    fun effectiveQueuePosition(podcastId: Long): String {
        val value = podcastSettings(podcastId)?.auto_queue_position
            ?.takeIf { it == "front" || it == "back" }
            ?: stateString(AUTO_QUEUE_POSITION, "back")
        return if (value == "front") "front" else "back"
    }

    fun effectiveSpeed(podcastId: Long): Double =
        podcastSettings(podcastId)?.playback_speed?.takeIf { it > 0.0 }
            ?: stateDouble(GLOBAL_SPEED, 1.0)

    fun displayTitle(podcast: Podcasts): String =
        podcastSettings(podcast.id)?.custom_title?.takeIf { it.isNotBlank() }
            ?: podcast.title?.takeIf { it.isNotBlank() }
            ?: podcast.feed_url

    // ---------------------------------------------------------------- queue reads

    fun queueItems(): List<Queue> = db.queueQueries.queueItems().executeAsList()

    fun queueEpisodes(): List<Episodes> = db.queueQueries.queueEpisodes().executeAsList()

    fun queueItem(episodeId: Long): Queue? =
        db.queueQueries.queueItemFor(episodeId).executeAsOneOrNull()

    fun isExcluded(episodeId: Long): Boolean =
        db.queueQueries.isExcluded(episodeId).executeAsOneOrNull() != null

    // ---------------------------------------------------------------- outbox

    /**
     * Queue a gpodder episode action for upload.
     *
     * This is the only channel that reaches the phone's other podcast apps —
     * peer sync carries the queue, but played/new state travels here, and
     * having both write it would just be a race.
     */
    fun enqueueAction(
        podcastUrl: String,
        episodeUrl: String,
        action: String,
        timestamp: String = iso8601Utc(now()),
        started: Long? = null,
        position: Long? = null,
        total: Long? = null,
    ) {
        db.syncQueries.enqueueAction(
            podcast_url = podcastUrl,
            episode_url = episodeUrl,
            action = action,
            timestamp = timestamp,
            started = started,
            position = position,
            total = total,
        )
    }

    fun outboxActions(): List<Action_outbox> = db.syncQueries.outboxActions().executeAsList()

    /**
     * Record an action we could not attach to a local episode.
     *
     * Kept rather than dropped because an unmatched action is the visible
     * symptom of the URL ladder failing on a real feed — it is the only way to
     * find out which CDN needs a new tracker pattern.
     */
    fun logUnmatched(
        podcastUrl: String,
        episodeUrl: String,
        action: String,
        timestamp: String,
        payload: String,
    ) {
        db.syncQueries.logUnmatched(podcastUrl, episodeUrl, action, timestamp, payload, now())
    }

    fun unmatchedCount(): Long = db.syncQueries.unmatchedCount().executeAsOne()

    fun clearOutbox(upToId: Long) {
        db.syncQueries.clearOutbox(upToId)
    }

    // ---------------------------------------------------------------- peers

    fun knownPeers(): List<Lan_peers> = db.lanPeersQueries.knownPeers().executeAsList()

    fun rememberPeer(deviceId: String, caption: String, address: String, port: Int) {
        db.lanPeersQueries.rememberPeer(deviceId, caption, address, port.toLong(), now())
    }

    fun forgetPeer(deviceId: String) {
        db.lanPeersQueries.forgetPeer(deviceId)
    }

    fun manualPeers(): List<Pair<String, Int>> =
        db.lanPeersQueries.manualPeers().executeAsList().map { it.address to it.port.toInt() }

    fun addManualPeer(address: String, port: Int) {
        db.lanPeersQueries.addManualPeer(address, port.toLong())
    }

    fun removeManualPeer(address: String, port: Int) {
        db.lanPeersQueries.removeManualPeer(address, port.toLong())
    }

    data class PodcastDisplay(val id: Long, val title: String, val imageUrl: String?)

    companion object {
        // The subset of the desktop's repo.DEFAULTS that the shared core reads.
        // UI-only defaults (theme, accent, home sections) belong to the app.
        const val GLOBAL_SPEED = "global_speed"
        const val SKIP_FWD_SECS = "skip_fwd_secs"
        const val SKIP_BACK_SECS = "skip_back_secs"
        const val DOWNLOAD_AHEAD_N = "download_ahead_n"
        const val DOWNLOAD_AHEAD = "download_ahead"
        const val AUTO_ADD_TO_QUEUE = "auto_add_to_queue"
        const val AUTO_QUEUE_POSITION = "auto_queue_position"
        const val SYNC_INTERVAL_MINS = "sync_interval_mins"
        const val LAN_DEVICE_ID = "lan_device_id"
        const val LAN_PORT = "lan_port"
        const val LAN_SYNC_ENABLED = "lan_sync_enabled"
        const val DEFAULT_LAN_PORT = 47722L
    }
}
