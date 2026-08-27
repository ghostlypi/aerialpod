package org.aerialpod.android.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aerialpod.core.AerialPodCore

/**
 * What the UI talks to.
 *
 * Connects a `MediaController` to [PlaybackService] and mirrors the player's
 * state into a flow Compose can collect. Commands are queued until the
 * connection lands, so a play tapped a moment after launch is not dropped —
 * the alternative is a button that silently does nothing on a cold start.
 */
class PlayerController(
    private val context: Context,
    private val core: AerialPodCore,
    private val scope: CoroutineScope,
) {
    data class State(
        val episodeId: Long? = null,
        val title: String = "",
        val podcast: String = "",
        val artworkUri: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val speed: Float = 1f,
        val sleep: String = "",
    ) {
        val hasEpisode: Boolean get() = episodeId != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * A `MediaController` may only be used from the thread it was built on.
     * Everything that touches it goes through here; the database reads that
     * feed it are the only part moved off.
     */
    private val main = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var progress: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val ready = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = ready
            ready.addListener(listener)
            while (pending.isNotEmpty()) pending.removeFirst()(ready)
            refresh()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        main.cancel()
        progress?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    // ---------------------------------------------------------------- commands

    private fun withController(block: (MediaController) -> Unit) {
        main.launch {
            val ready = controller
            if (ready != null) block(ready) else pending.addLast(block)
            connect()
        }
    }

    /** Start an episode, or resume it if it is already loaded. */
    fun play(episodeId: Long) {
        scope.launch {
            val item = withContext(Dispatchers.IO) { mediaItemFor(episodeId, core) } ?: return@launch
            withController { player ->
                if (player.currentMediaItem?.mediaId == episodeId.toString()) {
                    player.play()
                } else {
                    player.setMediaItem(item)
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    fun togglePlayPause() = withController { player ->
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() = withController { it.pause() }

    /** Relative seek, using the user's configured skip amounts. */
    fun skipForward() = seekByState("skip_fwd_secs", 30L, forward = true)

    fun skipBack() = seekByState("skip_back_secs", 10L, forward = false)

    private fun seekByState(key: String, default: Long, forward: Boolean) {
        scope.launch {
            val secs = withContext(Dispatchers.IO) { core.repo.stateLong(key, default) }
            withController { player ->
                val delta = (if (forward) secs else -secs) * 1000
                player.seekTo((player.currentPosition + delta).coerceAtLeast(0))
            }
        }
    }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0)) }

    /**
     * Change speed for this podcast, not just this session.
     *
     * A speed set from the player is a per-podcast setting on the desktop, and
     * it replicates to the other devices — so it is written through rather than
     * held in the player alone.
     */
    fun setSpeed(speed: Float) {
        withController { it.setPlaybackSpeed(speed) }
        scope.launch(Dispatchers.IO) {
            val episodeId = _state.value.episodeId ?: return@launch
            val episode = core.repo.episodeById(episodeId) ?: return@launch
            core.repo.updatePodcastSettings(episode.podcast_id) {
                it.copy(playbackSpeed = speed.toDouble())
            }
        }
    }

    // ---------------------------------------------------------------- sleep

    private var sleepJob: Job? = null
    private var sleepUntilEndOfEpisode = false

    /**
     * Minutes, or until the end of the episode.
     *
     * No volume fade, unlike the desktop's: Android's own media volume is a
     * system-level thing and quietly turning it down would leave the user's
     * device changed after the timer fired.
     */
    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepJob = scope.launch {
            var remaining = minutes * 60
            while (remaining > 0) {
                _state.value = _state.value.copy(sleep = sleepLabel(remaining))
                delay(1000)
                remaining--
            }
            withController { it.pause() }
            _state.value = _state.value.copy(sleep = "")
        }
    }

    fun sleepAtEndOfEpisode() {
        cancelSleepTimer()
        sleepUntilEndOfEpisode = true
        _state.value = _state.value.copy(sleep = "Sleep: end of episode")
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepUntilEndOfEpisode = false
        _state.value = _state.value.copy(sleep = "")
    }

    private fun sleepLabel(remaining: Int): String {
        val minutes = (remaining + 59) / 60
        return if (minutes > 1) "Sleep in $minutes min" else "Sleep in under a minute"
    }

    // ---------------------------------------------------------------- state

    private fun refresh() {
        val player = controller ?: return
        val item: MediaItem? = player.currentMediaItem
        val episodeId = item?.mediaId?.toLongOrNull()

        // End of episode with the sleep timer armed for exactly that.
        if (sleepUntilEndOfEpisode && episodeId == null) cancelSleepTimer()

        _state.value = _state.value.copy(
            episodeId = episodeId,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            podcast = item?.mediaMetadata?.artist?.toString().orEmpty(),
            artworkUri = item?.mediaMetadata?.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
            speed = player.playbackParameters.speed,
        )

        if (player.isPlaying) startProgress() else progress?.cancel()
    }

    /**
     * A 1 Hz UI tick.
     *
     * Only the progress bar — it writes nothing and tells no peer anything. The
     * database write is the coordinator's five-second tick, and the mesh hears
     * about playback on transport events alone.
     */
    private fun startProgress() {
        if (progress?.isActive == true) return
        progress = main.launch {
            while (true) {
                val player = controller ?: break
                _state.value = _state.value.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = player.duration.takeIf { it > 0 } ?: 0,
                )
                delay(1000)
            }
        }
    }
}
