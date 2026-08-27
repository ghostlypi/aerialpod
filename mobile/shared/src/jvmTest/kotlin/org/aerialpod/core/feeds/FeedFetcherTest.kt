package org.aerialpod.core.feeds

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.aerialpod.core.queue.Library
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `fetchAndStore` — conditional GET, the first-fetch back-catalogue rule, and
 * enclosure aliasing.
 */
class FeedFetcherTest {

    private class Server {
        var body = ""
        var status = HttpStatusCode.OK
        var etag: String? = null
        var lastModified: String? = null
        val requests = mutableListOf<Map<String, String?>>()

        fun engine() = MockEngine { request ->
            requests += mapOf(
                HttpHeaders.IfNoneMatch to request.headers[HttpHeaders.IfNoneMatch],
                HttpHeaders.IfModifiedSince to request.headers[HttpHeaders.IfModifiedSince],
                HttpHeaders.UserAgent to request.headers[HttpHeaders.UserAgent],
            )
            val headers = buildList {
                add(HttpHeaders.ContentType to "application/rss+xml")
                etag?.let { add(HttpHeaders.ETag to it) }
                lastModified?.let { add(HttpHeaders.LastModified to it) }
            }
            respond(
                body, status,
                headersOf(*headers.map { (k, v) -> k to listOf(v) }.toTypedArray()),
            )
        }
    }

    private class Fx(val server: Server = Server()) {
        val lib = Library()
        val podcast = lib.repo.upsertPodcast(Library.FEED, syncState = "clean")
        val fetcher = FeedFetcher(lib.repo, HttpClient(server.engine())) { 1_700_000_000L }
    }

    private fun feed(vararg items: String, title: String = "Show") = """
        <?xml version="1.0"?>
        <rss><channel>
          <title>$title</title>
          <link>https://example.com</link>
          <description>Desc</description>
          <image><url>https://example.com/art.png</url></image>
          ${items.joinToString("\n")}
        </channel></rss>
    """.trimIndent()

    private fun item(n: Int, guid: String = "g$n", url: String = "https://cdn.example.com/ep$n.mp3") = """
        <item>
          <title>Episode $n</title>
          <guid>$guid</guid>
          <pubDate>Tue, ${10 + n} Jul 2024 10:00:00 +0000</pubDate>
          <enclosure url="$url" type="audio/mpeg" length="100"/>
        </item>
    """.trimIndent()

    // ---------------------------------------------------------------- first fetch

    /**
     * A brand-new subscription must not dump its whole back catalogue into the
     * queue — only the most recent episode surfaces.
     */
    @Test
    fun theFirstFetchArchivesTheBackCatalogueExceptTheNewest() = runTest {
        val f = Fx()
        f.server.body = feed(item(1), item(2), item(3))
        val fresh = f.fetcher.fetchAndStore(f.podcast)

        assertEquals(0, fresh, "nothing on a first fetch counts as new")
        val episodes = f.lib.db.episodesQueries.allEpisodesForPodcast(f.podcast).executeAsList()
        assertEquals(3, episodes.size)
        assertEquals(1, episodes.count { it.state == "new" })
        assertEquals("Episode 3", episodes.single { it.state == "new" }.title)
        assertEquals(2, episodes.count { it.state == "archived" })
    }

    @Test
    fun aLaterFetchCountsGenuinelyNewEpisodes() = runTest {
        val f = Fx()
        f.server.body = feed(item(1), item(2))
        f.fetcher.fetchAndStore(f.podcast)

        f.server.body = feed(item(1), item(2), item(3))
        val fresh = f.fetcher.fetchAndStore(f.podcast)

        assertEquals(1, fresh)
        assertEquals("new", f.lib.db.episodesQueries
            .episodeByGuid(f.podcast, "g3").executeAsOne().state)
    }

    @Test
    fun storesChannelMetadata() = runTest {
        val f = Fx()
        f.server.etag = "\"abc\""
        f.server.lastModified = "Tue, 16 Jul 2024 10:00:00 GMT"
        f.server.body = feed(item(1), title = "Rock &amp; Roll")
        f.fetcher.fetchAndStore(f.podcast)

        val podcast = assertNotNull(f.lib.repo.podcastById(f.podcast))
        assertEquals("Rock & Roll", podcast.title)
        assertEquals("https://example.com/art.png", podcast.image_url)
        assertEquals("https://example.com", podcast.website)
        assertEquals("\"abc\"", podcast.etag)
        assertEquals("Tue, 16 Jul 2024 10:00:00 GMT", podcast.http_last_modified)
        assertEquals(1_700_000_000L, podcast.last_refresh)
    }

    // ---------------------------------------------------------------- conditional GET

    @Test
    fun sendsConditionalHeadersOnceItHasThem() = runTest {
        val f = Fx()
        f.server.etag = "\"v1\""
        f.server.lastModified = "Tue, 16 Jul 2024 10:00:00 GMT"
        f.server.body = feed(item(1))
        f.fetcher.fetchAndStore(f.podcast)

        assertNull(f.server.requests[0][HttpHeaders.IfNoneMatch], "nothing to send on the first fetch")

        f.fetcher.fetchAndStore(f.podcast)
        assertEquals("\"v1\"", f.server.requests[1][HttpHeaders.IfNoneMatch])
        assertEquals("Tue, 16 Jul 2024 10:00:00 GMT", f.server.requests[1][HttpHeaders.IfModifiedSince])
        assertTrue(f.server.requests[0][HttpHeaders.UserAgent]!!.startsWith("AerialPod/"))
    }

