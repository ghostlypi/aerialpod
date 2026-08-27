package org.aerialpod.core.downloads

import org.aerialpod.core.queue.Library
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `apply_policy` from `core/downloads.py`, case for case.
 *
 * Every test here is about the same question from a different side: what may be
 * deleted. A policy that fetches too little wastes bandwidth later; one that
 * evicts too much throws away the episode the user is about to listen to on a
 * train with no signal.
 */
class DownloadPolicyTest {

    private class Fx(aheadN: String = "2") {
        val lib = Library()
        val repo = lib.repo
        val policy = DownloadPolicy(repo)
        val podcast = lib.addPodcast()

        init {
            repo.setDownloadAhead(aheadN)
        }

        fun queued(n: Int): List<Long> = (1..n).map { index ->
            val id = lib.makeEpisode(podcast, index)
            lib.queue.add(id)
            id
        }

        fun downloaded(id: Long, keep: Boolean = false) {
            repo.setDownloadState(id, "done", "/media/$id.mp3")
            if (keep) repo.setKeepDownload(id, true)
        }
    }

    @Test
    fun fetchesTheFirstNQueueItemsAndNothingElse() {
        val fx = Fx(aheadN = "2")
        val ids = fx.queued(5)

        val plan = fx.policy.plan()

        assertEquals(ids.take(2), plan.fetch.map { it.id })
        assertTrue(plan.evict.isEmpty())
    }

    @Test
    fun evictsADownloadThatHasFallenOutOfTheWindow() {
        val fx = Fx(aheadN = "1")
        val ids = fx.queued(3)
        fx.downloaded(ids[2])

        val plan = fx.policy.plan()

        assertEquals(listOf(ids[2]), plan.evict.map { it.id })
    }

    @Test
    fun neverEvictsSomethingTheUserPinned() {
        // `keep_download` is the user saying "I am taking this on a plane".
        val fx = Fx(aheadN = "1")
        val ids = fx.queued(3)
        fx.downloaded(ids[2], keep = true)

        val plan = fx.policy.plan()

        assertTrue(plan.evict.isEmpty(), "a pinned download outranks the window")
    }

    @Test
    fun neverEvictsOrRefetchesSomethingInFlight() {
        val fx = Fx(aheadN = "1")
        val ids = fx.queued(3)
        fx.repo.setDownloadState(ids[2], "downloading")

        val plan = fx.policy.plan(active = setOf(ids[2], ids[0]))

        assertTrue(plan.evict.none { it.id == ids[2] }, "let the transfer finish")
        assertTrue(plan.fetch.none { it.id == ids[0] }, "and do not start it twice")
    }

    @Test
    fun anInterruptedTransferIsEvictableOnceNothingIsCarryingIt() {
        // A process death leaves a row claiming 'downloading' with no transfer
        // behind it. The next pass has to be able to clear it, or that episode
        // is stuck forever — neither downloaded nor re-fetchable.
        val fx = Fx(aheadN = "1")
        val ids = fx.queued(3)
        fx.repo.setDownloadState(ids[2], "downloading")

        val plan = fx.policy.plan(active = emptySet())

        assertEquals(listOf(ids[2]), plan.evict.map { it.id })
    }

    @Test
    fun aDeadTransferInsideTheWindowIsPickedUpAgain() {
        // The stranding case: a crash leaves the queue head at 'downloading'.
        // It is not 'none', so a fetch-only-none policy skips it; it is still
        // wanted, so eviction skips it too. It would never download again.
        val fx = Fx(aheadN = "1")
        val ids = fx.queued(3)
        fx.repo.setDownloadState(ids[0], "downloading")

        val plan = fx.policy.plan(active = emptySet())

        assertEquals(listOf(ids[0]), plan.fetch.map { it.id })
        assertTrue(plan.evict.none { it.id == ids[0] })
    }

    @Test
    fun zeroMeansStreamEverything() {
        val fx = Fx(aheadN = "0")
        val ids = fx.queued(3)
        fx.downloaded(ids[0])

        val plan = fx.policy.plan()

        assertTrue(plan.fetch.isEmpty())
        assertEquals(listOf(ids[0]), plan.evict.map { it.id }, "and clear what is already there")
    }

    @Test
    fun aNegativeSettingIsTreatedAsOffRatherThanThrowing() {
        // `app_state` is shared with the desktop and editable by hand, so the
        // count is not guaranteed to be sane. `take(-1)` throws, which would
        // take the whole download pass down over a bad row.
        val fx = Fx(aheadN = "-1")
        val ids = fx.queued(3)
        fx.downloaded(ids[0])

        val plan = fx.policy.plan()

        assertTrue(plan.fetch.isEmpty())
        assertEquals(listOf(ids[0]), plan.evict.map { it.id })
    }

