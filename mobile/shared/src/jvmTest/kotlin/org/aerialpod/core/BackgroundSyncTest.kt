package org.aerialpod.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The background pass, which nobody is watching.
 *
 * Its whole contract is the one bit it hands back to the platform scheduler:
 * whether waking sooner than the next scheduled run would help. Getting that
 * wrong is expensive in a way no screen shows — a pass that reports "retry" for
 * a condition that cannot improve puts the device into an exponential backoff
 * that wakes the radio forever, and a pass that reports "success" after a real
 * failure quietly stops syncing for an hour.
 */
class BackgroundSyncTest {

    private val feed = """<?xml version="1.0"?><rss version="2.0"><channel><title>Show</title>
        <item><title>Old</title><guid>g0</guid>
          <pubDate>Mon, 01 Jan 2024 00:00:00 GMT</pubDate>
          <enclosure url="https://example.com/0.mp3" type="audio/mpeg"/></item>
        <item><title>New</title><guid>g1</guid>
          <pubDate>Tue, 02 Jan 2024 00:00:00 GMT</pubDate>
          <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/></item>
        </channel></rss>"""

    private fun core(
        account: GpodderCredentials? = null,
        feedStatus: HttpStatusCode = HttpStatusCode.OK,
        gpodderFails: Boolean = false,
    ): AerialPodCore {
        val engine = MockEngine { request ->
            val isFeed = request.url.encodedPath.endsWith(".xml")
            when {
                isFeed && feedStatus != HttpStatusCode.OK -> respondError(feedStatus)
                isFeed -> respond(
                    feed, HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/rss+xml"),
                )
                gpodderFails -> respondError(HttpStatusCode.ServiceUnavailable)
                else -> respond("{}", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        return AerialPodCore(
            database = openDatabase(JvmDriverFactory()),
            secretStore = object : SecretStore {
                private var value: ByteArray? = null
                override fun loadSecret() = value
                override fun storeSecret(value: ByteArray) { this.value = value }
            },
            credentials = object : GpodderCredentialStore {
                override fun load(): GpodderCredentials? = account
                override fun save(credentials: GpodderCredentials) = Unit
                override fun clear() = Unit
            },
            httpClient = HttpClient(engine),
            deviceCaption = "Test",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            now = { 1_700_000_000L },
        )
    }

    private val account = GpodderCredentials(
        username = "someone", password = "secret", server = "https://gpodder.example",
    )

    @Test
    fun `no account is a settled state, not a retry`() = runTest {
        val core = core(account = null)
        core.repo.upsertPodcast("https://example.com/feed.xml")

        val result = core.backgroundSync()

        // syncNow() throws when unconfigured. Reporting that as retryable would
        // back off forever on every phone that never signed in.
        assertFalse(result.shouldRetry, "an unconfigured account must not retry")
        assertNull(result.sync)
        assertNull(result.syncFailure)
    }

    @Test
    fun `feeds still refresh when there is no account`() = runTest {
        val core = core(account = null)
        core.repo.upsertPodcast("https://example.com/feed.xml")

        core.backgroundSync()

        // gpodder.net is optional; the feed half has to work on its own, or an
        // account-less install never sees a new episode at all.
        assertEquals(2, core.repo.episodesForPodcast(1L).size)
        assertEquals(1, core.queue.episodes().size, "and the newest reaches the queue")
    }

    @Test
    fun `a failing sync asks to be retried`() = runTest {
        val core = core(account = account, gpodderFails = true)
        core.repo.upsertPodcast("https://example.com/feed.xml")

        val result = core.backgroundSync()

        assertTrue(result.shouldRetry, "a real sync failure is worth waking for")
        assertNull(result.sync)
    }

    @Test
    fun `a failing sync still keeps the feed refresh`() = runTest {
        val core = core(account = account, gpodderFails = true)
        core.repo.upsertPodcast("https://example.com/feed.xml")

        core.backgroundSync()

        // The refresh already committed. Losing it because the sync that ran
        // afterwards failed would throw away the pass's only useful work.
        assertEquals(2, core.repo.episodesForPodcast(1L).size)
    }

    @Test
    fun `a dead feed does not retry the whole pass`() = runTest {
        val core = core(account = null, feedStatus = HttpStatusCode.NotFound)
        core.repo.upsertPodcast("https://example.com/feed.xml")

        val result = core.backgroundSync()

        // One unreachable feed is ordinary. The next scheduled pass is soon
        // enough, and backing off would punish every other feed too.
        assertFalse(result.shouldRetry)
        assertEquals(1, result.feedFailures.size)
    }

    @Test
    fun `the pass never throws`() = runTest {
        for (core in listOf(
            core(account = null),
            core(account = account, gpodderFails = true),
            core(account = null, feedStatus = HttpStatusCode.NotFound),
        )) {
            core.repo.upsertPodcast("https://example.com/feed.xml")
            // An exception escaping into WorkManager is reported as a generic
            // failure, losing the retry/success distinction this class exists
            // to make.
            core.backgroundSync()
        }
    }

    @Test
    fun `the interval matches the desktop's hourly refresher`() {
        // feeds/refresher.py: REFRESH_INTERVAL_MS = 60 * 60 * 1000.
        assertEquals(3600L, AerialPodCore.BACKGROUND_INTERVAL_SECONDS)
    }
}
