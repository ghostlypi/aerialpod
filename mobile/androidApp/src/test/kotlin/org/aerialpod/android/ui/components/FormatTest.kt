package org.aerialpod.android.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatTest {

    @Test
    fun paragraphsDoNotRunTogether() {
        // The bug this was written for: stripping tags to nothing turned real
        // show notes into "…communist capitalism at its finest.Sponsors:Many".
        assertEquals("One.\nTwo.", plainText("<p>One.</p><p>Two.</p>"))
        assertEquals("A\nB", plainText("A<br>B"))
        assertEquals("First\nSecond", plainText("<li>First</li><li>Second</li>"))
    }

    @Test
    fun inlineTagsDoNotInsertLineBreaks() {
        assertEquals("a bold word", plainText("a <b>bold</b> word"))
        assertEquals("see the link here", plainText("see the <a href=\"x\">link</a> here"))
    }

    @Test
    fun decodesTheEntitiesFeedsActuallyUse() {
        assertEquals("Tom & Jerry", plainText("Tom &amp; Jerry"))
        assertEquals("<tag>", plainText("&lt;tag&gt;"))
        assertEquals("\"quoted\"", plainText("&quot;quoted&quot;"))
        assertEquals("it's", plainText("it&#39;s"))
        assertEquals("a b", plainText("a&nbsp;b"))
    }

    @Test
    fun collapsesWhitespaceWithoutLosingParagraphs() {
        assertEquals("a b", plainText("a     \t  b"))
        assertEquals("a\nb", plainText("<p>a</p>\n\n\n<p>b</p>"))
        assertEquals("", plainText(null))
        assertEquals("", plainText("   "))
    }

    @Test
    fun survivesMalformedMarkup() {
        // Feeds are not well-formed and this must never throw.
        assertTrue(plainText("<p>unclosed").isNotEmpty())
        assertEquals("text", plainText("text<"))
        assertEquals("a b", plainText("a <  > b"))
    }

    @Test
    fun durationsReadLikeTheDesktop() {
        assertEquals("", formatDuration(null))
        assertEquals("", formatDuration(0))
        assertEquals("0:45", formatDuration(45))
        assertEquals("47:00", formatDuration(47 * 60L))
        assertEquals("1:16:17", formatDuration(4577))
    }

    @Test
    fun remainingRoundsUpAndDisappearsWhenFinished() {
        assertEquals("1 min left", formatRemaining(0, 1))
        assertEquals("10 min left", formatRemaining(0, 600))
        assertEquals("1h left", formatRemaining(0, 3600))
        assertEquals("1h 30m left", formatRemaining(0, 5400))
        // Past the end, or with no known total, there is nothing honest to say.
        assertEquals("", formatRemaining(700, 600))
        assertEquals("", formatRemaining(0, 0))
    }

    @Test
    fun datesFallBackRatherThanThrow() {
        assertEquals("", formatDate(null))
        assertEquals("", formatDate(0))
        assertTrue(formatDate(1_700_000_000L).isNotEmpty())
    }
}
