package org.aerialpod.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.aerialpod.core.db.JvmDriverFactory
import org.aerialpod.core.db.openDatabase
import org.aerialpod.core.gpodder.GpodderCredentialStore
import org.aerialpod.core.gpodder.GpodderCredentials
import org.aerialpod.core.lan.SecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Subscribing to a podcast has to put its newest episode in the queue.
 *
 * This is the case a `fresh > 0` guard around `reconcile()` silently breaks, and
 * the one that no test covered: `fetchAndStore` deliberately reports **0** new
 * episodes on a first fetch, because the back catalogue is archived rather than
 * counted. Everything looked correct — 3830 episodes stored, one per feed left
 * 'new' — and the queue stayed empty, on a real device, with no error anywhere.
 *
 * The desktop reconciles unconditionally when a refresh finishes (`ipc/hub.py`,
 * `_on_refresh_finished`). So does the core now.
 */
class FirstSubscriptionTest {

    private fun feed(vararg titles: String): String = buildString {
        append("""<?xml version="1.0"?><rss version="2.0"><channel><title>Show</title>""")
        titles.forEachIndexed { index, title ->
            append(
                """<item><title>$title</title><guid>guid-$index</guid>""" +
                    """<pubDate>${pubDates[index]}</pubDate>""" +
                    """<enclosure url="https://example.com/$index.mp3" type="audio/mpeg"/></item>"""
            )
        }
        append("</channel></rss>")
    }

    // Oldest first in the list; the newest is the last one the feed lists.
    private val pubDates = listOf(
        "Mon, 01 Jan 2024 00:00:00 GMT",
        "Tue, 02 Jan 2024 00:00:00 GMT",
        "Wed, 03 Jan 2024 00:00:00 GMT",
    )

    private fun core(body: String): AerialPodCore {
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/rss+xml"))
        }
        return AerialPodCore(
            database = openDatabase(JvmDriverFactory()),
            secretStore = object : SecretStore {
                private var value: ByteArray? = null
                override fun loadSecret() = value
                override fun storeSecret(value: ByteArray) { this.value = value }
            },
            credentials = object : GpodderCredentialStore {
                override fun load(): GpodderCredentials? = null
                override fun save(credentials: GpodderCredentials) = Unit
                override fun clear() = Unit
            },
            httpClient = HttpClient(engine),
            deviceCaption = "Test",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            now = { 1_700_000_000L },
        )
    }

    @Test
    fun subscribingQueuesTheNewestEpisode() = runTest {
        val core = core(feed("Oldest", "Middle", "Newest"))

        core.addPodcast("https://example.com/feed.xml")

        val queued = core.queue.episodes()
        assertEquals(1, queued.size, "the newest episode should be queued on subscribe")
        assertEquals("Newest", queued.single().title)
    }

    @Test
    fun theBackCatalogueIsArchivedRatherThanQueued() = runTest {
        val core = core(feed("Oldest", "Middle", "Newest"))
        core.addPodcast("https://example.com/feed.xml")

        val states = core.repo.episodesForPodcast(1L).groupingBy { it.state }.eachCount()
        assertEquals(2, states["archived"], "everything but the newest is back catalogue")
        assertEquals(1, states["new"])
    }

    @Test
    fun firstFetchReportsNoNewEpisodesEvenThoughOneIsQueued() = runTest {
        // The guard that caused the bug looked reasonable precisely because this
        // number is 0. Pinning it means a future reader sees why the reconcile
        // cannot be conditional on it.
        val core = core(feed("Oldest", "Newest"))
        val id = core.repo.upsertPodcast("https://example.com/feed.xml")

        val reported = core.refreshFeed(id)

        assertEquals(0, reported, "a first fetch counts no episodes as new")
        assertEquals(1, core.queue.episodes().size, "and yet the newest one is queued")
    }

    @Test
    fun aSecondSubscriptionDoesNotDisturbTheFirst() = runTest {
        val core = core(feed("Oldest", "Newest"))
        core.addPodcast("https://example.com/one.xml")
        core.addPodcast("https://example.com/two.xml")

        val queued = core.queue.episodes()
        assertEquals(2, queued.size, "one newest episode from each podcast")
        assertTrue(queued.map { it.podcast_id }.toSet().size == 2)
    }

    @Test
    fun opmlImportQueuesOneEpisodePerFeed() = runTest {
        val core = core(feed("Oldest", "Newest"))
        val opml = """
            <?xml version="1.0"?>
            <opml version="2.0"><body>
              <outline type="rss" text="A" xmlUrl="https://example.com/a.xml"/>
              <outline type="rss" text="B" xmlUrl="https://example.com/b.xml"/>
              <outline type="rss" text="C" xmlUrl="https://example.com/c.xml"/>
            </body></opml>
        """.trimIndent()

        core.importOpml(opml)

        assertEquals(3, core.queue.episodes().size)
    }
}
