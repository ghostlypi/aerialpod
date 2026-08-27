package org.aerialpod.android.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The seam between the player and the peer mesh.
 *
 * Two of the doc's triggers come from playback — "playback starts and no link
 * is up" dials, and "play / pause / seek / stop / episode change" pushes a
 * position immediately. Nothing calls them yet, because there is no player
 * until step 6.2.
 *
 * It exists now rather than later so 6.2 has one obvious place to report to,
 * instead of reaching into `LanPeerService` from inside a MediaSession callback
 * and quietly getting the set of triggers wrong. [isPlaying] is also what
 * decides whether backgrounding closes the link: audio playing means the
 * process stays alive and the link is worth keeping.
 */
class PlaybackSignals(private val peers: PeerTriggers) {

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentEpisodeId = MutableStateFlow<Long?>(null)
    val currentEpisodeId: StateFlow<Long?> = _currentEpisodeId.asStateFlow()

    /**
     * Play or pause. A transition into playing dials if nothing is up, which is
     * the doc's third dial trigger; either edge is a transport event and pushes
     * a position straight away.
     */
    fun setPlaying(playing: Boolean, episodeId: Long? = _currentEpisodeId.value) {
        _currentEpisodeId.value = episodeId
        val wasPlaying = _isPlaying.value
        _isPlaying.value = playing
        if (playing && !wasPlaying) peers.onPlaybackStarted()
        if (playing != wasPlaying) peers.onTransportEvent(episodeId)
    }

    /** A seek, stop or episode change — everything that is not a play/pause edge. */
    fun notifyTransportEvent(episodeId: Long? = _currentEpisodeId.value) {
        _currentEpisodeId.value = episodeId
        peers.onTransportEvent(episodeId)
    }

    fun notifyEpisodeChanged(episodeId: Long?) {
        _currentEpisodeId.value = episodeId
        peers.onTransportEvent(episodeId)
    }
}

/**
 * The two peer triggers playback owns, behind an interface.
 *
 * Not because there will ever be a second implementation, but because the rules
 * below — dial only on the *edge* into playing, push a position on either edge —
 * are the kind that break quietly. A player calling `setPlaying(true)` on every
 * tick would dial on every tick if the edge check were dropped, and nothing
 * about the app would look wrong.
 */
interface PeerTriggers {
    fun onPlaybackStarted()
    fun onTransportEvent(episodeId: Long?)
}
