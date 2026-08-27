package org.aerialpod.core.feeds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RSS and Atom, reduced to episode rows.
 *
 * The date expectations were computed with Python's `email.utils` and
 * `datetime` — the same readings feedparser gives the desktop — so a feed dates
 * its episodes identically on both sides. A wrong date is worse than a missing
 * one: it silently reorders the queue.
 */
class FeedParserTest {

    // ---------------------------------------------------------------- dates

    @Test
    fun readsEveryDateShapeFeedsActuallyUse() {
        val cases = mapOf(
            "Tue, 16 Jul 2024 10:00:00 +0000" to 1_721_124_000L,
            "Tue, 16 Jul 2024 10:00:00 GMT" to 1_721_124_000L,
            "Tue, 16 Jul 2024 10:00:00 +0200" to 1_721_116_800L,
            "Tue, 16 Jul 2024 10:00:00 -0500" to 1_721_142_000L,
            "16 Jul 2024 10:00:00 GMT" to 1_721_124_000L,   // no day name
            "Tue, 16 Jul 2024 10:00 GMT" to 1_721_124_000L, // no seconds
            "Mon, 05 Jan 98 04:03:02 GMT" to 883_972_982L,  // two-digit year
            "2024-07-16T10:00:00Z" to 1_721_124_000L,
            "2024-07-16T10:00:00+00:00" to 1_721_124_000L,
            "2024-07-16T10:00:00+02:00" to 1_721_116_800L,
            "2024-07-16" to 1_721_088_000L,                 // date only
        )
        for ((text, expected) in cases) {
            assertEquals(expected, parseFeedDate(text), "date '$text'")
        }
    }

    @Test
    fun namedUsZonesAreOffsetsToo() {
        // 10:00 EST is 15:00 UTC.
        assertEquals(1_721_124_000L + 5 * 3600, parseFeedDate("Tue, 16 Jul 2024 10:00:00 EST"))
    }

    @Test
    fun returnsNullRatherThanGuessingAtNonsense() {
        assertNull(parseFeedDate(null))
        assertNull(parseFeedDate(""))
        assertNull(parseFeedDate("sometime last Tuesday"))
        assertNull(parseFeedDate("Tue, 32 Foo 2024"))
    }

    // ---------------------------------------------------------------- duration

    @Test
    fun readsEveryDurationShape() {
        assertEquals(3600L, parseDuration("3600"))
        assertEquals(3723L, parseDuration("1:02:03"))
        assertEquals(123L, parseDuration("2:03"))
        assertEquals(45L, parseDuration("45.7"))
        assertEquals(3723L, parseDuration("  1:02:03  "))
        assertNull(parseDuration(null))
        assertNull(parseDuration(""))
        assertNull(parseDuration("about an hour"))
    }

    // ---------------------------------------------------------------- rss

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Rock &amp; Roll Radio</title>
            <link>https://example.com</link>
            <description>A show about music &amp; things</description>
            <image><url>https://example.com/art.jpg</url></image>
            <item>
              <title>Episode One</title>
              <guid isPermaLink="false">tag:example.com,2024:1</guid>
              <pubDate>Tue, 16 Jul 2024 10:00:00 +0000</pubDate>
              <description><![CDATA[<p>Notes with <b>html</b> & an ampersand</p>]]></description>
              <itunes:duration>1:02:03</itunes:duration>
              <itunes:image href="https://example.com/ep1.jpg"/>
              <enclosure url="https://cdn.example.com/ep1.mp3?a=1&amp;b=2"
                         type="audio/mpeg" length="42000000"/>
            </item>
            <item>
              <title>Episode Two</title>
              <pubDate>Wed, 17 Jul 2024 10:00:00 +0000</pubDate>
              <enclosure url="https://cdn.example.com/ep2.mp3" type="audio/mpeg"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun readsAnRssChannel() {
        val feed = assertNotNull(FeedParser.parse(rss))
        assertEquals("Rock & Roll Radio", feed.title)
        assertEquals("A show about music & things", feed.description)
        assertEquals("https://example.com/art.jpg", feed.imageUrl)
        assertEquals("https://example.com", feed.website)
        assertEquals(2, feed.entries.size)
    }

    @Test
    fun readsAnRssItem() {
        val entry = assertNotNull(FeedParser.parse(rss)).entries[0]
        assertEquals("tag:example.com,2024:1", entry.guid)
        assertEquals("Episode One", entry.title)
        assertEquals("https://cdn.example.com/ep1.mp3?a=1&b=2", entry.mediaUrl)
        assertEquals("audio/mpeg", entry.mime)
        assertEquals(42_000_000L, entry.fileSize)
        assertEquals(1_721_124_000L, entry.pubDate)
        assertEquals(3723L, entry.durationSecs)
        assertEquals("https://example.com/ep1.jpg", entry.imageUrl)
        assertTrue(entry.description!!.contains("<b>html</b> & an ampersand"))
    }