    // ------------------------------------------------------------ resolution

    @Test
    fun fractionsAreOfTheQueueAndRoundUp() {
        // A queue of 11: a quarter is 3, a third is 4, a half is 6.
        assertEquals(3, DownloadPolicy.resolve("1/4", 11))
        assertEquals(4, DownloadPolicy.resolve("1/3", 11))
        assertEquals(6, DownloadPolicy.resolve("1/2", 11))
        assertEquals(11, DownloadPolicy.resolve("all", 11))
    }

    @Test
    fun aFractionOfASmallQueueIsStillAtLeastOne() {
        // "a quarter of my queue" meaning nothing at all for a three-item queue
        // would be a surprising reading of the setting.
        assertEquals(1, DownloadPolicy.resolve("1/4", 1))
        assertEquals(1, DownloadPolicy.resolve("1/4", 3))
        assertEquals(1, DownloadPolicy.resolve("1/2", 2))
    }

    @Test
    fun aFractionNeverAsksForMoreThanTheQueueHolds() {
        assertEquals(4, DownloadPolicy.resolve("1/1", 4))
        assertEquals(4, DownloadPolicy.resolve("all", 4))
        assertEquals(5, DownloadPolicy.resolve("5", 5))
        // A top-heavy fraction is not one of the offered choices, but the value
        // is hand-editable and "three halves of the queue" has to clamp.
        assertEquals(10, DownloadPolicy.resolve("3/2", 10))
        assertEquals(10, DownloadPolicy.resolve("99/1", 10))
    }

    @Test
    fun aNegativeFractionIsOffRatherThanNegative() {
        // Without the guard this returns -1, and a negative count reaching
        // `take()` throws.
        assertEquals(0, DownloadPolicy.resolve("-1/4", 10))
        assertEquals(0, DownloadPolicy.resolve("1/-4", 10))
    }

    @Test
    fun anEmptyQueueResolvesToNothingWhateverTheSetting() {
        for (choice in DownloadPolicy.CHOICES) {
            assertEquals(0, DownloadPolicy.resolve(choice, 0), "for $choice")
        }
    }

    @Test
    fun countsStillWork() {
        assertEquals(0, DownloadPolicy.resolve("0", 11))
        assertEquals(1, DownloadPolicy.resolve("1", 11))
        assertEquals(5, DownloadPolicy.resolve("5", 11))
    }

    @Test
    fun rubbishFallsBackRatherThanThrowing() {
        // The value lives in `app_state`, which is shared with the desktop and
        // editable by hand.
        assertEquals(1, DownloadPolicy.resolve("banana", 10))
        assertEquals(1, DownloadPolicy.resolve("1/banana", 10))
        assertEquals(1, DownloadPolicy.resolve("", 10))
        assertEquals(0, DownloadPolicy.resolve("-3", 10))
        assertEquals(0, DownloadPolicy.resolve("0/4", 10))
        assertEquals(11, DownloadPolicy.resolve("  ALL  ", 11))
    }

    @Test
    fun aFractionSettingDrivesTheRealPlan() {
        val fx = Fx(aheadN = "1/3")
        val ids = fx.queued(9)

        val plan = fx.policy.plan()

        assertEquals(ids.take(3), plan.fetch.map { it.id }, "a third of nine is three")
    }

    @Test
    fun theWholeQueueMeansEveryEpisodeInIt() {
        val fx = Fx(aheadN = "all")
        val ids = fx.queued(4)

        assertEquals(ids, fx.policy.plan().fetch.map { it.id })
    }

    @Test
    fun anAlreadyDownloadedHeadIsNotFetchedAgain() {
        val fx = Fx(aheadN = "2")
        val ids = fx.queued(3)
        fx.downloaded(ids[0])

        val plan = fx.policy.plan()

        assertEquals(listOf(ids[1]), plan.fetch.map { it.id })
        assertTrue(plan.evict.isEmpty())
    }

    @Test
    fun aShortQueueAsksForNoMoreThanItHas() {
        val fx = Fx(aheadN = "5")
        val ids = fx.queued(2)

        val plan = fx.policy.plan()

        assertEquals(ids, plan.fetch.map { it.id })
    }

    @Test
    fun anEmptyQueueEvictsEverythingUnpinned() {
        val fx = Fx(aheadN = "3")
        val ids = fx.queued(2)
        fx.downloaded(ids[0])
        fx.downloaded(ids[1], keep = true)
        ids.forEach { fx.lib.queue.remove(it) }

        val plan = fx.policy.plan()

        assertEquals(listOf(ids[0]), plan.evict.map { it.id })
        assertTrue(plan.fetch.isEmpty())
    }
}
