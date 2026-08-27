package org.aerialpod.core.feeds

import org.aerialpod.core.queue.Library
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** OPML import/export — how subscriptions arrive from, and leave for, other apps. */
class OpmlTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <head><title>Someone's subscriptions</title></head>
          <body>
            <outline text="Tech" title="Tech">
              <outline type="rss" text="Show A" xmlUrl="https://a.example/feed.xml"
                       htmlUrl="https://a.example"/>
              <outline type="rss" text="Show B &amp; Friends" xmlUrl="https://b.example/feed.xml"/>
            </outline>
            <outline type="rss" text="Show C" xmlUrl="https://c.example/feed.xml"/>
            <outline text="A category with no feed"/>
          </body>
        </opml>
    """.trimIndent()

    /** Exporters disagree about grouping, so a nested feed is still a feed. */
    @Test
    fun importsFeedsAtAnyDepth() {
        val lib = Library()
        val added = Opml.import(sample, lib.repo)
        assertEquals(3, added.size)
        for (url in listOf("https://a.example/feed.xml", "https://b.example/feed.xml",
                           "https://c.example/feed.xml")) {
            assertNotNull(lib.repo.podcastByFeedUrl(url), url)
        }
    }

    @Test
    fun importsTheTitleAndDecodesEntities() {
        val lib = Library()
        Opml.import(sample, lib.repo)
        val podcast = assertNotNull(lib.repo.podcastByFeedUrl("https://b.example/feed.xml"))
        assertEquals("Show B & Friends", podcast.title)
    }

    @Test
    fun importedFeedsArePendingUpload() {
        val lib = Library()
        Opml.import(sample, lib.repo)
        val podcast = assertNotNull(lib.repo.podcastByFeedUrl("https://a.example/feed.xml"))
        assertEquals("add_pending", podcast.sync_state, "an import is a local subscription to push")
    }

    @Test
    fun skipsFeedsAlreadySubscribed() {
        val lib = Library()
        lib.repo.upsertPodcast("https://a.example/feed.xml")
        val added = Opml.import(sample, lib.repo)
        assertEquals(2, added.size)
    }

    @Test
    fun ignoresSomethingThatIsNotOpml() {
        val lib = Library()
        assertEquals(emptyList(), Opml.import("not xml at all", lib.repo))
        assertEquals(emptyList(), Opml.import("<opml><body/></opml>", lib.repo))
    }

    @Test
    fun exportsSubscriptionsWithEscapedAttributes() {
        val lib = Library()
        val id = lib.addPodcast("https://x.example/feed.xml?a=1&b=2", "Rock & Roll")
        lib.db.podcastsQueries.updatePodcastMeta(
            title = "Rock & Roll", description = null, image_url = null,
            website = "https://x.example", etag = null, http_last_modified = null,
            last_refresh = null, id = id,
        )
        val xml = Opml.export(lib.repo) { 1_700_000_000L }

        assertTrue("""xmlUrl="https://x.example/feed.xml?a=1&amp;b=2"""" in xml, xml)
        assertTrue("""title="Rock &amp; Roll"""" in xml, xml)
        assertTrue("""htmlUrl="https://x.example"""" in xml, xml)
        assertTrue("2023-11-14T22:13:20Z" in xml, xml)
    }

    /** The real check: what we write, we can read back. */
    @Test
    fun exportRoundTripsThroughImport() {
        val source = Library()
        source.addPodcast("https://one.example/feed.xml?x=1&y=2", "One & Only")
        source.addPodcast("https://two.example/feed.xml", "Two")
        val xml = Opml.export(source.repo) { 1_700_000_000L }

        val target = Library()
        // Both libraries seed the same feed, so of the three exported only the
        // two genuinely new ones import — which is the dedup rule working.
        val added = Opml.import(xml, target.repo)
        assertEquals(2, added.size)
        val restored = assertNotNull(target.repo.podcastByFeedUrl("https://one.example/feed.xml?x=1&y=2"))
        assertEquals("One & Only", restored.title)
    }

    @Test
    fun exportsOnlySubscribedPodcasts() {
        val lib = Library()
        val gone = lib.addPodcast("https://gone.example/feed.xml", "Gone")
        lib.repo.unsubscribePodcast(gone)
        val xml = Opml.export(lib.repo) { 1_700_000_000L }
        assertTrue("gone.example" !in xml, xml)
    }
}