    @Test
    fun anItemWithNoGuidHasNone() {
        val entry = assertNotNull(FeedParser.parse(rss)).entries[1]
        assertNull(entry.guid)
        assertEquals("https://cdn.example.com/ep2.mp3", entry.mediaUrl)
        assertNull(entry.fileSize)
    }

    /** An item offering several files: the audio one wins regardless of order. */
    @Test
    fun prefersTheAudioEnclosure() {
        val xml = """
            <rss><channel><item>
              <enclosure url="https://x/chapters.json" type="application/json"/>
              <enclosure url="https://x/ep.mp3" type="audio/mpeg"/>
              <enclosure url="https://x/video.mp4" type="video/mp4"/>
            </item></channel></rss>
        """.trimIndent()
        assertEquals("https://x/ep.mp3", FeedParser.parse(xml)?.entries?.get(0)?.mediaUrl)
    }

    /** Nothing marked audio: still yield something playable rather than skipping. */
    @Test
    fun fallsBackToTheLastEnclosure() {
        val xml = """
            <rss><channel><item>
              <enclosure url="https://x/a.bin"/>
              <enclosure url="https://x/b.m4a"/>
            </item></channel></rss>
        """.trimIndent()
        assertEquals("https://x/b.m4a", FeedParser.parse(xml)?.entries?.get(0)?.mediaUrl)
    }

    @Test
    fun preferstItunesSummaryOverNothing() {
        val xml = """
            <rss><channel><item><itunes:summary>from itunes</itunes:summary></item></channel></rss>
        """.trimIndent()
        assertEquals("from itunes", FeedParser.parse(xml)?.entries?.get(0)?.description)
    }

    // ---------------------------------------------------------------- atom

    @Test
    fun readsAnAtomFeed() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Show</title>
              <subtitle>Subtitle here</subtitle>
              <link href="https://example.org/"/>
              <logo>https://example.org/logo.png</logo>
              <entry>
                <id>urn:uuid:1234</id>
                <title>Atom Episode</title>
                <published>2024-07-16T10:00:00Z</published>
                <summary>Summary text</summary>
                <link rel="enclosure" href="https://cdn.example.org/a.mp3"
                      type="audio/mpeg" length="123"/>
              </entry>
            </feed>
        """.trimIndent()
        val feed = assertNotNull(FeedParser.parse(xml))
        assertEquals("Atom Show", feed.title)
        assertEquals("Subtitle here", feed.description)
        assertEquals("https://example.org/logo.png", feed.imageUrl)
        assertEquals("https://example.org/", feed.website)

        val entry = feed.entries.single()
        assertEquals("urn:uuid:1234", entry.guid)
        assertEquals("https://cdn.example.org/a.mp3", entry.mediaUrl)
        assertEquals(123L, entry.fileSize)
        assertEquals(1_721_124_000L, entry.pubDate)
        assertEquals("Summary text", entry.description)
    }

    // ---------------------------------------------------------------- salvage

    /** The whole reason for a hand-rolled parser. */
    @Test
    fun readsAFeedAConformingParserWouldReject() {
        val xml = """
            <?xml version="1.0"?>
            <rss><channel>
              <title>Bad & Broken</title>
              <item>
                <title>Fish & Chips</title>
                <enclosure url="https://x/a.mp3" type="audio/mpeg">
                <description>unclosed tags everywhere
              </item>
              <item>
                <TITLE>Uppercase</TITLE>
                <enclosure url='https://x/b.mp3' type='audio/mpeg'/>
              </item>
            </channel></rss>
        """.trimIndent()
        val feed = assertNotNull(FeedParser.parse(xml))
        assertEquals("Bad & Broken", feed.title)
        assertEquals(2, feed.entries.size)
        assertEquals("Fish & Chips", feed.entries[0].title)
        assertEquals("https://x/a.mp3", feed.entries[0].mediaUrl)
        assertEquals("Uppercase", feed.entries[1].title)
    }

    @Test
    fun returnsNullForSomethingThatIsNotAFeed() {
        assertNull(FeedParser.parse("<html><body>Not a feed</body></html>"))
        assertNull(FeedParser.parse("500 Internal Server Error"))
    }

    @Test
    fun salvagesABareChannelElement() {
        val feed = assertNotNull(FeedParser.parse("<channel><title>Bare</title></channel>"))
        assertEquals("Bare", feed.title)
    }
}
