package org.aerialpod.core.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `QueueManager.reconcile()` scenarios — the port of the desktop's
 * `tests/test_queue_reconcile.py`, case for case and in the same order.
 *
 * This is the algorithm the whole app is arranged around: the queue is derived
 * from episode state, but the user's overrides have to survive every rebuild.
 */
class QueueReconcileTest {

    private class Fx {
        val lib = Library()
        val podcast = lib.addPodcast()
        val episodes = lib.fiveEpisodes(podcast)
        val qm get() = lib.queue
        fun ids() = lib.queueIds()
    }

    private fun fx() = Fx()

    // ---------------------------------------------------------------- ordering

    @Test
    fun freshEpisodesAppendChronologically() {
        val f = fx()
        f.qm.reconcile()
        assertEquals(f.episodes, f.ids()) // ep1 (oldest) first
    }

    @Test
    fun inProgressInsertsAtTop() {
        val f = fx()
        f.qm.reconcile()
        // another device started ep5 (newest, currently last in the queue)
        f.lib.setEpisode(f.episodes[4], position = 300, total = 3000, updatedAt = 1_700_999_999)
        f.qm.reconcile()
        assertEquals(f.episodes[4], f.ids()[0])
        assertEquals(f.episodes.take(4), f.ids().drop(1)) // rest keeps relative order
    }

    @Test
    fun finishedElsewhereDropsWithoutResort() {
        val f = fx()
        f.qm.reconcile()
        f.qm.move(f.episodes[2], 0) // user rearranged: ep3 to the front (pins it)
        assertEquals(f.episodes[2], f.ids()[0])

        f.lib.setEpisode(f.episodes[0], state = "played", position = 2970, total = 3000)
        f.qm.reconcile()

        val ids = f.ids()
        assertFalse(f.episodes[0] in ids)
        assertEquals(f.episodes[2], ids[0]) // manual arrangement preserved
        assertEquals(listOf(f.episodes[1], f.episodes[3], f.episodes[4]), ids.drop(1))
    }

    @Test
    fun nearTotalCountsAsFinished() {
        val f = fx()
        f.qm.reconcile()
        f.lib.setEpisode(f.episodes[1], position = 2980, total = 3000)
        f.qm.reconcile()
        assertFalse(f.episodes[1] in f.ids())
    }

    /** AntennaPod sometimes reports total=0 — that must not read as finished. */
    @Test
    fun zeroTotalNeverFinishes() {
        val f = fx()
        f.qm.reconcile()
        f.lib.setEpisode(f.episodes[1], position = 2980, total = 0)
        f.qm.reconcile()
        assertTrue(f.episodes[1] in f.ids())
    }

    // ---------------------------------------------------------------- overrides

    @Test
    fun userRemovalIsPermanent() {
        val f = fx()
        f.qm.reconcile()
        f.qm.remove(f.episodes[2])
        f.qm.reconcile()
        f.qm.reconcile()
        assertFalse(f.episodes[2] in f.ids())
    }

    @Test
    fun manualReaddClearsExclusion() {
        val f = fx()
        f.qm.reconcile()
        f.qm.remove(f.episodes[2])
        f.qm.add(f.episodes[2])
        assertTrue(f.episodes[2] in f.ids())
        f.qm.reconcile()
        assertTrue(f.episodes[2] in f.ids())
    }

    @Test
    fun playingEpisodeNeverRemoved() {
        val f = fx()
        f.qm.reconcile()
        f.qm.playingEpisodeId = f.episodes[0]
        // even if marked played remotely (a stale action), the playing row stays
        f.lib.setEpisode(f.episodes[0], state = "played")
        f.qm.reconcile()
        assertTrue(f.episodes[0] in f.ids())
    }

    @Test
    fun pinnedRemovedWhenFinished() {
        val f = fx()
        f.qm.reconcile()
        f.qm.move(f.episodes[1], 0) // pins ep2
        f.lib.setEpisode(f.episodes[1], state = "played")
        f.qm.reconcile()
        assertFalse(f.episodes[1] in f.ids())
    }

