package org.aerialpod.core.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.aerialpod.core.queue.Library as TestLibrary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The skip lengths, written and read back.
 *
 * Worth its own file because the setting crosses three pieces that each pick
 * their own encoding: [Repo.setState] writes it, [Repo.stateLong] reads it for
 * the player, and [Library.stateLong] reads it again — through a different
 * parse — for the chips. Agreeing on the value is the whole contract; a chip
 * that appears to do nothing is what a disagreement looks like from the sofa.
 */
class SkipSettingTest {

    private class Fx {
        val lib = TestLibrary()
        val repo = lib.repo
        val reactive = Library(repo, Dispatchers.Unconfined)
    }

    @Test
    fun `unset falls back to the default`() {
        val fx = Fx()
        assertEquals(30, fx.repo.stateLong(Repo.SKIP_FWD_SECS, 30))
        assertEquals(10, fx.repo.stateLong(Repo.SKIP_BACK_SECS, 10))
    }

    @Test
    fun `what the chip writes is what the player reads`() {
        val fx = Fx()
        // The two directions are stored under different keys, and getting that
        // wrong would make one chip silently move the other.
        fx.repo.setState(Repo.SKIP_FWD_SECS, 45L)
        fx.repo.setState(Repo.SKIP_BACK_SECS, 15L)

        assertEquals(45, fx.repo.stateLong(Repo.SKIP_FWD_SECS, 30))
        assertEquals(15, fx.repo.stateLong(Repo.SKIP_BACK_SECS, 10))
    }

    @Test
    fun `the chips see the write through the flow`() = runTest {
        val fx = Fx()
        assertEquals(30, fx.reactive.stateLong(Repo.SKIP_FWD_SECS, 30).first())

        fx.repo.setState(Repo.SKIP_FWD_SECS, 5L)

        // Not merely stored — re-read. The setting is rendered from this flow,
        // so a write the query does not notify on leaves the chip unmoved and
        // the tap looking like it was missed.
        assertEquals(5, fx.reactive.stateLong(Repo.SKIP_FWD_SECS, 30).first())
    }

    @Test
    fun `a value the desktop set off-list survives a round trip`() {
        val fx = Fx()
        // The desktop's spin box runs 5..300, so it can hold a number no chip
        // offers. Reading it back as the default would quietly retune the
        // player on a device the user never touched.
        fx.repo.setState(Repo.SKIP_FWD_SECS, 23L)

        assertEquals(23, fx.repo.stateLong(Repo.SKIP_FWD_SECS, 30))
    }

    @Test
    fun `a junk value falls back rather than throwing`() = runTest {
        val fx = Fx()
        fx.repo.setState(Repo.SKIP_FWD_SECS, "not a number")

        assertEquals(30, fx.repo.stateLong(Repo.SKIP_FWD_SECS, 30))
        assertEquals(30, fx.reactive.stateLong(Repo.SKIP_FWD_SECS, 30).first())
    }
}
