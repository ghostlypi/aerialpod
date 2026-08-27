package org.aerialpod.core.lan

import org.aerialpod.core.queue.QueueManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Played" crossing the wire.
 *
 * A position cannot say an episode is done. One abandoned twenty minutes into
 * an hour and then marked played looks, to anyone who only sees the number,
 * exactly like one still in progress — so it lands back in their queue. That is
 * what a phone syncing with an established desktop sees, and why its queue came
 * out with ten items against the desktop's three.
 */
class FinishedPositionTest {

    private fun pair(): Pair<Device, Device> = Device("aaaa1111") to Device("bbbb2222")

    /** Abandoned partway, then marked played — the shape that caused the bug. */
    private fun Device.abandonThenMarkPlayed(episodeId: Long, at: Long = 1_700_000_500L) {
        db.episodesQueries.setEpisodePosition(1200, 3600, at, episodeId)
        db.episodesQueries.setEpisodeState("played", episodeId)
    }

    @Test
    fun aPositionAbandonedPartwayCarriesTheFactThatItIsDone() {
        val (desktop, phone) = pair()
        desktop.abandonThenMarkPlayed(3)

        val record = desktop.sync.buildSnapshot().positions.single { it.guid == "guid-1-3" }

        assertEquals(1200, record.position, "the resume point is still the real one")
        assertEquals(true, record.finished, "and the sender says it is done")
    }

    @Test
    fun theReceiverMarksItPlayedRatherThanTreatingItAsInProgress() {
        val (desktop, phone) = pair()
        desktop.abandonThenMarkPlayed(3)

        phone.sync.mergeSnapshot(desktop.sync.buildSnapshot())

        val episode = phone.repo.episodeById(3)!!
        assertEquals("played", episode.state)
        assertEquals(1200, episode.position_secs, "the resume point survives, for un-marking later")
    }

    @Test
    fun andSoItDoesNotEnterTheReceiversQueue() {
        val (desktop, phone) = pair()
        desktop.abandonThenMarkPlayed(3)
        phone.sync.mergeSnapshot(desktop.sync.buildSnapshot())

        val queue = QueueManager(phone.repo) { 1_700_000_000L }
        queue.reconcile()

        assertTrue(
            queue.episodes().none { it.id == 3L },
            "an episode the other device has finished has no business in this one's queue",
        )
    }

    @Test
    fun anUnfinishedPositionStillReplicatesAsInProgress() {
        // The handoff case, which must keep working: genuinely half-listened.
        val (desktop, phone) = pair()
        desktop.db.episodesQueries.setEpisodePosition(1200, 3600, 1_700_000_500L, 3)

        phone.sync.mergeSnapshot(desktop.sync.buildSnapshot())

        val episode = phone.repo.episodeById(3)!!
        assertEquals("new", episode.state, "not played — the sender never said so")
        assertEquals(1200, episode.position_secs)
    }

    @Test
    fun aPeerThatPredatesTheFieldCannotUnmarkAnythingPlayed() {
        // The reason only `true` is acted on. An older peer omits the key
        // entirely, which deserialises as false; reading that as "unplayed"
        // would wipe played state across the whole mesh.
        val (_, phone) = pair()
        phone.db.episodesQueries.setEpisodePosition(1200, 3600, 1_700_000_400L, 3)
        phone.db.episodesQueries.setEpisodeState("played", 3)

        val fromOldPeer = Snapshot(
            positions = listOf(
                PositionRecord(
                    feed = FEED, guid = "guid-1-3", media = "https://cdn.example.com/ep003.mp3",
                    position = 1300, total = 3600, updatedAt = 1_700_000_900L,
                    // finished omitted, exactly as an older build sends it
                )
            )
        )
        phone.sync.mergeSnapshot(fromOldPeer)

        assertEquals(
            "played", phone.repo.episodeById(3)!!.state,
            "an omitted flag means 'I have nothing to say', not 'this is unplayed'",
        )
        assertEquals(1300, phone.repo.episodeById(3)!!.position_secs, "the position still moves")
    }

    @Test
    fun aStalerRecordCannotResurrectAnEpisode() {
        val (desktop, phone) = pair()
        phone.db.episodesQueries.setEpisodePosition(1200, 3600, 1_700_000_900L, 3)
        phone.db.episodesQueries.setEpisodeState("played", 3)
        desktop.db.episodesQueries.setEpisodePosition(60, 3600, 1_700_000_100L, 3)

        phone.sync.mergeSnapshot(desktop.sync.buildSnapshot())

        assertEquals("played", phone.repo.episodeById(3)!!.state)
        assertEquals(1200, phone.repo.episodeById(3)!!.position_secs)
    }
}
