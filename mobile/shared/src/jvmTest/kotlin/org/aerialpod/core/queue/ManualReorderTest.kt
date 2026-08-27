package org.aerialpod.core.queue

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A manual reorder has to survive reconcile.
 *
 * Reported from real use: reordering on the phone did not stick on the desktop.
 * The intents replicated correctly — the receiving reconcile undid them. An
 * in-progress episode "floats" to the top, and the episode a user most wants to
 * move is usually one they are partway through, so moving it down was
 * impossible on either device.
 *
 * `move()` pins the row it moves, which is the queue's existing way of saying
 * "leave it where I put it". Reconcile now honours that — as its own comment
 * always claimed it did.
 */
class ManualReorderTest {

    /**
     * A queue built the way most queues are: auto-added by reconcile, so the
     * rows are unpinned. `add()` pins, because putting something in the queue
     * by hand is itself a placement — using it here would be testing the wrong
     * thing.
     */
    private fun library(): Triple<Library, Long, List<Long>> {
        val lib = Library()
        val podcast = lib.addPodcast()
        val ids = (1..3).map { n -> lib.makeEpisode(podcast, n) }
        lib.queue.reconcile()
        return Triple(lib, podcast, lib.queue.episodes().map { it.id })
    }

    @Test
    fun movingAnInProgressEpisodeToTheEndSticks() {
        val (lib, _, ids) = library()
        val started = ids[0]
        // Partway through — the case that used to float straight back up.
        lib.db.episodesQueries.setEpisodePosition(600, 3600, 1_700_000_500L, started)

        lib.queue.move(started, 2)
        lib.queue.reconcile()

        assertEquals(started, lib.queue.episodes().last().id, "it must stay where it was put")
    }

    @Test
    fun anInProgressEpisodeStillFloatsWhenNobodyPlacedIt() {
        // The handoff behaviour has to keep working: started elsewhere,
        // surfaces here. Only a row the user placed is exempt.
        val (lib, _, ids) = library()
        val started = ids[2]
        lib.db.episodesQueries.setEpisodePosition(600, 3600, 1_700_000_500L, started)

        lib.queue.reconcile()

        assertEquals(started, lib.queue.episodes().first().id, "in progress surfaces on its own")
    }

    @Test
    fun anEpisodeTheUserQueuedByHandDoesNotFloat() {
        // `add()` pins, so putting something in the queue is a placement too.
        val lib = Library()
        val podcast = lib.addPodcast()
        val auto = lib.makeEpisode(podcast, 1)
        lib.queue.reconcile()
        val placed = lib.makeEpisode(podcast, 9)
        lib.queue.add(placed)
        lib.db.episodesQueries.setEpisodePosition(600, 3600, 1_700_000_500L, placed)

        lib.queue.reconcile()

        assertEquals(
            placed, lib.queue.episodes().last().id,
            "it stays where it was put, even once it is in progress",
        )
    }

    @Test
    fun aReorderSurvivesRepeatedReconciles() {
        val (lib, _, ids) = library()
        lib.db.episodesQueries.setEpisodePosition(600, 3600, 1_700_000_500L, ids[0])

        lib.queue.move(ids[0], 2)
        repeat(5) { lib.queue.reconcile() }

        assertEquals(ids[0], lib.queue.episodes().last().id)
    }

    @Test
    fun movingAnEpisodeToTheFrontAlsoSticks() {
        val (lib, _, ids) = library()
        lib.db.episodesQueries.setEpisodePosition(600, 3600, 1_700_000_500L, ids[2])

        lib.queue.move(ids[0], 0)
        lib.queue.reconcile()

        assertEquals(ids[0], lib.queue.episodes().first().id)
    }
}