    @Test
    fun aNotModifiedResponseOnlyStampsTheRefresh() = runTest {
        val f = Fx()
        f.server.body = feed(item(1))
        f.fetcher.fetchAndStore(f.podcast)

        f.server.status = HttpStatusCode.NotModified
        f.server.body = ""
        val fresh = f.fetcher.fetchAndStore(f.podcast)

        assertEquals(0, fresh)
        assertEquals(1, f.lib.db.episodesQueries.allEpisodesForPodcast(f.podcast).executeAsList().size)
        assertEquals(1_700_000_000L, f.lib.repo.podcastById(f.podcast)?.last_refresh)
    }

    @Test
    fun anHttpErrorIsReportedWithTheFeedUrl() = runTest {
        val f = Fx()
        f.server.status = HttpStatusCode.NotFound
        f.server.body = "nope"
        val error = assertFailsWith<FeedFetcher.FeedException> { f.fetcher.fetchAndStore(f.podcast) }
        assertTrue(Library.FEED in error.message!!)
        assertTrue("404" in error.message!!)
    }

    @Test
    fun anUnparseableBodyIsReported() = runTest {
        val f = Fx()
        f.server.body = "<html><body>Sorry</body></html>"
        assertFailsWith<FeedFetcher.FeedException> { f.fetcher.fetchAndStore(f.podcast) }
    }

    // ---------------------------------------------------------------- updates

    /**
     * The enclosure URL changing is routine — a show moves hosting. The old URL
     * becomes an alias so gpodder actions that still reference it resolve.
     */
    @Test
    fun aChangedEnclosureUrlIsAliased() = runTest {
        val f = Fx()
        f.server.body = feed(item(1, url = "https://old-cdn.example.com/ep1.mp3"))
        f.fetcher.fetchAndStore(f.podcast)

        f.server.body = feed(item(1, url = "https://new-cdn.example.com/ep1.mp3"))
        f.fetcher.fetchAndStore(f.podcast)

        val episode = f.lib.db.episodesQueries.episodeByGuid(f.podcast, "g1").executeAsOne()
        assertEquals("https://new-cdn.example.com/ep1.mp3", episode.media_url)
        assertNotNull(
            f.lib.db.episodesQueries
                .episodeByAlias("https://old-cdn.example.com/ep1.mp3", f.podcast)
                .executeAsOneOrNull(),
            "the old URL must still resolve",
        )
    }

    /** A refresh must not erase what an earlier fetch knew. */
    @Test
    fun aFeedThatStopsPublishingADurationDoesNotEraseIt() = runTest {
        val f = Fx()
        f.server.body = """
            <rss><channel><title>S</title><item><guid>g1</guid>
              <itunes:duration>1800</itunes:duration>
              <enclosure url="https://cdn.example.com/ep1.mp3" type="audio/mpeg" length="99"/>
            </item></channel></rss>
        """.trimIndent()
        f.fetcher.fetchAndStore(f.podcast)
        assertEquals(1800L, f.lib.db.episodesQueries.episodeByGuid(f.podcast, "g1").executeAsOne().duration_secs)

        f.server.body = """
            <rss><channel><title>S</title><item><guid>g1</guid>
              <enclosure url="https://cdn.example.com/ep1.mp3" type="audio/mpeg"/>
            </item></channel></rss>
        """.trimIndent()
        f.fetcher.fetchAndStore(f.podcast)

        val episode = f.lib.db.episodesQueries.episodeByGuid(f.podcast, "g1").executeAsOne()
        assertEquals(1800L, episode.duration_secs, "duration should survive")
        assertEquals(99L, episode.file_size, "size should survive")
    }

    @Test
    fun anItemWithNoEnclosureIsSkipped() = runTest {
        val f = Fx()
        f.server.body = feed("<item><title>Just a note</title><guid>g9</guid></item>", item(1))
        f.fetcher.fetchAndStore(f.podcast)
        val episodes = f.lib.db.episodesQueries.allEpisodesForPodcast(f.podcast).executeAsList()
        assertEquals(1, episodes.size)
        assertEquals("Episode 1", episodes.single().title)
    }

    /** No GUID: the enclosure URL is the identity, so a re-fetch must not duplicate. */
    @Test
    fun anItemWithNoGuidIsKeyedOnItsUrl() = runTest {
        val f = Fx()
        val xml = """
            <rss><channel><title>S</title><item><title>No guid</title>
              <enclosure url="https://cdn.example.com/solo.mp3" type="audio/mpeg"/>
            </item></channel></rss>
        """.trimIndent()
        f.server.body = xml
        f.fetcher.fetchAndStore(f.podcast)
        f.fetcher.fetchAndStore(f.podcast)

        val episodes = f.lib.db.episodesQueries.allEpisodesForPodcast(f.podcast).executeAsList()
        assertEquals(1, episodes.size)
        assertEquals("https://cdn.example.com/solo.mp3", episodes.single().guid)
    }
}
