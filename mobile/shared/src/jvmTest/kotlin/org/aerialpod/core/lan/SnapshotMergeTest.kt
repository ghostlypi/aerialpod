package org.aerialpod.core.lan

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The merge rules, on real databases — the Kotlin counterpart of the desktop's
 * `tests/test_lan_merge.py`, case for case.
 *
 * Two installs with identical libraries exchange snapshots, and each test
 * checks what crossing the wire does and doesn't change. Where the desktop
 * drives the setup through `QueueManager`, these write the intent directly:
 * the queue manager is a later step, and what is under test here is the merge.
 */
class SnapshotMergeTest {

    private fun pair(): Pair<Device, Device> = Device("aaaa1111") to Device("bbbb2222")

    // ---------------------------------------------------------------- queue order

    @Test
    fun queueOrderTravels() {
        // The whole point of the feature: gpodder.net cannot carry this.
        val (a, b) = pair()
        a.setIntent(1, "queued", at = 1000, by = "aaaa1111", position = 1024, pinned = 1)
        a.setIntent(5, "queued", at = 1000, by = "aaaa1111", position = 2048)
        a.setIntent(3, "queued", at = 1000, by = "aaaa1111", position = 3072)
        val expected = a.queueIds()

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals(expected, b.queueIds())
        assertEquals(listOf(1L, 5L, 3L), b.queueIds())
    }

    @Test
    fun aPinTravels() {
        val (a, b) = pair()
        a.setIntent(4, "queued", at = 1000, position = 1024, pinned = 1, origin = "manual")

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        val row = b.queueRow(4)
        assertEquals(1L, row?.pinned)
        assertEquals("manual", row?.origin)
    }

    @Test
    fun manualRemovalTravels() {
        val (a, b) = pair()
        a.setIntent(2, "excluded", at = 1000)

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertFalse(2L in b.queueIds())
        assertTrue(b.isExcluded(2))
    }

    @Test
    fun mergedQueueKeepsTheGapScheme() {
        val (a, b) = pair()
        a.setIntent(5, "queued", at = 1000, position = 1024)
        a.setIntent(3, "queued", at = 1000, position = 2048)

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertContentEquals(listOf(QUEUE_GAP, QUEUE_GAP * 2), b.queuePositions())
    }

    /**
     * Two devices that numbered independently can send colliding positions, and
     * the tie-break has to be something both ends agree on — never the local
     * rowid, which is assigned in feed-fetch order and differs per device.
     */
    @Test
    fun collidingPositionsRenumberDeterministically() {
        val (a, b) = pair()
        a.setIntent(2, "queued", at = 1000, position = 1024)
        a.setIntent(4, "queued", at = 1000, position = 1024)

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        val first = b.queueIds()

        val c = Device("cccc3333")
        c.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals(first, c.queueIds(), "two devices must resolve the collision alike")
        assertContentEquals(listOf(QUEUE_GAP, QUEUE_GAP * 2), b.queuePositions())
    }

    // ---------------------------------------------------------------- last writer wins

    @Test
    fun newerIntentWins() {
        val (a, b) = pair()
        b.setIntent(2, "excluded", at = 1000)
        a.setIntent(2, "queued", at = 2000, position = 1024)

        val counts = b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals(1, counts.intents)
        assertTrue(2L in b.queueIds())
    }

    @Test
    fun olderIntentLoses() {
        val (a, b) = pair()
        b.setIntent(2, "queued", at = 2000, position = 1024)
        a.setIntent(2, "excluded", at = 1000)

        val counts = b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals(0, counts.intents)
        assertTrue(2L in b.queueIds())
        assertFalse(b.isExcluded(2))
    }

    /**
     * The reason intent is recorded rather than inferred: restoring an episode
     * clears an exclusion, and a peer still holding the old one must not throw
     * the episode straight back out.
     */
    @Test
    fun restoringAnEpisodeBeatsAPeersOlderExclusion() {
        val (a, b) = pair()
        b.setIntent(3, "excluded", at = 1000)
        a.setIntent(3, "excluded", at = 1000)
        a.setIntent(3, "queued", at = 2000, position = 1024) // user restores it here, later

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertFalse(b.isExcluded(3))
        assertTrue(3L in b.queueIds())

        // …and the stale exclusion travelling the other way changes nothing.
        val counts = a.sync.mergeSnapshot(b.sync.buildSnapshot())
        assertEquals(0, counts.intents)
        assertFalse(a.isExcluded(3))
    }

