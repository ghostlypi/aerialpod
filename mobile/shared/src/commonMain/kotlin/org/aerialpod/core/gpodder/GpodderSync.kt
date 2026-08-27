package org.aerialpod.core.gpodder

import kotlinx.serialization.json.Json
import org.aerialpod.core.db.Repo
import org.aerialpod.core.epochSeconds
import org.aerialpod.core.parseIso8601Utc
import org.aerialpod.core.sync.Matcher

/**
 * The gpodder.net sync cycle — the port of `gpodder/sync.py`.
 *
 * Flow per sync: login → push outbox actions → pull episode actions
 * (aggregated) → push pending subscription changes → pull subscription changes.
 *
 * Subscriptions on gpodder.net are per-device; episode actions are per-user. On
 * first sync we pull the user-level merged subscription list (so what the other
 * devices already subscribe to appears here), upload our list to our own device
 * id, and best-effort group all devices with `/api/2/sync-devices` so the server
 * propagates subscription changes between them.
 *
 * **The phone registers its own device id.** Sharing the desktop's would look
 * tempting — one less device in the list — but the subscription endpoint returns
 * the diff *for a device*: a feed added here would be recorded against that
 * shared id, and the desktop asking the same id for changes would never be told,
 * because the server already believes that device has it. Two independent
 * clients on one id lose each other's subscription changes. Episode actions are
 * account-wide and unaffected either way.
 */