    @Test
    fun inProgressInsertsAfterPinnedBlock() {
        val f = fx()
        f.qm.reconcile()
        f.qm.move(f.episodes[0], 0) // pinned head
        f.lib.setEpisode(f.episodes[4], position = 60, total = 3000, updatedAt = 1_700_999_999)
        f.qm.reconcile()
        val ids = f.ids()
        assertEquals(f.episodes[0], ids[0]) // pinned stays first
        assertEquals(f.episodes[4], ids[1]) // in-progress right after the pinned block
    }

    // ---------------------------------------------------------------- auto-add

    @Test
    fun autoAddOffKeepsFreshOut() {
        val lib = Library()
        val podcast = lib.addPodcast()
        lib.repo.updatePodcastSettings(podcast) { it.copy(autoAddToQueue = 0) }
        lib.makeEpisode(podcast, 10)
        lib.queue.reconcile()
        assertEquals(emptyList(), lib.queueIds())
    }

    @Test
    fun autoAddOffStillAddsInProgress() {
        val lib = Library()
        val podcast = lib.addPodcast()
        lib.repo.updatePodcastSettings(podcast) { it.copy(autoAddToQueue = 0) }
        val id = lib.makeEpisode(podcast, 11, position = 120, total = 3000, updatedAt = 1_700_999_999)
        lib.queue.reconcile()
        assertEquals(listOf(id), lib.queueIds())
    }

    @Test
    fun unsubscribeClearsQueue() {
        val f = fx()
        f.qm.reconcile()
        f.lib.repo.unsubscribePodcast(f.podcast)
        f.qm.reconcile()
        assertEquals(emptyList(), f.ids())
    }

    // ---------------------------------------------------------------- stability

    @Test
    fun reconcileIsIdempotent() {
        val f = fx()
        f.qm.reconcile()
        val first = f.ids()
        f.qm.reconcile()
        f.qm.reconcile()
        assertEquals(first, f.ids())
    }

    @Test
    fun dragThenReconcileIsStable() {
        val f = fx()
        f.qm.reconcile()
        f.qm.move(f.episodes[3], 1)
        val arranged = f.ids()
        f.qm.reconcile()
        f.qm.reconcile()
        assertEquals(arranged, f.ids())
    }

    // ---------------------------------------------------------------- advancing

    @Test
    fun markPlayedAndAdvance() {
        val f = fx()
        f.qm.reconcile()
        val next = f.qm.markPlayedAndAdvance(f.episodes[0])
        assertEquals(f.episodes[1], next?.id)
        assertFalse(f.episodes[0] in f.ids())
        assertEquals("played", f.lib.episode(f.episodes[0]).state)
    }

    /**
     * The real-world bug this guards: a back-catalog episode (locally
     * 'archived') started on the phone — the position synced, but the episode
     * never entered the queue.
     */
    @Test
    fun archivedInProgressFromPhoneQueues() {
        val lib = Library()
        val podcast = lib.addPodcast()
        val id = lib.makeEpisode(podcast, 20, state = "archived", position = 847, total = 3555, updatedAt = 1_789_000_000)
        lib.queue.reconcile()
        assertTrue(id in lib.queueIds())
    }

    /** A gpodder 'download' action (another device queued it) must surface here. */
    @Test
    fun inboxFromDownloadActionQueues() {
        val lib = Library()
        val podcast = lib.addPodcast()
        val id = lib.makeEpisode(podcast, 21, state = "inbox")
        lib.queue.reconcile()
        assertTrue(id in lib.queueIds())
    }

    @Test
    fun archivedUntouchedStaysOut() {
        val lib = Library()
        val podcast = lib.addPodcast()
        val id = lib.makeEpisode(podcast, 22, state = "archived")
        lib.queue.reconcile()
        assertFalse(id in lib.queueIds())
    }

    // ---------------------------------------------------------------- gpodder

