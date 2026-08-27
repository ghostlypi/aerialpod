package org.aerialpod.android.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two playback rows of the trigger table in `docs/mobile-lan-sync.md`:
 * "playback starts and no link is up" dials, and "play / pause / seek / stop /
 * episode change" pushes a position immediately. The tick row is explicitly
 * **nothing** — the desktop's 5-second heartbeat is dropped on mobile.
 */
class PlaybackSignalsTest {

    private class Recorder : PeerTriggers {
        var dials = 0
        val transports = mutableListOf<Long?>()
        override fun onPlaybackStarted() { dials++ }
        override fun onTransportEvent(episodeId: Long?) { transports += episodeId }
    }

    @Test
    fun startingPlaybackDialsOnceAndPushesAPosition() {
        val peers = Recorder()
        val signals = PlaybackSignals(peers)

        signals.setPlaying(true, episodeId = 7L)

        assertEquals(1, peers.dials)
        assertEquals(listOf<Long?>(7L), peers.transports)
        assertTrue(signals.isPlaying.value)
        assertEquals(7L, signals.currentEpisodeId.value)
    }

    @Test
    fun aPlayerReportingPlayingOnEveryTickDialsOnlyOnce() {
        // The failure this guards: a MediaSession callback that reports state on
        // a timer would dial once per second if the edge check were dropped, and
        // nothing on screen would look wrong.
        val peers = Recorder()
        val signals = PlaybackSignals(peers)

        repeat(50) { signals.setPlaying(true, episodeId = 7L) }

        assertEquals(1, peers.dials)
        assertEquals(1, peers.transports.size)
    }

    @Test
    fun pausePushesAPositionButDoesNotDial() {
        val peers = Recorder()
        val signals = PlaybackSignals(peers)
        signals.setPlaying(true, episodeId = 7L)

        signals.setPlaying(false)

        assertEquals(1, peers.dials, "pausing must not dial")
        assertEquals(2, peers.transports.size, "but it is a transport event")
        assertFalse(signals.isPlaying.value)
    }

    @Test
    fun resumingAfterAPauseDialsAgain() {
        // A link may well have dropped while paused, so the second start is a
        // genuine dial trigger rather than a repeat of the first.
        val peers = Recorder()
        val signals = PlaybackSignals(peers)
        signals.setPlaying(true, episodeId = 7L)
        signals.setPlaying(false)

        signals.setPlaying(true)

        assertEquals(2, peers.dials)
    }

    @Test
    fun seeksAndStopsPushWithoutDialling() {
        val peers = Recorder()
        val signals = PlaybackSignals(peers)
        signals.setPlaying(true, episodeId = 7L)

        signals.notifyTransportEvent()
        signals.notifyTransportEvent()

        assertEquals(1, peers.dials)
        assertEquals(listOf<Long?>(7L, 7L, 7L), peers.transports)
    }

    @Test
    fun anEpisodeChangeCarriesTheNewIdOnward() {
        val peers = Recorder()
        val signals = PlaybackSignals(peers)
        signals.setPlaying(true, episodeId = 7L)

        signals.notifyEpisodeChanged(9L)
        signals.notifyTransportEvent()

        assertEquals(listOf<Long?>(7L, 9L, 9L), peers.transports)
        assertEquals(9L, signals.currentEpisodeId.value)
    }

    @Test
    fun isPlayingIsWhatDecidesWhetherBackgroundingClosesTheLink() {
        // PeerLifecycle reads this to choose between closing the link and
        // keeping it: audio playing means the process survives and a pause from
        // the phone should still reach the desktop in seconds.
        val signals = PlaybackSignals(Recorder())
        assertFalse(signals.isPlaying.value)
        signals.setPlaying(true, episodeId = 1L)
        assertTrue(signals.isPlaying.value)
        signals.setPlaying(false)
        assertFalse(signals.isPlaying.value)
    }
}
