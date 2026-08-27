package org.aerialpod.core.gpodder

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.aerialpod.core.queue.Library
import org.aerialpod.core.sync.Matcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sync cycle end to end, against a mock gpodder.net.
 *
 * `SyncActionsTest` covers what an action *means*; this covers the orchestration
 * around it — the order of the calls, which endpoint gets which device id, what
 * advances the cursors, and what dry-run must not do.
 */
class GpodderSyncCycleTest {

    private class Server {
        val requests = mutableListOf<HttpRequestData>()
        var episodeActions = """{"actions": [], "timestamp": 100}"""
        var mergedSubscriptions = """[]"""
        var subscriptionChanges = """{"add": [], "remove": [], "timestamp": 200}"""
        var uploadResponse = """{"timestamp": 300, "update_urls": []}"""
        var devices = """[{"id": "aerialpod-desktop", "caption": "Desktop"}]"""
        /** path -> how many more times to fail before succeeding */
        val failures = mutableMapOf<String, Int>()
        var failStatus = HttpStatusCode.InternalServerError

        fun engine() = MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            val pending = failures[path] ?: 0
            if (pending > 0) {
                failures[path] = pending - 1
                respond("upstream sad", failStatus, jsonHeaders)
            } else {
                respond(bodyFor(path), HttpStatusCode.OK, jsonHeaders)
            }
        }

        private fun bodyFor(path: String): String = when {
            path.contains("/auth/") -> "{}"
            path.contains("/api/2/devices/") -> if (path.endsWith("/testuser.json")) devices else "{}"
            path.contains("/api/2/sync-devices/") -> "{}"
            path.contains("/api/2/subscriptions/") -> subscriptionChanges
            path == "/subscriptions/testuser.json" -> mergedSubscriptions
            path.contains("/api/2/episodes/") -> episodeActions
            else -> "{}"
        }

        private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        fun paths(): List<String> = requests.map { it.url.encodedPath }

        /** The last POST to [path] — several endpoints take both verbs. */
        fun postBody(path: String): String? = requests
            .lastOrNull { it.url.encodedPath == path && it.method == HttpMethod.Post }
            ?.let { (it.body as? TextContent)?.text }

        fun posted(path: String): Boolean = requests
            .any { it.url.encodedPath == path && it.method == HttpMethod.Post }

