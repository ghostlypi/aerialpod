package org.aerialpod.android.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import org.aerialpod.android.appGraph

/**
 * ExoPlayer in a media foreground service.
 *
 * A service rather than a player owned by the Activity, for two reasons that
 * are really one: playback has to survive the app being backgrounded, and the
 * peer link has to survive with it. `onBackgrounded(isPlaying = true)` leaves
 * the link open precisely because this service keeps the process alive, which
 * is what lets a pause on the phone reach the desktop in seconds rather than at
 * the next gpodder cycle.
 *
 * The service owns the player; [PlaybackCoordinator] owns everything that has
 * to happen *around* it — per-podcast speed, skip intro and outro, ad
 * skipping, position writes, auto-advance and the sleep timer.
 *
 * A library service rather than a plain session service so Android Auto can
 * browse it: [LibraryCallback] serves the tree and turns a pick into playback.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var library: LibraryCallback? = null
    private var coordinator: PlaybackCoordinator? = null

    override fun onCreate() {
        super.onCreate()
        val graph = appGraph

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // Let the platform duck and pause for us on calls and other
                // apps' audio; a podcast that keeps talking over a phone call
                // is the kind of bug users uninstall over.
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        val callback = LibraryCallback(graph.core).also { library = it }
        session = MediaLibrarySession.Builder(this, player, callback)
            .setMediaButtonPreferences(callback.mediaButtons)
            .build()
        coordinator = PlaybackCoordinator(
            player = player,
            core = graph.core,
            signals = graph.playback,
            adSkips = graph.adSkips,
            scope = graph.scope,
        ).also { it.attach() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    /**
     * Stop when the user swipes the app away and nothing is playing.
     *
     * Media3's default is to keep the service alive, which leaves a paused
     * notification behind for an app the user has just dismissed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        coordinator?.detach()
        coordinator = null
        session?.run {
            player.release()
            release()
        }
        session = null
        library?.release()
        library = null
        super.onDestroy()
    }
}