    /** Two devices resolving one conflict must never disagree, or they ping-pong. */
    @Test
    fun tiesBreakTheSameWayOnBothSides() {
        val (a, b) = pair()
        a.setIntent(2, "queued", at = 5000, by = "aaaa", position = 1024)
        b.setIntent(2, "excluded", at = 5000, by = "zzzz")

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        val queuedOnB = 2L in b.queueIds()
        a.sync.mergeSnapshot(b.sync.buildSnapshot())
        val queuedOnA = 2L in a.queueIds()

        assertEquals(queuedOnA, queuedOnB) // both landed on 'zzzz', whichever way it went
        assertFalse(queuedOnA)
    }

    @Test
    fun mergeIsIdempotent() {
        val (a, b) = pair()
        a.setIntent(5, "queued", at = 1000, position = 1024)
        val snapshot = a.sync.buildSnapshot()

        val first = b.sync.mergeSnapshot(snapshot)
        val second = b.sync.mergeSnapshot(snapshot)
        assertEquals(1, first.intents)
        assertEquals(0, second.intents, "a re-merged snapshot must be a no-op")
    }

    // ---------------------------------------------------------------- positions

    @Test
    fun newerPositionWins() {
        val (a, b) = pair()
        b.setPosition(2, position = 100, total = 3600, at = 1000)
        a.setPosition(2, position = 900, total = 3600, at = 2000)

        val counts = b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals(1, counts.positions)
        assertEquals(900L, b.episode(2).position_secs)
        assertEquals(2000L, b.episode(2).position_updated_at)
    }

    @Test
    fun olderPositionIgnored() {
        val (a, b) = pair()
        b.setPosition(2, position = 900, total = 3600, at = 2000)
        a.setPosition(2, position = 100, total = 3600, at = 1000)

        val counts = b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals(0, counts.positions)
        assertEquals(900L, b.episode(2).position_secs)
    }

    /**
     * Episode state is left alone on purpose: gpodder.net already carries
     * played/new between devices, and this path must not race it.
     */
    @Test
    fun mergeAdoptsPlayedOnlyWhenTheSenderSaysSo() {
        // The contract changed deliberately. It used to be "state never crosses
        // the wire, gpodder carries it" — which is false in the one case that
        // matters: an episode abandoned partway and then marked played reads as
        // in progress to the receiver, and lands back in its queue.
        val (a, b) = pair()
        a.setPosition(2, position = 3599, total = 3600, at = 2000)
        a.setEpisodeState(2, "played")

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals("played", b.episode(2).state, "the sender said it is done")
        assertEquals(3599L, b.episode(2).position_secs)
    }

    @Test
    fun mergeLeavesEpisodeStateAloneWhenTheSenderIsStillListening() {
        val (a, b) = pair()
        a.setPosition(2, position = 1200, total = 3600, at = 2000)

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals("new", b.episode(2).state, "a bare position says nothing about state")
        assertEquals(1200L, b.episode(2).position_secs)
    }

    /** A peer reporting total=0 must not clobber a known-good duration. */
    @Test
    fun aZeroTotalDoesNotClobberAKnownDuration() {
        val (a, b) = pair()
        b.setPosition(2, position = 100, total = 3600, at = 1000)
        a.setPosition(2, position = 900, total = 0, at = 2000)

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals(900L, b.episode(2).position_secs)
        assertEquals(3600L, b.episode(2).total_secs)
    }

    @Test
    fun livePositionPushApplies() {
        val (a, b) = pair()
        a.setPosition(4, position = 42, total = 1200, at = 3000)

        val message = a.sync.positionMessageFor(4)!!
        assertTrue(b.sync.applyPositionMessage(message))
        assertEquals(42L, b.episode(4).position_secs)

        // …and re-applying it changes nothing.
        assertFalse(b.sync.applyPositionMessage(message))
    }

    // ---------------------------------------------------------------- settings

