package org.aerialpod.android.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.aerialpod.android.platform.PlaybackSignals
import org.aerialpod.core.AerialPodCore
import org.aerialpod.core.ads.AdAnchors
import org.aerialpod.core.ads.AdSkip
import org.aerialpod.core.ads.AdSpan
import org.aerialpod.core.ads.EpisodeAds
import org.aerialpod.core.ads.FileByteSource
import org.aerialpod.core.ads.adsFor
import org.aerialpod.core.db.Repo

/**
 * Everything that has to happen around the player.
 *
 * The port of the desktop's `core/player.py`, minus the parts Media3 already
 * does. What is left is the behaviour the two apps must share: per-podcast
 * speed, skip intro and outro, ad skipping, throttled position writes, and
 * what "finished" means.
 *
 * It lives beside the player in the service rather than in the UI, because
 * every one of these has to keep working with the app backgrounded and the
 * screen off.
 */
class PlaybackCoordinator(
    private val player: Player,
    private val core: AerialPodCore,
    private val signals: PlaybackSignals,
    private val adSkips: AdSkips,
    private val scope: CoroutineScope,
) {
    companion object {
        /** `POSITION_WRITE_MS` from `core/player.py`. */
        const val POSITION_WRITE_MS = 5_000L

        /**
         * How often to look for an ad while playing. The desktop hears every
         * position change; this is the nearest equivalent, and only runs while
         * the loaded copy has spans to skip.
         */
        const val AD_CHECK_MS = 250L
    }

    /**
     * ExoPlayer is thread-confined to the thread it was built on — the
     * service's main thread. Every read of `player.currentPosition` and every
     * command has to happen here or it throws; only the database work is moved
     * off it.
     */
    private val main = mainChildScope(scope)

    private var ticker: Job? = null

    /**
     * Set once per episode so a skip-outro fires at most once.
     *
     * Without it the outro check would re-fire on every tick past the
     * threshold, marking the episode played repeatedly and advancing the queue
     * more than once.
     */
    private var outroFired = false

    // Ad spans for what is loaded. The rules are AdSkip's, shared with the desktop.
    private var adsEnabled = true
    private var ads: EpisodeAds? = null
    private var adsMapped: List<AdSpan>? = null     // moved onto this copy (AdAnchors)
    private val adsAllowed = mutableSetOf<Int>()   // undone: let them play
    private var adsPrevMs: Long? = null
    private var adsLast: Int? = null                // the span most recently skipped
    private var adTicker: Job? = null

    private val listener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicking() else stopTicking()
            if (isPlaying) startAdTicking() else stopAdTicking()
            // Both edges are transport events. The play edge also dials if no
            // link is up — see PlaybackSignals.
            signals.setPlaying(isPlaying, currentEpisodeId())
            if (!isPlaying) report(final = true)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A seek is a transport event on the desktop too: the position it
            // jumped away from is what the other devices need.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) report(final = true)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            outroFired = false
            ads = null
            adsMapped = null
            adsAllowed.clear()
            adsPrevMs = null
            adsLast = null
            main.launch { applyPerPodcastSettings() }
            main.launch { loadAds() }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) finishEpisode()
            // Whether spans apply depends on the duration, known from here on.
            if (state == Player.STATE_READY) {
                publishAds()
                if (player.isPlaying) startAdTicking()
                if (adsMapped == null) main.launch { mapAds() }
            }
        }
    }

    fun attach() {
        player.addListener(listener)
        main.launch { adSkips.undoRequests.collect { undoAdSkip() } }
        // A desktop finishing its analysis mid-episode should still help.
        main.launch { core.lan.merged.collect { if (it.ads > 0) loadAds() } }
        main.launch {
            core.library.stateBool(Repo.ADS_SKIP_ENABLED, true).collect {
                adsEnabled = it
                loadAds()
            }
        }
    }

    fun detach() {
        stopTicking()
        stopAdTicking()
        player.removeListener(listener)
        main.cancel()
    }

    // ---------------------------------------------------------------- ticking

    private fun startTicking() {
        if (ticker?.isActive == true) return
        ticker = main.launch {
            while (true) {
                delay(POSITION_WRITE_MS)
                report(final = false)
                checkSkipOutro()
            }
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    // ---------------------------------------------------------------- writes

    /**
     * Persist where playback got to.
     *
     * `final` is the same flag the desktop uses: every report persists, and only
     * a final one enqueues a gpodder action and nudges peers. On mobile the
     * non-final path deliberately never reaches the mesh — the trigger table
     * lists a playback tick as doing nothing.
     */
    private fun report(final: Boolean) {
        val episodeId = currentEpisodeId() ?: return
        val position = (player.currentPosition / 1000).coerceAtLeast(0)
        val duration = player.duration
        val total = if (duration > 0) duration / 1000 else 0L
        scope.launch(Dispatchers.IO) {
            core.reportPosition(episodeId, position, total, final)
        }
    }

    private fun currentEpisodeId(): Long? =
        player.currentMediaItem?.mediaId?.toLongOrNull()

    // ---------------------------------------------------------------- settings

    /**
     * Per-podcast speed, and the skip-intro seek.
     *
     * The desktop's rule, kept exactly: the effective start is
     * `max(saved position, skip_intro)`. Someone twenty minutes into an episode
     * does not get dragged back to the end of its theme tune.
     */
    private suspend fun applyPerPodcastSettings() {
        val episodeId = currentEpisodeId() ?: return
        val prepared = withContext(Dispatchers.IO) {
            val episode = core.repo.episodeById(episodeId) ?: return@withContext null
            val settings = core.repo.podcastSettings(episode.podcast_id)
            Prepared(
                speed = core.repo.effectiveSpeed(episode.podcast_id).toFloat(),
                startSecs = maxOf(episode.position_secs, settings?.skip_intro_secs ?: 0L),
            )
        } ?: return

        player.setPlaybackSpeed(prepared.speed)
        if (prepared.startSecs > 0 && player.currentPosition < prepared.startSecs * 1000) {
            player.seekTo(prepared.startSecs * 1000)
        }
    }

    private data class Prepared(val speed: Float, val startSecs: Long)

    /**
     * Treat `total - skip_outro` as the end of the episode.
     *
     * Checked on the write tick rather than on every position callback, which
     * is all the resolution a credits roll needs and keeps this off the hot path.
     */
    private fun checkSkipOutro() {
        if (outroFired) return
        val episodeId = currentEpisodeId() ?: return
        val duration = player.duration
        if (duration <= 0) return
        val position = player.currentPosition / 1000
        val total = duration / 1000

        main.launch {
            val skipOutro = withContext(Dispatchers.IO) {
                val episode = core.repo.episodeById(episodeId) ?: return@withContext 0L
                core.repo.podcastSettings(episode.podcast_id)?.skip_outro_secs ?: 0L
            }
            if (skipOutro > 0 && position >= total - skipOutro && !outroFired) {
                outroFired = true
                finishEpisode()
            }
        }
    }

    // ---------------------------------------------------------------- ads

    private suspend fun loadAds() {
        val episodeId = currentEpisodeId()
        val loaded = if (episodeId == null || !adsEnabled) null
        else withContext(Dispatchers.IO) { core.repo.db.adsFor(episodeId) }
        if (currentEpisodeId() != episodeId) return  // moved on while reading
        ads = loaded
        adsMapped = null
        publishAds()
        if (player.isPlaying) startAdTicking()
        mapAds()
    }

    /**
     * Move the spans onto the downloaded copy being played. The desktop analysed
     * its own download, which dynamic ad insertion may have filled with
     * different ads of different lengths; AdAnchors finds the show's own bytes
     * either side of each span in this file. A stream has no file to search, and
     * falls back to the length check in [usableSpans].
     */
    private suspend fun mapAds() {
        val found = ads ?: return
        val anchors = found.anchors ?: return
        val episodeId = currentEpisodeId() ?: return
        val duration = player.duration
        if (duration <= 0) return
        val mapped = withContext(Dispatchers.IO) {
            val episode = core.repo.episodeById(episodeId) ?: return@withContext null
            val file = episode.downloaded_path
                ?.takeIf { episode.download_state == "done" }
                ?.let(::File)?.takeIf { it.exists() } ?: return@withContext null
            runCatching {
                AdAnchors.mapSpans(
                    found.spans, anchors, found.durationMs,
                    AdAnchors.Audio(FileByteSource(file), duration), duration,
                )
            }.getOrNull()
        } ?: return
        if (ads !== found) return  // another episode, or newer spans, since this started
        adsMapped = mapped
        publishAds()
        if (player.isPlaying) startAdTicking()
    }

    /** The spans as placed in this copy, or as found if it is the same length. */
    private fun usableSpans(): List<AdSpan> {
        adsMapped?.let { return it }
        val found = ads ?: return emptyList()
        return if (AdSkip.applies(found.durationMs, player.duration)) found.spans else emptyList()
    }

    private fun publishAds() {
        val usable = usableSpans()
        val elsewhere = usable.isEmpty() && player.duration > 0 && !ads?.spans.isNullOrEmpty()
        adSkips.publish(usable, foundElsewhere = elsewhere)
    }

    private fun startAdTicking() {
        if (adTicker?.isActive == true || usableSpans().isEmpty()) return
        adTicker = main.launch {
            while (true) {
                checkAds()
                delay(AD_CHECK_MS)
            }
        }
    }

    private fun stopAdTicking() {
        adTicker?.cancel()
        adTicker = null
    }

    private fun checkAds() {
        val spans = usableSpans()
        val position = player.currentPosition
        val previous = adsPrevMs
        adsPrevMs = position
        val index = AdSkip.spanToSkip(spans, adsAllowed, previous, position) ?: return
        val span = spans[index]
        val episodeId = currentEpisodeId() ?: return
        adsLast = index
        if (span.endMs >= player.duration - AdSkip.END_MARGIN_MS) {
            finishEpisode()  // an ad is all that was left
            return
        }
        player.seekTo(span.endMs)
        adsPrevMs = span.endMs
        adSkips.announce(AdSkips.Skipped(episodeId, span.startMs, span.endMs))
    }

    private fun undoAdSkip() {
        val index = adsLast ?: return
        val span = usableSpans().getOrNull(index) ?: return
        adsAllowed += index
        adsLast = null
        player.seekTo(span.startMs)
        adsPrevMs = span.startMs
    }

    // ---------------------------------------------------------------- finish

    /**
     * End of episode: report it complete, mark it played, and advance.
     *
     * `markPlayedAndAdvance` returns the next episode and is what retires this
     * one from the derived queue — the same call the desktop's window makes
     * when the player reports a finish.
     */
    private fun finishEpisode() {
        val episodeId = currentEpisodeId() ?: return
        outroFired = true
        val duration = player.duration
        val total = if (duration > 0) duration / 1000 else 0L

        main.launch {
            val next = withContext(Dispatchers.IO) {
                if (total > 0) core.reportPosition(episodeId, total, total, final = true)
                core.queue.markPlayedAndAdvance(episodeId)
            }
            if (next == null) {
                player.pause()
                player.clearMediaItems()
                return@launch
            }
            val item = withContext(Dispatchers.IO) { mediaItemFor(next.id, core) }
            if (item == null) {
                player.pause()
                player.clearMediaItems()
            } else {
                player.setMediaItem(item)
                player.prepare()
                player.play()
            }
        }
    }
}
