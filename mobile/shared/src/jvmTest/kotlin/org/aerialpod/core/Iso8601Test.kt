package org.aerialpod.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gpodder.net action timestamps.
 *
 * Expected values come from the desktop's own
 * `datetime.fromtimestamp(t, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")`, so a
 * drift in the calendar arithmetic shows up here rather than as actions the
 * server silently orders wrong.
 */
class Iso8601Test {

    @Test
    fun matchesTheDesktopsFormat() {
        val cases = listOf(
            0L to "1970-01-01T00:00:00",
            1_700_000_000L to "2023-11-14T22:13:20",
            1_789_000_000L to "2026-09-10T00:26:40",
            951_782_400L to "2000-02-29T00:00:00",   // leap day in a 400-year leap century
            4_102_444_800L to "2100-01-01T00:00:00", // 2100 is not a leap year
            1_583_020_800L to "2020-03-01T00:00:00", // day after a leap day
        )
        for ((epoch, expected) in cases) {
            assertEquals(expected, iso8601Utc(epoch), "epoch $epoch")
        }
    }

    @Test
    fun everyFieldIsZeroPadded() {
        // 2001-02-03T04:05:06 — every component a single digit before padding.
        assertEquals("2001-02-03T04:05:06", iso8601Utc(981_173_106L))
    }
}