    @Test
    fun podcastSettingsTravel() {
        val (a, b) = pair()
        a.setSettings(customTitle = "My Show", speed = 1.5, at = 2000, by = "aaaa1111")

        val counts = b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals(1, counts.settings)
        assertEquals("My Show", b.settings()?.custom_title)
        assertEquals(1.5, b.settings()?.playback_speed)
    }

    @Test
    fun newerSettingsWin() {
        val (a, b) = pair()
        b.setSettings(customTitle = "Old", at = 1000, by = "bbbb2222")
        a.setSettings(customTitle = "New", at = 2000, by = "aaaa1111")

        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals("New", b.settings()?.custom_title)
    }

    @Test
    fun olderSettingsLose() {
        val (a, b) = pair()
        b.setSettings(customTitle = "Keep", at = 2000, by = "bbbb2222")
        a.setSettings(customTitle = "Stale", at = 1000, by = "aaaa1111")

        val counts = b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals(0, counts.settings)
        assertEquals("Keep", b.settings()?.custom_title)
    }

    /** A daily show's 'front' placement is a setting like any other. */
    @Test
    fun autoQueuePositionTravels() {
        val (a, b) = pair()
        a.setSettings(autoQueuePosition = "front", at = 2000, by = "aaaa1111")
        b.sync.mergeSnapshot(a.sync.buildSnapshot())
        assertEquals("front", b.settings()?.auto_queue_position)
    }

    // ---------------------------------------------------------------- resolution

    /**
     * Ad-injecting CDNs rewrite the enclosure URL per listener, so the same
     * episode is a different URL on each device. GUID is what makes them agree.
     */
    @Test
    fun episodesMatchByGuidWhenTheCdnRotatesTheUrl() {
        val (a, b) = pair()
        // b's copy of episode 2 carries a per-listener URL that shares no path
        // with a's, so only the GUID can connect the two.
        b.db.episodesQueries.updateMediaUrl(
            "https://dts.podtrac.com/redirect.mp3/cdn.b.example/xyz/two.mp3", 2,
        )

        a.setPosition(2, position = 555, total = 3600, at = 2000)
        val counts = b.sync.mergeSnapshot(a.sync.buildSnapshot())

        assertEquals(1, counts.positions, "GUID should have resolved it")
        assertEquals(555L, b.episode(2).position_secs)
    }

    @Test
    fun recordsForUnknownEpisodesAreSkipped() {
        val (a, _) = pair()
        val stranger = Snapshot(
            positions = listOf(
                PositionRecord(
                    feed = "https://elsewhere.example/feed.xml",
                    guid = "nope",
                    media = "https://elsewhere.example/ep.mp3",
                    position = 10,
                    total = 100,
                    updatedAt = 9999,
                )
            )
        )
        val counts = a.sync.mergeSnapshot(stranger)
        assertEquals(0, counts.positions)
    }

    @Test
    fun unsupportedSnapshotVersionIsRejected() {
        val (a, _) = pair()
        assertFailsWith<IllegalArgumentException> {
            a.sync.mergeSnapshot(Snapshot(v = 99))
        }
    }

    // ---------------------------------------------------------------- version

    @Test
    fun replicatedVersionIsZeroOnAnUntouchedLibrary() {
        val a = Device("aaaa1111")
        assertEquals(0L, a.sync.replicatedVersion())
    }

    @Test
    fun replicatedVersionMovesForEveryReplicatedSection() {
        val a = Device("aaaa1111")
        assertEquals(0L, a.sync.replicatedVersion())

        a.setIntent(1, "queued", at = 1000, position = 1024)
        assertEquals(1000L, a.sync.replicatedVersion())

        a.setSettings(customTitle = "x", at = 2000, by = "aaaa1111")
        assertEquals(2000L, a.sync.replicatedVersion())

        a.setPosition(2, position = 5, total = 100, at = 3000)
        assertEquals(3000L, a.sync.replicatedVersion())
    }

    @Test
    fun aFreshDeviceHasNoIntentsToSend() {
        val a = Device("aaaa1111")
        val snapshot = a.sync.buildSnapshot()
        assertTrue(snapshot.intents.isEmpty())
        assertTrue(snapshot.positions.isEmpty())
        assertNull(a.settings())
    }
}