class GpodderSync(
    private val repo: Repo,
    private val matcher: Matcher,
    private val deviceCaption: String,
    private val clientProvider: suspend () -> GpodderClient?,
    private val now: () -> Long = ::epochSeconds,
    private val dryRun: Boolean = false,
) {
    private val db get() = repo.db
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    data class Result(
        val pushed: Int = 0,
        val applied: Int = 0,
        val unmatched: Int = 0,
        /** Podcast ids that are new here and need a feed fetch. */
        val subscriptionsAdded: List<Long> = emptyList(),
        val notes: List<String> = emptyList(),
    ) {
        val summary: String
            get() = (listOf("$pushed action(s) sent", "$applied applied") +
                (if (unmatched > 0) listOf("$unmatched unmatched") else emptyList()) +
                notes).joinToString(", ")
    }

    /**
     * Our gpodder device id — stable, and distinct from the LAN device id.
     *
     * gpodder.net accepts `[A-Za-z0-9._-]` here, and it is user-visible in the
     * account's device list, so it is built from the device's name rather than
     * from a random identifier.
     */
    fun deviceId(): String {
        repo.rawState(DEVICE_ID)?.let { raw ->
            val stored = runCatching { json.parseToJsonElement(raw) }.getOrNull()
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            if (!stored.isNullOrBlank()) return stored
        }
        val slug = deviceCaption.lowercase()
            .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .take(24)
            .ifBlank { "phone" }
        val generated = "aerialpod-$slug"
        repo.setState(DEVICE_ID, generated)
        return generated
    }

    /** Where the episode-action pull will resume from. Exposed for tests. */
    fun actionsCursor(): Long = repo.stateLong(ACTIONS_SINCE, 0)

    suspend fun syncNow(): Result {
        val client = clientProvider()
            ?: throw GpodderError("gpodder.net account not configured")
        client.login()

        val deviceId = deviceId()
        if (!repo.stateBool(DEVICE_REGISTERED, false)) {
            client.registerDevice(deviceId, deviceCaption)
            repo.setState(DEVICE_REGISTERED, true)
        }

        val pushed = pushActions(client)

        // Subscriptions first, so the action pull below knows whether the
        // library it is about to match against is complete.
        val subscriptions = syncSubscriptions(client, deviceId)

        // A subscription that arrived in this cycle has no episodes yet — its
        // feed is fetched afterwards, by AerialPodCore. Every action for it
        // would be unmatchable now, so the cursor is held back and the next
        // sync re-pulls the same window against a library that exists.
        //
        // Without this, signing in on a new device throws away the account's
        // entire listening history on the one sync meant to bring it over, and
        // never asks for it again. It is the ordinary case on a phone.
        val libraryStillArriving = subscriptions.first.isNotEmpty()
        val (applied, unmatched) = pullActions(client, holdCursor = libraryStillArriving)

        return Result(
            pushed = pushed,
            applied = applied,
            unmatched = unmatched,
            subscriptionsAdded = subscriptions.first,
            notes = subscriptions.second,
        )
    }

    // ---------------------------------------------------------------- actions

    private suspend fun pushActions(client: GpodderClient): Int {
        val rows = repo.outboxActions()
        if (rows.isEmpty()) return 0
        val payload = rows.map { row ->
            EpisodeAction(
                podcast = row.podcast_url,
                episode = row.episode_url,
                action = row.action,
                timestamp = row.timestamp,
                // Only a play action carries progress. `total` is dropped when
                // zero — AntennaPod sends bogus zeroes and the server would
                // store one over a good duration.
                started = if (row.action == "play") row.started else null,
                position = if (row.action == "play") row.position else null,
                total = if (row.action == "play") row.total?.takeIf { it != 0L } else null,
            )
        }
        client.uploadEpisodeActions(payload)
        if (!dryRun) repo.clearOutbox(rows.last().id)
        return payload.size
    }

    private suspend fun pullActions(
        client: GpodderClient,
        holdCursor: Boolean = false,
    ): Pair<Int, Int> {
        val since = repo.stateLong(ACTIONS_SINCE, 0)
        val data = client.getEpisodeActions(since, aggregated = true)
        var applied = 0
        var unmatched = 0
        for (action in data.actions) {
            if (applyAction(action)) applied++ else unmatched++
        }
        // Advance only on success — we reached here without throwing, so every
        // action in this batch has been considered. Advancing earlier would
        // silently skip the rest of a batch that failed halfway. And not at all
        // when the library is still arriving: these actions have to be asked
        // for again once there are episodes to match them to.
        if (!holdCursor) {
            repo.setState(ACTIONS_SINCE, if (data.timestamp > 0) data.timestamp else since)
        }
        return applied to unmatched
    }

    /** True if the action was handled (or is irrelevant); false if it was logged unmatched. */
    fun applyAction(action: EpisodeAction): Boolean {
        val kind = action.action.lowercase()
        val podcast = matcher.matchPodcast(action.podcast)
        val episode = podcast?.let { matcher.matchEpisode(it, action.episode) }

        if (episode == null) {
            // Only log play/delete for podcasts we actually carry — actions for
            // feeds we do not subscribe to are not interesting.
            if (podcast != null && (kind == "play" || kind == "delete")) {
                repo.logUnmatched(
                    action.podcast, action.episode, kind, action.timestamp,
                    json.encodeToString(EpisodeAction.serializer(), action),
                )
                return false
            }
            return true
        }

        val epoch = parseIso8601Utc(action.timestamp)
        when (kind) {
            "play" -> {
                if (epoch <= episode.position_updated_at) return true // local is newer
                val position = action.position ?: 0
                val total = action.total ?: 0
                if (position > 0 && total > 0) {
                    db.episodesQueries.setEpisodePosition(position, total, epoch, episode.id)
                } else if (position > 0) {
                    db.episodesQueries.setEpisodePositionOnly(position, epoch, episode.id)
                } else if (total > 0) {
                    db.episodesQueries.setEpisodeTotal(total, episode.id)
                }
                // Guard: AntennaPod sometimes reports total=0, and 'near the end
                // of nothing' must not read as finished.
                if (total > 0 && position >= total - 30) {
                    db.episodesQueries.setEpisodeState("played", episode.id)
                }
            }
            "delete" -> db.episodesQueries.setEpisodeState("played", episode.id)
            "download" -> {
                // Another device downloaded it, so it is (almost certainly) in
                // that device's queue. Promote to 'inbox' so reconcile picks it
                // up — including archived back-catalog episodes.
                if (episode.state == "new" || episode.state == "archived") {
                    db.episodesQueries.setEpisodeState("inbox", episode.id)
                }
            }
            "new" -> db.episodesQueries.resetEpisodeToNew(epoch, episode.id)
        }
        return true
    }

    // ---------------------------------------------------------------- subscriptions

    private suspend fun syncSubscriptions(
        client: GpodderClient,
        deviceId: String,
    ): Pair<List<Long>, List<String>> {
        val since = repo.stateLong(SUBS_SINCE, 0)
        val added = mutableListOf<Long>()
        val notes = mutableListOf<String>()

        val pending = db.podcastsQueries.podcastsPendingSync().executeAsList()
        val add = pending.filter { it.sync_state == "add_pending" }.map { it.feed_url }
        val remove = pending.filter { it.sync_state == "remove_pending" }.map { it.feed_url }
        if (add.isNotEmpty() || remove.isNotEmpty()) {
            val result = client.uploadSubscriptionChanges(deviceId, add, remove)
            for (pair in result.updateUrls) {
                val old = pair.getOrNull(0)
                val new = pair.getOrNull(1)
                if (!old.isNullOrBlank() && !new.isNullOrBlank()) {
                    db.podcastsQueries.rewriteFeedUrl(new, old)
                }
            }
            if (!dryRun) db.podcastsQueries.markSubscriptionsClean()
            notes += "${add.size}+/${remove.size}− subs pushed"
        }

        if (since == 0L) {
            // First sync: pull the account-level merged list so what the other
            // devices subscribe to appears here, then group devices server-side.
            val urls = runCatching { client.getAllSubscriptions() }.getOrElse { emptyList() }
            var fresh = 0
            for (url in urls) {
                if (matcher.matchPodcast(url) == null) {
                    added += repo.upsertPodcast(url, syncState = "clean")
                    fresh++
                }
            }
            if (fresh > 0) notes += "$fresh podcast(s) from server"
            linkDevices(client, deviceId)
            repo.setState(SUBS_SINCE, now())
        } else {
            val data = client.getSubscriptionChanges(deviceId, since)
            for (url in data.add) {
                val existing = matcher.matchPodcast(url)
                if (existing == null) {
                    added += repo.upsertPodcast(url, syncState = "clean")
                } else if (existing.subscribed == 0L) {
                    repo.upsertPodcast(existing.feed_url, syncState = "clean", subscribed = 1)
                }
            }
            for (url in data.remove) {
                val existing = matcher.matchPodcast(url)
                if (existing != null && existing.subscribed != 0L) {
                    db.podcastsQueries.markUnsubscribedFromServer(existing.id)
                }
            }
            repo.setState(SUBS_SINCE, if (data.timestamp > 0) data.timestamp else since)
            val changes = data.add.size + data.remove.size
            if (changes > 0) notes += "$changes sub change(s) pulled"
        }
        return added to notes
    }

    /**
     * Best-effort: group the account's devices so gpodder.net propagates
     * subscription changes between them. A server that does not implement it
     * only means subscriptions have to be added on each device.
     */
    private suspend fun linkDevices(client: GpodderClient, deviceId: String) {
        runCatching {
            val others = client.listDeviceIds().filter { it != deviceId }
            if (others.isNotEmpty()) client.linkDevices(listOf(deviceId) + others)
        }
    }

    companion object {
        const val DEVICE_ID = "device_id"
        const val DEVICE_REGISTERED = "device_registered"
        const val ACTIONS_SINCE = "actions_since"
        const val SUBS_SINCE = "subs_since"
    }
}
