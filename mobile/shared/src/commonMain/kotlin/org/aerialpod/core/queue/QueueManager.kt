package org.aerialpod.core.queue

import org.aerialpod.core.db.Episodes
import org.aerialpod.core.db.Repo
import org.aerialpod.core.epochSeconds

/**
 * The derived-queue-with-manual-override algorithm — the port of
 * `core/queue.py`.
 *
 * Rules:
 *  - [reconcile] only removes and inserts; it NEVER re-sorts surviving rows.
 *  - The currently-playing episode is never removed.
 *  - Pinned rows are kept unless finished or unsubscribed.
 *  - Removed-by-user episodes live in `queue_exclusions` and are never auto
 *    re-added.
 *  - In-progress episodes insert after the playing item and the leading pinned
 *    block; fresh episodes append at the end in pub_date order — unless their
 *    podcast is set to 'front', which puts them at the top instead, newest
 *    first.
 *
 * Every user-facing op records intent as well as changing the queue. That is
 * what reaches the other devices: the queue itself is derived truth that each
 * device rebuilds for itself, while the intent is the user's decision, and
 * gpodder.net has nowhere to put it. An op that changes the queue and forgets
 * the intent looks correct locally and silently never leaves the phone.
 */
const val GAP: Long = 1024
const val FINISHED_SLACK_SECS: Long = 30

fun isFinished(episode: Episodes): Boolean {
    if (episode.state == "played") return true
    // Guard: AntennaPod sometimes reports total as 0 / -1.
    if (episode.total_secs > 0) {
        return episode.position_secs >= episode.total_secs - FINISHED_SLACK_SECS
    }
    return false
}