        fun paramOf(path: String, name: String): String? = requests
            .lastOrNull { it.url.encodedPath == path && it.method == HttpMethod.Get }
            ?.url?.parameters?.get(name)
    }

    private class Fx(val server: Server = Server(), dryRun: Boolean = false) {
        val lib = Library()
        val podcast = lib.addPodcast()
        val client = GpodderClient(
            username = "testuser",
            password = "hunter2",
            http = HttpClient(server.engine()),
            dryRun = dryRun,
            now = { 1_700_000_000L },
            backoff = { }, // tests must not actually wait out the retry schedule
        )
        val sync = GpodderSync(
            repo = lib.repo,
            matcher = Matcher(lib.repo),
            deviceCaption = "Pixel 10 Pro XL",
            clientProvider = { client },
            now = { 1_700_000_000L },
            dryRun = dryRun,
        )
    }

    // ---------------------------------------------------------------- identity

    @Test
    fun registersItsOwnDeviceIdOnce() = runTest {
        val f = Fx()
        f.sync.syncNow()
        val deviceId = f.sync.deviceId()
        assertEquals("aerialpod-pixel-10-pro-xl", deviceId)
        assertTrue("/api/2/devices/testuser/$deviceId.json" in f.server.paths())

        // A second sync must not re-register.
        val before = f.server.paths().count { it == "/api/2/devices/testuser/$deviceId.json" }
        f.sync.syncNow()
        assertEquals(before, f.server.paths().count { it == "/api/2/devices/testuser/$deviceId.json" })
    }

    /**
     * The subscription endpoint is per-device, so the phone must not borrow the
     * desktop's id — the server would believe that device already has whatever
     * we add, and never tell the desktop about it.
     */
    @Test
    fun subscriptionsUseOurOwnDeviceIdNotAPeers() = runTest {
        val f = Fx()
        // Something to push, so the POST happens; then a second sync so the GET
        // diff endpoint is used too. Without both, a first sync touches only the
        // account-level list and there is no per-device path to assert on —
        // which would make the assertions below vacuously true.
        f.lib.repo.upsertPodcast("https://pending.example/feed.xml", syncState = "add_pending")
        f.sync.syncNow()
        f.sync.syncNow()

        val subPaths = f.server.paths().filter { it.startsWith("/api/2/subscriptions/") }
        assertTrue(subPaths.size >= 2, "expected a push and a pull, got $subPaths")
        assertTrue(subPaths.all { it.contains("aerialpod-pixel-10-pro-xl") }, subPaths.toString())
        assertTrue(subPaths.none { it.contains("aerialpod-desktop") }, subPaths.toString())
    }

    @Test
    fun sendsBasicAuthOnEveryRequest() = runTest {
        val f = Fx()
        f.sync.syncNow()
        assertTrue(f.server.requests.isNotEmpty())
        // "testuser:hunter2" base64
        val expected = "Basic dGVzdHVzZXI6aHVudGVyMg=="
        assertTrue(f.server.requests.all { it.headers[HttpHeaders.Authorization] == expected })
    }

    // ---------------------------------------------------------------- actions

    @Test
    fun pushesTheOutboxAndClearsIt() = runTest {
        val f = Fx()
        f.lib.repo.enqueueAction(
            Library.FEED, "https://cdn.example.com/ep001.mp3", "play",
            timestamp = "2026-07-16T10:00:00", started = 0, position = 1200, total = 3000,
        )
        f.lib.repo.enqueueAction(Library.FEED, "https://cdn.example.com/ep002.mp3", "new",
            timestamp = "2026-07-16T10:05:00")

        val result = f.sync.syncNow()
        assertEquals(2, result.pushed)

        val body = assertNotNull(f.server.postBody("/api/2/episodes/testuser.json"))
        assertTrue("\"position\":1200" in body, body)
        assertTrue("\"total\":3000" in body, body)
        // A non-play action carries no progress fields at all.
        assertTrue(body.substringAfter("ep002").let { "position" !in it }, body)

        assertEquals(emptyList(), f.lib.repo.outboxActions())
    }

    @Test
    fun advancesTheActionCursor() = runTest {
        val f = Fx()
        f.server.episodeActions = """{"actions": [], "timestamp": 4242}"""
        f.sync.syncNow()
        assertEquals(4242L, f.lib.repo.stateLong(GpodderSync.ACTIONS_SINCE, 0))
        // and the next sync asks from there
        f.sync.syncNow()
        assertEquals("4242", f.server.paramOf("/api/2/episodes/testuser.json", "since"))
    }

    @Test
    fun appliesPulledActions() = runTest {
        val f = Fx()
        val id = f.lib.makeEpisode(f.podcast, 1)
        f.server.episodeActions = """
            {"actions": [{"podcast": "${Library.FEED}",
                          "episode": "https://cdn.example.com/ep001.mp3",
                          "action": "play", "timestamp": "2026-07-16T10:00:00",
                          "started": 0, "position": 640, "total": 3000}],
             "timestamp": 500}
        """.trimIndent()
        val result = f.sync.syncNow()
        assertEquals(1, result.applied)
        assertEquals(640L, f.lib.episode(id).position_secs)
    }

    // ---------------------------------------------------------------- subscriptions

    @Test
    fun firstSyncPullsTheMergedListAndLinksDevices() = runTest {
        val f = Fx()
        f.server.mergedSubscriptions = """["https://other.example/feed.xml", "${Library.FEED}"]"""
        val result = f.sync.syncNow()

        // Only the one we did not already have.
        assertEquals(1, result.subscriptionsAdded.size)
        assertNotNull(f.lib.repo.podcastByFeedUrl("https://other.example/feed.xml"))
        assertTrue("/api/2/sync-devices/testuser.json" in f.server.paths())

        val body = assertNotNull(f.server.postBody("/api/2/sync-devices/testuser.json"))
        assertTrue("aerialpod-desktop" in body, body)
    }

    /** Some servers answer the merged list with objects rather than bare strings. */
    @Test
    fun acceptsObjectShapedSubscriptions() = runTest {
        val f = Fx()
        f.server.mergedSubscriptions = """[{"url": "https://other.example/feed.xml"}]"""
        f.sync.syncNow()
        assertNotNull(f.lib.repo.podcastByFeedUrl("https://other.example/feed.xml"))
    }

    @Test
    fun laterSyncsUseTheDiffEndpoint() = runTest {
        val f = Fx()
        f.sync.syncNow() // first sync stamps subs_since
        f.server.subscriptionChanges = """
            {"add": ["https://fresh.example/feed.xml"], "remove": [], "timestamp": 900}
        """.trimIndent()
        val result = f.sync.syncNow()

        assertNotNull(f.lib.repo.podcastByFeedUrl("https://fresh.example/feed.xml"))
        assertEquals(900L, f.lib.repo.stateLong(GpodderSync.SUBS_SINCE, 0))
        assertTrue(result.notes.any { "sub change" in it })
    }

    @Test
    fun aRemoteRemovalUnsubscribes() = runTest {
        val f = Fx()
        f.sync.syncNow()
        f.server.subscriptionChanges = """
            {"add": [], "remove": ["${Library.FEED}"], "timestamp": 950}
        """.trimIndent()
        f.sync.syncNow()
        val podcast = assertNotNull(f.lib.repo.podcastByFeedUrl(Library.FEED))
        assertEquals(0L, podcast.subscribed)
        assertEquals("clean", podcast.sync_state)
    }

    @Test
    fun pushesPendingSubscriptionsAndAppliesUrlRewrites() = runTest {
        val f = Fx()
        f.lib.repo.upsertPodcast("https://moved.example/old.xml", syncState = "add_pending")
        f.server.uploadResponse = """{"timestamp": 1, "update_urls": []}"""
        f.server.subscriptionChanges =
            """{"timestamp": 1, "update_urls": [["https://moved.example/old.xml", "https://moved.example/new.xml"]]}"""

        f.sync.syncNow()

        assertNull(f.lib.repo.podcastByFeedUrl("https://moved.example/old.xml"))
        assertNotNull(f.lib.repo.podcastByFeedUrl("https://moved.example/new.xml"))
        assertTrue(f.lib.repo.allPodcasts().none { it.sync_state != "clean" })
    }

    // ---------------------------------------------------------------- resilience

    @Test
    fun retriesA500ThenSucceeds() = runTest {
        val f = Fx()
        f.server.failures["/api/2/episodes/testuser.json"] = 2
        f.sync.syncNow() // must not throw
        assertEquals(3, f.server.paths().count { it == "/api/2/episodes/testuser.json" })
    }

    @Test
    fun givesUpAfterThreeFailures() = runTest {
        val f = Fx()
        f.server.failures["/api/2/auth/testuser/login.json"] = 5
        val error = assertFailsWith<GpodderError> { f.sync.syncNow() }
        assertTrue("unreachable" in error.message!!, error.message!!)
    }

    @Test
    fun aBadPasswordIsReportedAsSuch() = runTest {
        val f = Fx()
        f.server.failStatus = HttpStatusCode.Unauthorized
        f.server.failures["/api/2/auth/testuser/login.json"] = 5
        assertFailsWith<GpodderAuthError> { f.sync.syncNow() }
    }

    // ---------------------------------------------------------------- dry run

    /** `--dry-run-sync`: reads happen for real, writes are logged instead. */
    @Test
    fun dryRunSendsNoWritesAndKeepsTheOutbox() = runTest {
        val f = Fx(dryRun = true)
        f.lib.repo.enqueueAction(Library.FEED, "https://cdn.example.com/ep001.mp3", "play",
            timestamp = "2026-07-16T10:00:00", started = 0, position = 10, total = 3000)

        f.sync.syncNow()

        assertFalse(
            f.server.posted("/api/2/episodes/testuser.json"),
            "no episode-action upload should reach the server",
        )
        assertEquals(1, f.lib.repo.outboxActions().size, "the outbox must survive a dry run")
    }
}
