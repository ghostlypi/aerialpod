package org.aerialpod.android.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which skip chips get offered, given what the setting currently holds. */
class SkipChoicesTest {

    @Test
    fun `an on-list value offers the plain list`() {
        assertEquals(SKIP_CHOICES, skipChoices(30))
    }

    @Test
    fun `an off-list value joins the list, in order`() {
        // The desktop's spin box goes to 300, so 23 is reachable there. Without
        // this the chips would show nothing selected, which reads as "skip is
        // not configured" when in fact it is — just not to one of six numbers.
        assertEquals(listOf(5L, 10L, 15L, 23L, 30L, 45L, 60L), skipChoices(23))
    }

    @Test
    fun `the current value is always selectable`() {
        for (seconds in listOf(5L, 7L, 30L, 299L, 300L)) {
            assertTrue(seconds in skipChoices(seconds), "$seconds missing")
        }
    }

    @Test
    fun `no duplicates and no reordering surprises`() {
        val choices = skipChoices(45)
        assertEquals(choices.distinct(), choices)
        assertEquals(choices.sorted(), choices)
    }
}