class QueueManager(
    private val repo: Repo,
    private val now: () -> Long = ::epochSeconds,
    /** The user changed the queue — peers want this now (2 s debounce). */
    private val onIntentChanged: () -> Unit = {},
    /** An action was enqueued that the phone's other apps should see soon. */
    private val onSyncNeeded: () -> Unit = {},
    private val onQueueChanged: () -> Unit = {},
) {
    private val db get() = repo.db

    /** Set by whoever owns playback. The playing row is never reconciled away. */
    var playingEpisodeId: Long? = null

    // ---------------------------------------------------------------- reads

    fun episodes(): List<Episodes> = repo.queueEpisodes()

    fun head(): Episodes? = episodes().firstOrNull()

    fun nextAfter(episodeId: Long): Episodes? {
        val all = episodes()
        val index = all.indexOfFirst { it.id == episodeId }
        if (index < 0) return all.firstOrNull()
        return all.getOrNull(index + 1)
    }

    fun contains(episodeId: Long): Boolean = repo.queueItem(episodeId) != null

    // ---------------------------------------------------------------- user ops

    /** Manual add: pinned, exclusion cleared. */
    fun add(episodeId: Long, toFront: Boolean = false) {
        var inserted = false
        db.transaction {
            db.queueQueries.unexcludeEpisode(episodeId)
            if (repo.queueItem(episodeId) != null) return@transaction
            val position = if (toFront) {
                (db.queueQueries.minQueuePosition().executeAsOne().MIN ?: GAP) - GAP
            } else {
                (db.queueQueries.maxQueuePosition().executeAsOne().MAX ?: 0L) + GAP
            }
            db.queueQueries.insertQueueItem(episodeId, position, "manual", 1, now())
            repo.recordIntent(episodeId, "queued", position = position, pinned = 1, origin = "manual")
            inserted = true
        }
        // Already queued: the exclusion is cleared either way, but nothing
        // changed and nothing is announced — the desktop returns out of the
        // whole method here, and a spurious queueChanged would repaint the UI
        // for a no-op.
        if (!inserted) return
        onQueueChanged()
        onIntentChanged()
    }

    /** User removal: never auto re-add (exclusion). */
    fun remove(episodeId: Long, exclude: Boolean = true) {
        db.transaction {
            db.queueQueries.deleteQueueItem(episodeId)
            if (exclude) {
                db.queueQueries.excludeEpisode(episodeId, now())
                repo.recordIntent(episodeId, "excluded")
            }
        }
        onQueueChanged()
        if (exclude) onIntentChanged()
    }

    fun toggle(episodeId: Long) {
        if (contains(episodeId)) remove(episodeId) else add(episodeId)
    }

    /** Drag-drop reorder — the moved row becomes pinned/manual. */
    fun move(episodeId: Long, newIndex: Int) {
        val items = repo.queueItems()
        val ids = items.map { it.episode_id }.filter { it != episodeId }.toMutableList()
        val index = newIndex.coerceIn(0, ids.size)
        ids.add(index, episodeId)
        val byId = items.associateBy { it.episode_id }

        db.transaction {
            for ((i, id) in ids.withIndex()) {
                db.queueQueries.setQueuePosition((i + 1) * GAP, id)
            }
            db.queueQueries.pinQueueItem(episodeId)
            // An ordering is a statement about the whole list, not about the row
            // that moved — so every row's intent carries the new order. A peer
            // merging this adopts the order wholesale, while an episode it
            // queued independently (newer intent of its own) still survives.
            for ((i, id) in ids.withIndex()) {
                val existing = byId[id]
                val moved = id == episodeId
                repo.recordIntent(
                    episodeId = id,
                    intent = "queued",
                    position = (i + 1) * GAP,
                    pinned = if (moved) 1 else existing?.pinned ?: 0,
                    origin = if (moved) "manual" else existing?.origin ?: "auto",
                )
            }
        }
        onQueueChanged()
        onIntentChanged()
    }

    /**
     * Hold this row where it is.
     *
     * Recorded as intent like any other user decision — setting the flag with
     * raw SQL instead is what once made a pin stay on the machine it was made on.
     */
    fun pin(episodeId: Long) {
        var pinned = false
        db.transaction {
            db.queueQueries.pinQueueItem(episodeId)
            val row = repo.queueItem(episodeId) ?: return@transaction
            repo.recordIntent(episodeId, "queued", position = row.position, pinned = 1, origin = "manual")
            pinned = true
        }
        if (pinned) {
            onQueueChanged()
            onIntentChanged()
        }
    }

    fun releaseToAuto(episodeId: Long) {
        db.transaction {
            db.queueQueries.releaseQueueItem(episodeId)
            val row = repo.queueItem(episodeId)
            repo.recordIntent(
                episodeId, "queued",
                position = row?.position ?: 0, pinned = 0, origin = "auto",
            )
        }
        onQueueChanged()
        onIntentChanged()
    }

    /**
     * Episode finished (played out, or the user marked it): mark played, drop
     * from the queue, tell gpodder so the other apps dequeue it too, and return
     * what is next.
     */
    fun markPlayedAndAdvance(episodeId: Long): Episodes? {
        val next = nextAfter(episodeId)
        val episode = repo.episodeById(episodeId)

        db.transaction {
            db.episodesQueries.setEpisodeState("played", episodeId)
            // Bump the replication stamp with it. `finished` rides on the
            // position record, and a record is only applied when it is newer
            // than what the receiver holds — so marking played without moving
            // the clock produces news that can never be delivered.
            // Always, even with no position: "played" is itself the news, and
            // it can only travel on a record newer than the receiver's.
            db.episodesQueries.setEpisodePositionOnly(episode?.position_secs ?: 0, now(), episodeId)
            db.queueQueries.deleteQueueItem(episodeId)
            // Finishing settles any standing intent: peers derive the removal
            // themselves from the played state / position.
            repo.dropIntent(episodeId)
        }

        // If playback already reported completion, the episode was 'played'
        // before this call — don't enqueue a duplicate action.
        if (episode != null && episode.state != "played") {
            val podcast = repo.podcastById(episode.podcast_id)
            val total = episode.total_secs.takeIf { it > 0 }
                ?: episode.duration_secs?.takeIf { it > 0 }
                ?: 0L
            if (podcast != null && total > 0) {
                repo.enqueueAction(
                    podcastUrl = podcast.feed_url,
                    episodeUrl = episode.media_url,
                    action = "play",
                    started = total,
                    position = total,
                    total = total,
                )
                onSyncNeeded()
            }
        }
        onQueueChanged()
        return next
    }

    /**
     * Reset progress and played state; tells gpodder via a 'new' action so the
     * other apps reset too. Reconcile decides whether it re-enters the queue.
     */
    fun markUnplayed(episodeId: Long) {
        val episode = repo.episodeById(episodeId) ?: return
        db.episodesQueries.markUnplayed(now(), episodeId)

        db.transaction {
            db.queueQueries.unexcludeEpisode(episodeId)
            // Recorded as intent rather than left as a bare deletion: a peer
            // holding an older 'excluded' intent must lose this merge, or the
            // episode the user just restored would be thrown out again.
            val last = db.queueQueries.maxQueuePosition().executeAsOne().MAX ?: 0L
            repo.recordIntent(episodeId, "queued", position = last + GAP, pinned = 0, origin = "auto")
        }
        onIntentChanged()

        val podcast = repo.podcastById(episode.podcast_id)
        if (podcast != null) {
            repo.enqueueAction(podcast.feed_url, episode.media_url, "new")
            onSyncNeeded()
        }
        reconcile()
        onQueueChanged()
    }

    // ---------------------------------------------------------------- reconcile

    private fun qualifies(episode: Episodes): Boolean {
        val podcast = repo.podcastById(episode.podcast_id) ?: return false
        if (podcast.subscribed == 0L) return false
        if (isFinished(episode)) return false
        if (episode.position_secs > 0) return true // in progress somewhere
        // 'inbox' = a gpodder download action, i.e. another device queued it —
        // the closest thing the protocol has to a queue-add signal.
        if (episode.state == "inbox") return true
        return episode.state == "new" && repo.effectiveAutoAdd(episode.podcast_id)
    }

    private fun candidateEpisodes(): List<Episodes> =
        db.episodesQueries.queueCandidates().executeAsList().filter(::qualifies)

    /** Rebuild queue membership from episode state. Remove + insert only. */
    fun reconcile() {
        var changed = false
        db.transaction {
            val items = repo.queueItems()

            // --- removal pass (order preserved) ---
            val survivors = items.filter { item ->
                val episode = repo.episodeById(item.episode_id)
                when {
                    episode == null -> {
                        db.queueQueries.deleteQueueItem(item.episode_id); changed = true; false
                    }
                    item.episode_id == playingEpisodeId -> true // never remove the playing episode
                    item.pinned != 0L -> {
                        val podcast = repo.podcastById(episode.podcast_id)
                        if (isFinished(episode) || podcast == null || podcast.subscribed == 0L) {
                            db.queueQueries.deleteQueueItem(item.episode_id); changed = true; false
                        } else {
                            true
                        }
                    }
                    !qualifies(episode) -> {
                        db.queueQueries.deleteQueueItem(item.episode_id); changed = true; false
                    }
                    else -> true
                }
            }

            // --- insertion / float pass ---
            // head block: the leading contiguous run of (playing | pinned) rows.
            // These never move.
            val head = mutableListOf<Long>()
            val rest = mutableListOf<Long>()
            for (item in survivors) {
                if (rest.isEmpty() && (item.episode_id == playingEpisodeId || item.pinned != 0L)) {
                    head += item.episode_id
                } else {
                    rest += item.episode_id
                }
            }

            val inQueue = survivors.map { it.episode_id }.toSet()
            val candidates = candidateEpisodes()
                .filter { it.id !in inQueue && !repo.isExcluded(it.id) }

            // Floaters: in-progress episodes surface right after the head block,
            // most recently touched (on any device) first. Unpinned in-queue rows
            // float too — "started it on the phone" moves it up.
            //
            // Insertion order matters: the sort below is stable, so rows already
            // in the queue keep precedence over new candidates on a timestamp tie,
            // exactly as the desktop's dict ordering does.
            val floating = LinkedHashMap<Long, Long>()
            val survivorById = survivors.associateBy { it.episode_id }
            for (id in rest) {
                // A pinned row is the user saying "leave it where I put it",
                // which is exactly what move() records. Floating it anyway
                // undoes a manual reorder on the very next reconcile — and
                // since reordering an episode you are partway through is the
                // common case, that made moving one down impossible.
                if (survivorById[id]?.pinned != 0L) continue
                val episode = repo.episodeById(id)
                if (episode != null && episode.position_secs > 0) {
                    floating[id] = episode.position_updated_at
                }
            }
            for (episode in candidates) {
                if (episode.position_secs > 0) floating[episode.id] = episode.position_updated_at
            }
            val floaters = floating.keys.sortedByDescending { floating.getValue(it) }

            // Untouched episodes normally land at the end, oldest first. A podcast
            // set to 'front' — a daily show, typically — instead goes in just under
            // the head block, newest first, so this morning's episode takes the top
            // slot and yesterday's sits below it. Anything playing or explicitly
            // pinned still outranks it.
            val untouched = candidates.filter { it.position_secs == 0L }
            val front = untouched
                .filter { repo.effectiveQueuePosition(it.podcast_id) == "front" }
                .sortedByDescending { it.pub_date ?: 0L }
            val back = untouched
                .filter { repo.effectiveQueuePosition(it.podcast_id) != "front" }
                .sortedBy { it.pub_date ?: 0L }

            val order = head +
                front.map { it.id } +
                floaters +
                rest.filter { it !in floating } +
                back.map { it.id }

            val oldOrder = survivors.map { it.episode_id }
            if (order != oldOrder) {
                writeOrder(order, candidates.map { it.id }.toSet())
                changed = true
            }
        }
        if (changed) onQueueChanged()
    }

    /** Renumber everything to the given order, inserting new rows as auto. */
    private fun writeOrder(order: List<Long>, newIds: Set<Long>) {
        val stamp = now()
        for ((i, episodeId) in order.withIndex()) {
            val position = (i + 1) * GAP
            if (episodeId in newIds) {
                db.queueQueries.insertQueueItemIfAbsent(episodeId, position, "auto", 0, stamp)
            }
            db.queueQueries.setQueuePosition(position, episodeId)
        }
    }
}
