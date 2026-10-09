package org.aerialpod.core.lan

import org.aerialpod.core.ads.AdSpan
import org.aerialpod.core.ads.adsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ad spans crossing the wire — the cases of the desktop's tests/test_ads_merge.py. */
class AdsMergeTest {

    private val now = 1_700_000_000L
    private val spans = listOf(listOf(60_000.0, 120_000.0, 0.93))

    private fun record(guid: String, at: Long, by: String = "laptop",
                       spans: List<List<Double>> = this.spans, durationMs: Long = 3_600_000) =
        AdsRecord(feed = FEED, guid = guid, media = "", durationMs = durationMs,
                  spans = spans, model = "test", updatedAt = at, updatedBy = by)

    @Test
    fun spansFromADesktopLandAndReadBack() {
        val phone = Device("phone")
        val counts = phone.sync.mergeSnapshot(Snapshot(ads = listOf(record("guid-1-1", now))))
        assertEquals(1, counts.ads)
        val ads = phone.db.adsFor(1)!!
        assertEquals(3_600_000L, ads.durationMs)
        assertEquals(listOf(AdSpan(60_000, 120_000, 0.93)), ads.spans)
    }

    @Test
    fun newerLocalRowWinsAndTravelsBack() {
        val phone = Device("phone", clock = { now })
        phone.sync.mergeSnapshot(Snapshot(ads = listOf(record("guid-1-1", now - 10, spans = emptyList()))))
        // An older detection arriving later must not resurrect the spans.
        assertEquals(0, phone.sync.mergeSnapshot(Snapshot(ads = listOf(record("guid-1-1", now - 20)))).ads)
        assertEquals(emptyList(), phone.db.adsFor(1)!!.spans)

        // The phone echoes what it holds, so a second desktop learns it too.
        val echoed = phone.sync.buildSnapshot().ads.single()
        assertEquals(now - 10, echoed.updatedAt)
        assertEquals("laptop", echoed.updatedBy)
    }

    @Test
    fun malformedSpansAreRejectedWhole() {
        val phone = Device("phone")
        val bad = listOf(
            record("guid-1-1", now, spans = listOf(listOf(0.0, 100.0, 0.9), listOf(500.0, 400.0, 0.9))),
            record("guid-1-2", now, spans = listOf(listOf(0.0, 100.0, 7.0))),
            record("guid-1-3", now, spans = listOf(listOf(0.0, 100.0))),
            record("guid-1-4", now, durationMs = 0),
        )
        assertEquals(0, phone.sync.mergeSnapshot(Snapshot(ads = bad)).ads)
        for (id in 1L..4L) assertNull(phone.db.adsFor(id))
    }

    @Test
    fun oldRowsForUnqueuedEpisodesStayHome() {
        val phone = Device("phone", clock = { now })
        phone.sync.mergeSnapshot(Snapshot(ads = listOf(record("guid-1-2", now - 60 * 86_400))))
        assertEquals(emptyList(), phone.sync.buildSnapshot().ads)
    }
}