    /**
     * Marking played here must reach the phone's other apps: a play action with
     * position == total goes to the outbox.
     */
    @Test
    fun markPlayedEnqueuesGpodderAction() {
        val lib = Library()
        val podcast = lib.addPodcast()
        val id = lib.makeEpisode(podcast, 30, total = 3000)
        lib.queue.reconcile()
        lib.queue.markPlayedAndAdvance(id)
        val actions = lib.repo.outboxActions()
        assertTrue(
            actions.any { it.action == "play" && it.position == 3000L && it.total == 3000L },
            "expected a completed play action, got $actions",
        )
        assertEquals(1, lib.syncRequests)
    }

    /**
     * Playback marks state='played' and enqueues its own action before
     * markPlayedAndAdvance runs — there must be no second action from here.
     */
    @Test
    fun playbackFinishEnqueuesNoDuplicate() {
        val lib = Library()
        val podcast = lib.addPodcast()
        val id = lib.makeEpisode(podcast, 31, state = "played", total = 3000)
        lib.queue.markPlayedAndAdvance(id)
        assertEquals(emptyList(), lib.repo.outboxActions())
    }

    /**
     * 'Mark unplayed': progress reset, back in rotation, and a gpodder 'new'
     * action queued so the other devices reset too.
     */
    @Test
    fun markUnplayedResetsAndRequeues() {
        val lib = Library()
        val podcast = lib.addPodcast()
        val id = lib.makeEpisode(podcast, 40, state = "played", position = 3000, total = 3000)
        lib.queue.markUnplayed(id)

        val episode = lib.episode(id)
        assertEquals("inbox", episode.state)
        assertEquals(0L, episode.position_secs)
        assertTrue(id in lib.queueIds()) // inbox qualifies for the queue
        assertTrue(lib.repo.outboxActions().any { it.action == "new" })
    }

    @Test
    fun markUnplayedClearsExclusion() {
        val f = fx()
        f.qm.reconcile()
        f.qm.remove(f.episodes[0]) // user excluded it
        f.lib.setEpisode(f.episodes[0], state = "played")
        f.qm.markUnplayed(f.episodes[0]) // explicit reset overrides the exclusion
        assertTrue(f.episodes[0] in f.ids())
    }

    // ---------------------------------------------------------------- front-of-queue
    //
    // The daily-show case: a podcast set to 'front' should own the top slot each
    // time it publishes, without shoving aside what is playing or pinned.

    @Test
    fun frontPodcastTakesTheTopSlot() {
        val f = fx()
        val daily = f.lib.addDaily()
        f.qm.reconcile()
        assertEquals(f.episodes, f.ids())

        val monday = f.lib.makeEpisode(daily, 1, pubDate = 1_800_000_000)
        f.qm.reconcile()
        assertEquals(listOf(monday) + f.episodes, f.ids())
    }

    @Test
    fun eachMorningEpisodeLandsAboveTheLast() {
        val f = fx()
        val daily = f.lib.addDaily()
        val monday = f.lib.makeEpisode(daily, 1, pubDate = 1_800_000_000)
        f.qm.reconcile()
        val tuesday = f.lib.makeEpisode(daily, 2, pubDate = 1_800_086_400)
        f.qm.reconcile()
        // yesterday's unplayed episode stays, but today's is what you see first
        assertEquals(listOf(tuesday, monday), f.ids().take(2))
    }

    @Test
    fun frontEpisodesDoNotDisplaceThePlayingOne() {
        val f = fx()
        val daily = f.lib.addDaily()
        f.qm.reconcile()
        f.qm.playingEpisodeId = f.episodes[0]
        val today = f.lib.makeEpisode(daily, 1, pubDate = 1_800_000_000)
        f.qm.reconcile()
        assertEquals(f.episodes[0], f.ids()[0]) // still playing
        assertEquals(today, f.ids()[1])         // up next
    }

    @Test
    fun frontEpisodesDoNotDisplaceAPin() {
        val f = fx()
        val daily = f.lib.addDaily()
        f.qm.reconcile()
        f.qm.move(f.episodes[3], 0) // user pinned ep4 to the top
        val today = f.lib.makeEpisode(daily, 1, pubDate = 1_800_000_000)
        f.qm.reconcile()
        assertEquals(f.episodes[3], f.ids()[0])
        assertEquals(today, f.ids()[1])
    }

