package org.aerialpod.core.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same cases as the desktop's tests/test_ads_skip.py, so the two rules can't drift. */
class AdSkipTest {
    private val spans = listOf(AdSpan(60_000, 120_000, 0.9), AdSpan(300_000, 330_000, 0.5))

    @Test
    fun spansOnlyApplyToTheCopyThatWasAnalysed() {
        assertTrue(AdSkip.applies(3_600_000, 3_601_500))
        assertFalse(AdSkip.applies(3_600_000, 3_630_000))  // another copy: a 30 s ad was inserted
        assertFalse(AdSkip.applies(0, 3_600_000))
    }

    @Test
    fun playingIntoAnAdSkipsIt() = assertEquals(0, AdSkip.spanToSkip(spans, emptySet(), 59_500, 60_400))

    @Test
    fun resumingInsideAnAdSkipsIt() = assertEquals(0, AdSkip.spanToSkip(spans, emptySet(), null, 90_000))

    @Test
    fun seekingForwardIntoAnAdSkipsIt() = assertEquals(0, AdSkip.spanToSkip(spans, emptySet(), 10_000, 90_000))

    @Test
    fun seekingBackIntoAnAdDoesNot() = assertNull(AdSkip.spanToSkip(spans, emptySet(), 200_000, 90_000))

    @Test
    fun undoLetsTheAdPlay() = assertNull(AdSkip.spanToSkip(spans, setOf(0), 59_500, 60_400))

    @Test
    fun lowConfidenceSpansAreNeverSkipped() =
        assertNull(AdSkip.spanToSkip(spans, emptySet(), 299_500, 300_200))
}
