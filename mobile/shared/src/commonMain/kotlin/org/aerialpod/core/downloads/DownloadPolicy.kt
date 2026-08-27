package org.aerialpod.core.downloads

import org.aerialpod.core.db.Episodes
import org.aerialpod.core.db.Repo

/**
 * Which episodes should be on disk, and which should not.
 *
 * The port of `apply_policy` in `core/downloads.py`: keep the first N queue
 * items downloaded, evict everything else the policy put there. Everything
 * outside that window streams.
 *
 * Pure, and separate from whatever does the transferring, because the rules are
 * shared with iOS and the file handling is not — and because the interesting
 * failure here is a policy that evicts something it should have kept.
 */
class DownloadPolicy(private val repo: Repo) {

    data class Plan(
        /** Wanted, not yet on disk, not already in flight. In queue order. */
        val fetch: List<Episodes>,
        /** On disk, no longer wanted, and not pinned by the user. */
        val evict: List<Episodes>,
    ) {
        val isEmpty: Boolean get() = fetch.isEmpty() && evict.isEmpty()
    }

    /**
     * @param active episodes a transfer is currently carrying. They are never
     *   evicted mid-flight — the desktop lets the transfer finish and lets the
     *   next pass evict it — and never fetched twice.
     */
    fun plan(active: Set<Long> = emptySet()): Plan {
        val queue = repo.queueEpisodes()
        val aheadN = resolve(repo.downloadAhead(), queue.size)
        val wanted = if (aheadN > 0) queue.take(aheadN) else emptyList()
        val wantedIds = wanted.map { it.id }.toSet()

        val evict = repo.downloadedEpisodes().filter { episode ->
            episode.id !in wantedIds &&
                episode.keep_download == 0L &&
                episode.id !in active
        }

        // A `downloading` row with nothing carrying it is a transfer that died
        // — the process was killed, or the worker crashed. Inside the wanted
        // window it is stranded otherwise: not `none`, so never fetched; still
        // wanted, so never evicted. Picking it up again is safe because the
        // transfer resumes from the `.part` file.
        val fetch = wanted.filter {
            it.id !in active &&
                (it.download_state == "none" || it.download_state == "downloading")
        }

        return Plan(fetch = fetch, evict = evict)
    }

    companion object {
        /** `"0"`, `"1"`, `"5"`, `"1/4"`, `"1/3"`, `"1/2"`, `"all"`. */
        const val ALL = "all"
        val CHOICES = listOf("0", "1", "5", "1/4", "1/3", "1/2", ALL)

        /**
         * How many of the queue's episodes to keep on disk.
         *
         * A fraction rounds **up**, so any non-empty queue with a fraction set
         * downloads at least one — "a quarter of my queue" meaning nothing at
         * all for a three-item queue would be a surprising reading.
         *
         * Anything unrecognised falls back to one. The value comes from
         * `app_state`, which is shared with the desktop and editable by hand.
         */
        fun resolve(setting: String, queueSize: Int): Int {
            if (queueSize <= 0) return 0
            val text = setting.trim().lowercase()
            if (text == ALL) return queueSize
            val slash = text.indexOf('/')
            if (slash > 0) {
                val numerator = text.substring(0, slash).toIntOrNull() ?: return 1
                val denominator = text.substring(slash + 1).toIntOrNull() ?: return 1
                if (numerator <= 0 || denominator <= 0) return 0
                // Ceiling division, without floating point: a queue of 11 at
                // 1/3 is 4, not 3.666 rounded by whatever the platform does.
                return ((queueSize.toLong() * numerator + denominator - 1) / denominator)
                    .coerceAtMost(queueSize.toLong())
                    .toInt()
            }
            return text.toIntOrNull()?.coerceAtLeast(0) ?: 1
        }

        /** How the choice reads in the UI. */
        fun label(setting: String): String = when (setting) {
            "0" -> "Off"
            ALL -> "Whole queue"
            else -> setting
        }
    }
}
