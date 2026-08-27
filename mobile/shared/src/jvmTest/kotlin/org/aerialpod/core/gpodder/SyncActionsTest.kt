package org.aerialpod.core.gpodder

import org.aerialpod.core.queue.Library
import org.aerialpod.core.sync.Matcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `applyAction` semantics — last-writer-wins, finished detection, unmatched
 * logging. The port of `tests/test_sync_actions.py`; pure database, no network.
 */
class SyncActionsTest {

    private class Fx {
        val lib = Library()
        val podcast = lib.addPodcast()
        val sync = GpodderSync(
            repo = lib.repo,
            matcher = Matcher(lib.repo),
            deviceCaption = "Test Phone",
            clientProvider = { null },
            now = { 1_700_000_000L },
            dryRun = true,
        )
    }

    private fun playAction(
        url: String,
        position: Long,
        total: Long = 3000,
        timestamp: String = "2026-07-16T10:00:00",
    ) = EpisodeAction(
        podcast = Library.FEED, episode = url, action = "play",
        timestamp = timestamp, started = position, position = position, total = total,
    )

    @Test
    fun playUpdatesPosition() {
        val f = Fx()
        val id = f.lib.makeEpisode(f.podcast, 1)
        assertTrue(f.sync.applyAction(playAction("https://cdn.example.com/ep001.mp3", 600)))
        val episode = f.lib.episode(id)
        assertEquals(600L, episode.position_secs)
        assertEquals(3000L, episode.total_secs)
        assertNotEquals("played", episode.state)
    }

    @Test
    fun playNearTotalMarksPlayed() {
        val f = Fx()
        val id = f.lib.makeEpisode(f.podcast, 1)
        f.sync.applyAction(playAction("https://cdn.example.com/ep001.mp3", 2985))
        assertEquals("played", f.lib.episode(id).state)
    }

    @Test
    fun anOlderActionLoses() {
        val f = Fx()
        // The action's timestamp is 2026-07-16T10:00:00 ≈ 1784196000 < 1789000000.
        val id = f.lib.makeEpisode(f.podcast, 1, position = 900, updatedAt = 1_789_000_000)
        f.sync.applyAction(playAction("https://cdn.example.com/ep001.mp3", 100))
        assertEquals(900L, f.lib.episode(id).position_secs)
    }

    @Test
    fun aNewerActionWins() {
        val f = Fx()
        val id = f.lib.makeEpisode(f.podcast, 1, position = 100, updatedAt = 100)
        f.sync.applyAction(playAction("https://cdn.example.com/ep001.mp3", 1200))
        assertEquals(1200L, f.lib.episode(id).position_secs)
        assertEquals(1_784_196_000L, f.lib.episode(id).position_updated_at)
    }

    @Test
    fun deleteMarksPlayed() {
        val f = Fx()
        val id = f.lib.makeEpisode(f.podcast, 1)
        f.sync.applyAction(EpisodeAction(
            podcast = Library.FEED, episode = "https://cdn.example.com/ep001.mp3",
            action = "delete", timestamp = "2026-07-16T10:00:00",
        ))
        assertEquals("played", f.lib.episode(id).state)
    }

    @Test
    fun anUnmatchedEpisodeOfAKnownPodcastIsLogged() {
        val f = Fx()
        f.lib.makeEpisode(f.podcast, 1)
        val handled = f.sync.applyAction(playAction("https://cdn.example.com/who-is-this.mp3", 5))
        assertFalse(handled)
        assertEquals(1L, f.lib.repo.unmatchedCount())
    }

    /** Actions for feeds we do not carry are not interesting — and not noise. */
    @Test
    fun anUnknownPodcastIsNotLogged() {
        val f = Fx()
        val handled = f.sync.applyAction(EpisodeAction(
            podcast = "https://elsewhere.com/feed", episode = "https://elsewhere.com/e.mp3",
            action = "play", timestamp = "2026-07-16T10:00:00", position = 5,
        ))
        assertTrue(handled)
        assertEquals(0L, f.lib.repo.unmatchedCount())
    }

    /** The AntennaPod total=0 guard. */
    @Test
    fun aZeroTotalActionDoesNotFinish() {
        val f = Fx()
        val id = f.lib.makeEpisode(f.podcast, 1)
        f.sync.applyAction(playAction("https://cdn.example.com/ep001.mp3", 2980, total = 0))
        val episode = f.lib.episode(id)
        assertEquals(2980L, episode.position_secs)
        assertNotEquals("played", episode.state)
    }

    /**
     * Another device queued a back-catalog episode → download action → must
     * become 'inbox' so reconcile queues it.
     */
    @Test
    fun aDownloadActionPromotesAnArchivedEpisode() {
        val f = Fx()
        val id = f.lib.makeEpisode(f.podcast, 2, state = "archived")
        f.sync.applyAction(EpisodeAction(
            podcast = Library.FEED, episode = "https://cdn.example.com/ep002.mp3",
            action = "download", timestamp = "2026-07-16T10:00:00",
        ))
        assertEquals("inbox", f.lib.episode(id).state)
    }

    /** A 'new' action elsewhere resets progress here. */
    @Test
    fun aNewActionResetsTheEpisode() {
        val f = Fx()
        val id = f.lib.makeEpisode(f.podcast, 1, state = "played", position = 3000, total = 3000)
        f.sync.applyAction(EpisodeAction(
            podcast = Library.FEED, episode = "https://cdn.example.com/ep001.mp3",
            action = "new", timestamp = "2026-07-16T10:00:00",
        ))
        val episode = f.lib.episode(id)
        assertEquals("new", episode.state)
        assertEquals(0L, episode.position_secs)
    }

    /** A duration with no position is still worth keeping — isFinished needs it. */
    @Test
    fun aTotalWithoutAPositionIsStored() {
        val f = Fx()
        val id = f.lib.makeEpisode(f.podcast, 1)
        f.sync.applyAction(playAction("https://cdn.example.com/ep001.mp3", 0, total = 4200))
        assertEquals(4200L, f.lib.episode(id).total_secs)
        assertEquals(0L, f.lib.episode(id).position_secs)
    }

    /** A tracker-wrapped URL still finds the episode, via the matching ladder. */
    @Test
    fun aTrackerWrappedActionUrlStillMatches() {
        val f = Fx()
        val id = f.lib.makeEpisode(f.podcast, 3)
        val wrapped = "https://dts.podtrac.com/redirect.mp3/cdn.example.com/ep003.mp3"
        assertTrue(f.sync.applyAction(playAction(wrapped, 120)))
        assertEquals(120L, f.lib.episode(id).position_secs)
    }
}
