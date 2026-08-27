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
import org.aerialpod.android.platform.PlaybackSignals
import org.aerialpod.core.AerialPodCore

/**
 * Everything that has to happen around the player.
 *
 * The port of the desktop's `core/player.py`, minus the parts Media3 already
 * does. What is left is the behaviour the two apps must share: per-podcast
 * speed, skip intro and outro, throttled position writes, and what "finished"
 * means.
 *
 * It lives beside the player in the service rather than in the UI, because
 * every one of these has to keep working with the app backgrounded and the
 * screen off.
 */
class PlaybackCoordinator(
    private val player: Player,
    private val core: AerialPodCore,
    private val signals: PlaybackSignals,
    private val scope: CoroutineScope,
) {
    companion object {
        /** `POSITION_WRITE_MS` from `core/player.py`. */
        const val POSITION_WRITE_MS = 5_000L
    }

    /**
     * ExoPlayer is thread-confined to the thread it was built on — the
     * service's main thread. Every read of `player.currentPosition` and every
     * command has to happen here or it throws; only the database work is moved
     * off it.
     */
    private val main = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)

    private var ticker: Job? = null

    /**
     * Set once per episode so a skip-outro fires at most once.
     *
     * Without it the outro check would re-fire on every tick past the
     * threshold, marking the episode played repeatedly and advancing the queue
     * more than once.
     */
    private var outroFired = false

    private val listener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicking() else stopTicking()
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
            main.launch { applyPerPodcastSettings() }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) finishEpisode()
        }
    }

    fun attach() {
        player.addListener(listener)
    }

    fun detach() {
        stopTicking()
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
