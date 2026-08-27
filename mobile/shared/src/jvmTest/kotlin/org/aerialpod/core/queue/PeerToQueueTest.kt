package org.aerialpod.core.queue

import org.aerialpod.core.lan.IntentRecord
import org.aerialpod.core.lan.PositionRecord
import org.aerialpod.core.lan.Snapshot
import org.aerialpod.core.lan.SnapshotSync
import org.aerialpod.core.sync.Matcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The seam between the merge and the queue.
 *
 * A merge writes intents and queue rows straight from a peer's snapshot, but
 * the queue is *derived* — every device rebuilds it. So the question these
 * tests answer is what survives the rebuild that follows: an order the user set
 * on another machine has to still be there afterwards, and an episode that
 * machine threw out has to stay out.
 */
class PeerToQueueTest {

    private class Fx {
        val lib = Library()
        val podcast = lib.addPodcast()
        val episodes = lib.fiveEpisodes(podcast)
        val sync = SnapshotSync(lib.repo, Matcher(lib.repo)) { 1_700_000_000L }
        fun ids() = lib.queueIds()

        /** What a peer's snapshot looks like for our episode at [index]. */
        fun intent(index: Int, intent: String, position: Long, at: Long, pinned: Long = 0) =
            IntentRecord(
                feed = Library.FEED,
                guid = "guid-$podcast-${index + 1}",
                media = "https://cdn.example.com/ep${(index + 1).toString().padStart(3, '0')}.mp3",
                intent = intent,
                position = position,
                pinned = pinned,
                origin = "manual",
                updatedAt = at,
                updatedBy = "desktop9",
            )
    }

    /** The desktop's user dragged ep5, ep3, ep1 into that order. */
    private fun draggedOrder(f: Fx) = Snapshot(intents = listOf(
        f.intent(4, "queued", position = 1024, at = 2000, pinned = 1),
        f.intent(2, "queued", position = 2048, at = 2000, pinned = 1),
        f.intent(0, "queued", position = 3072, at = 2000, pinned = 1),
    ))

    /**
     * The whole point of device sync — and the desktop's own
     * `test_queue_order_travels`: a phone that has not built its queue yet
     * adopts the order wholesale, and the episodes it knows about but the peer
     * had no opinion on append underneath.
     */
    @Test
    fun aPeersQueueOrderArrivesOnAFreshDevice() {
        val f = Fx()
        f.sync.mergeSnapshot(draggedOrder(f))
        assertEquals(listOf(f.episodes[4], f.episodes[2], f.episodes[0]), f.ids())

        f.lib.queue.reconcile()
        assertEquals(
            listOf(f.episodes[4], f.episodes[2], f.episodes[0], f.episodes[1], f.episodes[3]),
            f.ids(),
            "the peer's three keep the top; ours append oldest-first",
        )
    }

    /**
     * When both devices already had a queue, the peer's positions interleave
     * with the local ones rather than replacing them — a snapshot only carries
     * intents for episodes someone actually decided about, and an episode with
     * no intent keeps the position it had here.
     *
     * What must hold is that the peer's episodes keep their order *relative to
     * each other*, and that reconcile never re-sorts any of it.
     */
    @Test
    fun aPeersOrderInterleavesWithLocalEpisodesAndSurvivesReconcile() {
        val f = Fx()
        f.lib.queue.reconcile()
        assertEquals(f.episodes, f.ids())

        f.sync.mergeSnapshot(draggedOrder(f))
        val merged = f.ids()
        val theirs = listOf(f.episodes[4], f.episodes[2], f.episodes[0])
        assertEquals(theirs, merged.filter { it in theirs }, "their relative order")
        assertEquals(f.episodes.size, merged.size, "nothing lost in the merge")

        // reconcile() only removes and inserts; it must never re-sort survivors.
        f.lib.queue.reconcile()
        f.lib.queue.reconcile()
        assertEquals(merged, f.ids())
    }

    @Test
    fun aPeersExclusionKeepsTheEpisodeOutAcrossReconciles() {
        val f = Fx()
        f.lib.queue.reconcile()
        assertTrue(f.episodes[1] in f.ids())

        f.sync.mergeSnapshot(Snapshot(intents = listOf(
            f.intent(1, "excluded", position = 0, at = 2000),
        )))
        assertFalse(f.episodes[1] in f.ids())

        f.lib.queue.reconcile()
        f.lib.queue.reconcile()
        assertFalse(f.episodes[1] in f.ids(), "a peer's removal must be as permanent as a local one")
    }

    /** Started on the desktop → floats to the top here, once the queue rebuilds. */
    @Test
    fun aPeersPositionFloatsTheEpisodeUpOnReconcile() {
        val f = Fx()
        f.lib.queue.reconcile()
        assertEquals(f.episodes[0], f.ids()[0])

        f.sync.mergeSnapshot(Snapshot(positions = listOf(
            PositionRecord(
                feed = Library.FEED,
                guid = "guid-${f.podcast}-5",
                media = "https://cdn.example.com/ep005.mp3",
                position = 300, total = 3000, updatedAt = 1_700_999_999,
            )
        )))
        // The merge moves the position; the queue only learns about it here.
        f.lib.queue.reconcile()
        assertEquals(f.episodes[4], f.ids()[0])
    }

    /**
     * A peer that finished an episode sends no removal — the position at the end
     * of the file is enough, because reconcile drops it on its own.
     */
    @Test
    fun aPeersCompletedPositionDropsTheEpisode() {
        val f = Fx()
        f.lib.queue.reconcile()
        f.sync.mergeSnapshot(Snapshot(positions = listOf(
            PositionRecord(
                feed = Library.FEED,
                guid = "guid-${f.podcast}-2",
                media = "https://cdn.example.com/ep002.mp3",
                position = 2985, total = 3000, updatedAt = 1_700_999_999,
            )
        )))
        f.lib.queue.reconcile()
        assertFalse(f.episodes[1] in f.ids())
    }

    /** Merging must not look like a local decision, or it would echo back. */
    @Test
    fun mergingDoesNotAnnounceAnIntentChange() {
        val f = Fx()
        f.lib.queue.reconcile()
        f.sync.mergeSnapshot(Snapshot(intents = listOf(
            f.intent(3, "queued", position = 1024, at = 2000, pinned = 1),
        )))
        f.lib.queue.reconcile()
        assertEquals(0, f.lib.intentChanges, "a merge is not a local edit")
    }
}
