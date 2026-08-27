package org.aerialpod.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
 * `reportPosition` — the port of the desktop's `hub.report_position`.
 *
 * The `final` flag is the whole design: every report persists, and only a final
 * one enqueues a gpodder action. Mobile additionally drops the desktop's
 * per-tick nudge to peers, because `docs/mobile-lan-sync.md` lists a playback
 * tick as doing **nothing**.
 */
class ReportPositionTest {

    private val clock = 1_700_000_000L

    private fun core(): AerialPodCore = AerialPodCore(
        database = openDatabase(JvmDriverFactory()),
        secretStore = object : SecretStore {
            private var v: ByteArray? = null
            override fun loadSecret() = v
            override fun storeSecret(value: ByteArray) { v = value }
        },
        credentials = object : GpodderCredentialStore {
            override fun load(): GpodderCredentials? = null
            override fun save(credentials: GpodderCredentials) = Unit
            override fun clear() = Unit
        },
        httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
        deviceCaption = "Test",
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        now = { clock },
    )

    private fun AerialPodCore.seedEpisode(): Long {
        val podcastId = repo.upsertPodcast("https://example.com/feed.xml", syncState = "clean")
        repo.db.episodesQueries.insertEpisodeWithState(
            podcast_id = podcastId, guid = "guid-1",
            media_url = "https://example.com/1.mp3", title = "One", description = "",
            pub_date = clock, duration_secs = 3600, mime = "audio/mpeg", file_size = null,
            image_url = null, state = "new",
        )
        return repo.db.podcastsQueries.lastInsertId().executeAsOne()
            .let { repo.db.episodesQueries.episodeByGuid(podcastId, "guid-1").executeAsOne().id }
    }

    @Test
    fun everyReportPersistsThePosition() {
        val core = core()
        val id = core.seedEpisode()

        core.reportPosition(id, position = 120, total = 3600, final = false)

        val episode = core.repo.episodeById(id)!!
        assertEquals(120, episode.position_secs)
        assertEquals(3600, episode.total_secs)
        assertEquals(clock, episode.position_updated_at)
    }

    @Test
    fun onlyAFinalReportEnqueuesAGpodderAction() {
        val core = core()
        val id = core.seedEpisode()

        core.reportPosition(id, position = 120, total = 3600, final = false)
        assertEquals(0, core.repo.outboxActions().size, "a tick must not push an action")

        core.reportPosition(id, position = 300, total = 3600, final = true)
        val actions = core.repo.outboxActions()
        assertEquals(1, actions.size)
        assertEquals("play", actions.single().action)
        assertEquals(300L, actions.single().position)
        assertEquals(3600L, actions.single().total)
    }

    @Test
    fun aFinalReportAtZeroIsNotAnAction() {
        // Pausing before anything played is not a listening event, and pushing
        // it would tell the other devices to rewind to the start.
        val core = core()
        val id = core.seedEpisode()

        core.reportPosition(id, position = 0, total = 3600, final = true)

        assertEquals(0, core.repo.outboxActions().size)
    }

    @Test
    fun aDurationArrivingBeforeAnyPositionStillGetsStored() {
        // ExoPlayer reports duration as soon as the media is prepared, which is
        // usually before the first second has played. The total is worth
        // keeping on its own: it is what makes isFinished() able to answer.
        val core = core()
        val id = core.seedEpisode()

        core.reportPosition(id, position = 0, total = 2400, final = false)

        val episode = core.repo.episodeById(id)!!
        assertEquals(2400, episode.total_secs)
        assertEquals(0, episode.position_secs)
    }

    @Test
    fun anUnknownEpisodeIsIgnoredRatherThanThrowing() {
        val core = core()
        core.reportPosition(9999, position = 10, total = 20, final = true)
        assertTrue(core.repo.outboxActions().isEmpty())
    }

    @Test
    fun laterReportsOverwriteEarlierOnes() {
        val core = core()
        val id = core.seedEpisode()

        core.reportPosition(id, position = 100, total = 3600, final = false)
        core.reportPosition(id, position = 250, total = 3600, final = false)

        assertEquals(250, core.repo.episodeById(id)!!.position_secs)
    }
}
