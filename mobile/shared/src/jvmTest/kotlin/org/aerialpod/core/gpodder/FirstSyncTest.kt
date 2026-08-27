package org.aerialpod.core.gpodder

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.aerialpod.core.queue.Library
import org.aerialpod.core.sync.Matcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signing in on a device that has nothing yet.
 *
 * This is the ordinary case on a phone and a rare one on the desktop, which is
 * why it went unnoticed: the sync cycle pulls episode actions *before* it pulls
 * subscriptions, and the episodes those actions refer to do not exist until the
 * feeds have been fetched — which happens after the cycle, from
 * `AerialPodCore.subscriptionsAdded`.
 *
 * So every "I played this" the account holds is discarded on the very sync that
 * was supposed to bring it over, the cursor advances past it, and it never
 * comes back. The phone then thinks nothing has been played and queues the lot.
 */
class FirstSyncTest {

    private val feed = "https://example.com/feed.xml"
    private val media = "https://example.com/ep1.mp3"

    /**
     * Honours `since`, which is the whole point: gpodder returns only what
     * changed after the cursor. A mock that replays history regardless would
     * hide exactly the bug this file is about.
     */
    private class Server(val feed: String, val media: String) {
        var cursorAsked: Long? = null
        private val actionStamp = 500L
        fun engine() = MockEngine { request ->
            val path = request.url.encodedPath
            var sinceAsked = 0L
            if (path.contains("/api/2/episodes/") && request.method.value == "GET") {
                sinceAsked = request.url.parameters["since"]?.toLongOrNull() ?: 0L
                cursorAsked = sinceAsked
            }
            val body = when {
                path.contains("/auth/") -> "{}"
                path.contains("/api/2/devices/") -> "{}"
                path.contains("/api/2/sync-devices/") -> "{}"
                // The account knows about one subscription…
                path.contains("/api/2/subscriptions/") ->
                    """{"add": ["$feed"], "remove": [], "timestamp": 200}"""
                path == "/subscriptions/testuser.json" -> """["$feed"]"""
                // …and that its only episode was played to the end. Once the
                // caller's cursor is past that, the server has nothing more.
                path.contains("/api/2/episodes/") -> if (sinceAsked >= actionStamp) {
                    """{"actions": [], "timestamp": $actionStamp}"""
                } else {
                    """{"actions": [{"podcast": "$feed", "episode": "$media",
                        "action": "play", "timestamp": "2026-08-01T10:00:00",
                        "started": 0, "position": 1800, "total": 1800}],
                        "timestamp": $actionStamp}"""
                }
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private fun fixture(): Triple<Library, GpodderSync, Server> {
        val server = Server(feed, media)
        // A genuinely empty install: no podcasts, no episodes.
        val lib = Library()
        val sync = GpodderSync(
            repo = lib.repo,
            matcher = Matcher(lib.repo),
            deviceCaption = "Pixel 10 Pro XL",
            clientProvider = {
                GpodderClient(
                    username = "testuser", password = "hunter2",
                    http = HttpClient(server.engine()), now = { 1_700_000_000L },
                    backoff = { },
                )
            },
            now = { 1_700_000_000L },
        )
        return Triple(lib, sync, server)
    }

    /** The feed arriving, as `AerialPodCore` does it once a subscription lands. */
    private fun Library.fetchFeed(podcastId: Long) {
        db.episodesQueries.insertEpisodeWithState(
            podcast_id = podcastId, guid = "guid-1", media_url = media,
            title = "The only episode", description = "", pub_date = 1_700_000_000L,
            duration_secs = 1800, mime = "audio/mpeg", file_size = null,
            image_url = null, state = "new",
        )
    }

    @Test
    fun theFirstSyncBringsTheSubscriptionOver() = runTest {
        val (lib, sync, _) = fixture()

        val result = sync.syncNow()

        assertEquals(1, result.subscriptionsAdded.size)
        assertEquals(feed, lib.repo.podcastById(result.subscriptionsAdded.single())?.feed_url)
    }

    @Test
    fun aPlayedEpisodeSurvivesTheFirstSync() = runTest {
        val (lib, sync, _) = fixture()

        // Sync one: subscription arrives, feed has not been fetched yet.
        val first = sync.syncNow()
        lib.fetchFeed(first.subscriptionsAdded.single())

        // Sync two: the episodes exist now, so the account's history must land.
        sync.syncNow()

        val episode = lib.repo.db.episodesQueries.episodeByMediaUrl(
            first.subscriptionsAdded.single(), media,
        ).executeAsOne()
        assertEquals(1800, episode.position_secs, "the position the account holds")
        assertEquals(
            "played", episode.state,
            "an episode played to the end on another device must not come back as unplayed",
        )
    }

    @Test
    fun anEpisodePlayedElsewhereDoesNotEnterTheQueue() = runTest {
        val (lib, sync, _) = fixture()
        val first = sync.syncNow()
        lib.fetchFeed(first.subscriptionsAdded.single())
        sync.syncNow()

        lib.queue.reconcile()

        assertTrue(
            lib.queue.episodes().isEmpty(),
            "the queue is derived from episode state — a finished episode has no business in it",
        )
    }

    @Test
    fun theCursorDoesNotRunAheadOfALibraryThatIsNotThereYet() = runTest {
        val (_, sync, server) = fixture()

        sync.syncNow()

        // Advancing here is what makes the loss permanent: the next sync asks
        // for everything *after* the actions it just threw away.
        assertEquals(
            0L, server.cursorAsked,
            "the first pull asks from the beginning",
        )
        assertEquals(
            0L, sync.actionsCursor(),
            "and must not advance past actions it could not apply for want of a library",
        )
    }
}
