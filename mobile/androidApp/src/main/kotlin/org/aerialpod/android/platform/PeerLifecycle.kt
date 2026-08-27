package org.aerialpod.android.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.aerialpod.core.AerialPodCore

/**
 * The last mile: the peer service written in step 1 finally gets told when
 * anything happens.
 *
 * `ProcessLifecycleOwner` rather than an Activity's lifecycle, because these
 * are process-wide facts. An Activity observer would fire on every rotation and
 * report a backgrounded app during a configuration change, which would close a
 * perfectly good link and re-dial a second later.
 *
 * Rows three and eight of the doc's trigger table:
 *
 *  - **foregrounded** — reset the backoff and dial remembered peers.
 *  - **backgrounded and not playing** — close the link. Playing means the
 *    process survives in a media foreground service, and the link is what
 *    carries a pause from the phone to the desktop in seconds.
 */
class PeerLifecycle(
    private val core: AerialPodCore,
    private val playback: PlaybackSignals,
    private val network: NetworkMonitor,
) : DefaultLifecycleObserver {

    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // `start()` is idempotent — it returns early when already running — so
        // every foreground can call it rather than tracking first-run state.
        core.start()
        network.start()
        core.lan.onForegrounded()
    }

    override fun onStop(owner: LifecycleOwner) {
        core.lan.onBackgrounded(playback.isPlaying.value)
        // The path callback is only useful while something can act on it. While
        // playing, the process lives on and a network change still needs to
        // re-dial, so the monitor stays registered in that case.
        if (!playback.isPlaying.value) network.stop()
    }
}