    /** A half-listened episode floats to the top; this morning's news outranks it. */
    @Test
    fun frontBeatsAnInProgressEpisode() {
        val f = fx()
        val daily = f.lib.addDaily()
        f.qm.reconcile()
        f.lib.setEpisode(f.episodes[2], position = 300, total = 3000, updatedAt = 1_799_999_999)
        val today = f.lib.makeEpisode(daily, 1, pubDate = 1_800_000_000)
        f.qm.reconcile()
        assertEquals(listOf(today, f.episodes[2]), f.ids().take(2))
    }

    @Test
    fun otherPodcastsStillAppend() {
        val f = fx()
        val daily = f.lib.addDaily()
        f.lib.makeEpisode(daily, 1, pubDate = 1_800_000_000)
        f.qm.reconcile()
        val newest = f.lib.makeEpisode(f.podcast, 9, pubDate = 1_900_000_000)
        f.qm.reconcile()
        assertEquals(newest, f.ids().last())
    }

    @Test
    fun theGlobalDefaultIsTheBottom() {
        val f = fx()
        val extra = f.lib.makeEpisode(f.podcast, 9, pubDate = 1_900_000_000)
        f.qm.reconcile()
        assertEquals(extra, f.ids().last())
    }

    @Test
    fun aFrontPodcastCanBeSetBackToTheBottom() {
        val f = fx()
        val daily = f.lib.addDaily()
        f.qm.reconcile()
        f.lib.repo.updatePodcastSettings(daily) { it.copy(autoQueuePosition = "back") }
        val today = f.lib.makeEpisode(daily, 1, pubDate = 1_800_000_000)
        f.qm.reconcile()
        assertEquals(today, f.ids().last())
    }

    // ---------------------------------------------------------------- intent
    //
    // Not in the desktop suite: these guard the wiring that carries a user's
    // decision to the other devices. A queue op that changes the local queue and
    // forgets the intent looks perfectly correct here and silently never syncs.

    @Test
    fun everyUserOpRecordsIntentAndNotifies() {
        val f = fx()
        f.qm.reconcile()
        val before = f.lib.intentChanges

        f.qm.remove(f.episodes[0])
        f.qm.add(f.episodes[0])
        f.qm.move(f.episodes[1], 0)
        f.qm.pin(f.episodes[2])
        f.qm.releaseToAuto(f.episodes[3])

        assertEquals(before + 5, f.lib.intentChanges, "each op must notify peers exactly once")
        for (id in f.episodes.take(4)) {
            assertNotNull(
                f.lib.db.queueQueries.intentFor(id).executeAsOneOrNull(),
                "episode $id should carry an intent",
            )
        }
    }

    @Test
    fun reconcileAloneNeverRecordsIntent() {
        val f = fx()
        f.qm.reconcile()
        f.lib.setEpisode(f.episodes[4], position = 300, total = 3000, updatedAt = 1_700_999_999)
        f.qm.reconcile()
        // A derived reshuffle is not an opinion worth pushing at a peer.
        assertEquals(0, f.lib.intentChanges)
        assertEquals(0, f.lib.db.queueQueries.intentsForSnapshot().executeAsList().size)
    }

    /** Adding what is already queued changes nothing, and must announce nothing. */
    @Test
    fun addingAnAlreadyQueuedEpisodeIsANoOp() {
        val f = fx()
        f.qm.reconcile()
        val order = f.ids()
        val before = f.lib.intentChanges

        f.qm.add(f.episodes[2])

        assertEquals(order, f.ids())
        assertEquals(before, f.lib.intentChanges)
    }

    @Test
    fun aPinIsRecordedAsIntent() {
        val f = fx()
        f.qm.reconcile()
        f.qm.pin(f.episodes[2])
        val intent = f.lib.db.queueQueries.intentFor(f.episodes[2]).executeAsOne()
        assertEquals("queued", intent.intent)
        assertEquals(1L, intent.pinned)
        assertEquals("manual", intent.origin)
    }
}
