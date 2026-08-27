package org.aerialpod.core.sync

import org.aerialpod.core.queue.Library
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The URL matching ladder — the port of `tests/test_matching.py`.
 *
 * This is the #1 interop failure mode in the whole project: ad-injecting CDNs
 * rewrite the enclosure URL per listener, so the same episode is a different
 * URL on every device. Get this wrong and positions silently stop crossing.
 */
class MatchingTest {

    private companion object {
        const val MGLN = "https://mgln.ai/e/2/dts.podtrac.com/redirect.mp3/" +
            "stitcher.simplecastaudio.com/3bb687b0/episodes/abc-123/audio/128/default.mp3" +
            "?aid=rss_feed&feed=BqbsxVfO"
        const val INNER = "https://stitcher.simplecastaudio.com/3bb687b0/episodes/abc-123" +
            "/audio/128/default.mp3?aid=rss_feed&feed=BqbsxVfO"
    }

    // ---------------------------------------------------------------- url shapes

    @Test
    fun stripsChainedTrackers() {
        assertEquals(INNER, UrlMatching.stripTrackers(MGLN))
    }

    @Test
    fun leavesAPlainUrlUntouched() {
        val url = "https://atp.fm/audio/xyz/atp700.mp3"
        assertEquals(url, UrlMatching.stripTrackers(url))
    }

    @Test
    fun normalizesSchemeAndHostCase() {
        assertEquals(
            "https://cdn.example.com/Ep1.mp3",
            UrlMatching.normalize("HTTP://CDN.Example.com/Ep1.mp3"),
        )
    }

    /** Do NOT strip query strings — some CDNs require them to serve the file. */
    @Test
    fun keepsTheQueryString() {
        assertTrue("?aid=rss_feed" in UrlMatching.normalize(INNER))
    }

    @Test
    fun dropsTheFragmentAndDefaultPorts() {
        assertEquals(
            "https://cdn.example.com/ep1.mp3",
            UrlMatching.normalize("https://cdn.example.com:443/ep1.mp3#t=30"),
        )
        assertEquals(
            "https://cdn.example.com:8080/ep1.mp3",
            UrlMatching.normalize("http://cdn.example.com:8080/ep1.mp3"),
        )
    }

    @Test
    fun passesThroughSomethingThatIsNotAUrl() {
        assertEquals("not a url", UrlMatching.normalize("not a url"))
    }

    // ---------------------------------------------------------------- podcasts

    @Test
    fun matchesAPodcastAcrossSchemes() {
        val lib = Library()
        val id = lib.addPodcast()
        val matcher = Matcher(lib.repo)
        assertEquals(id, matcher.matchPodcast("http://example.com/feed.xml")?.id)
    }

    // ---------------------------------------------------------------- episodes

    @Test
    fun matchesAnExactEnclosureUrl() {
        val lib = Library()
        val podcast = lib.addPodcast()
        val id = lib.makeEpisode(podcast, 1)
        val matcher = Matcher(lib.repo)
        val found = matcher.matchEpisode(lib.repo.podcastById(podcast)!!, "https://cdn.example.com/ep001.mp3")
        assertEquals(id, found?.id)
    }

    /** Another app reports a tracker-wrapped URL for an episode we stored bare. */
    @Test
    fun matchesThroughATrackerPrefixAndRecordsAnAlias() {
        val lib = Library()
        val podcast = lib.addPodcast()
        val id = lib.makeEpisode(podcast, 2)
        val matcher = Matcher(lib.repo)
        val p = lib.repo.podcastById(podcast)!!
        val wrapped = "https://dts.podtrac.com/redirect.mp3/cdn.example.com/ep002.mp3"

        assertEquals(id, matcher.matchEpisode(p, wrapped)?.id)
        // the alias is recorded, so the next lookup is an index hit
        assertNotNull(lib.db.episodesQueries.episodeByAlias(wrapped, podcast).executeAsOneOrNull())
        assertEquals(id, matcher.matchEpisode(p, wrapped)?.id)
    }

    @Test
    fun fallsBackToThePathBasename() {
        val lib = Library()
        val podcast = lib.addPodcast()
        val id = lib.makeEpisode(podcast, 3)
        val matcher = Matcher(lib.repo)
        val found = matcher.matchEpisode(
            lib.repo.podcastById(podcast)!!,
            "https://other-cdn.example.net/media/ep003.mp3",
        )
        assertEquals(id, found?.id)
    }

    @Test
    fun refusesToGuessWhenNothingMatches() {
        val lib = Library()
        val podcast = lib.addPodcast()
        lib.makeEpisode(podcast, 4)
        val matcher = Matcher(lib.repo)
        assertNull(
            matcher.matchEpisode(
                lib.repo.podcastById(podcast)!!,
                "https://cdn.example.com/nonexistent.mp3",
            )
        )
    }

    /** anchor.fm-style action URLs embed the CDN URL percent-encoded in the path. */
    @Test
    fun matchesAPercentEncodedInnerUrl() {
        val lib = Library()
        val podcast = lib.addPodcast()
        lib.db.episodesQueries.insertEpisode(
            podcast_id = podcast,
            guid = "g-anchor",
            media_url = "https://anchor.fm/s/xyz/podcast/play/118917422/" +
                "https%3A%2F%2Fd3ctxlq1ktw2nl.cloudfront.net%2Fstaging%2Fep5.mp3",
            title = "Anchor Ep", description = null, pub_date = null,
            duration_secs = null, mime = null, file_size = null, image_url = null,
        )
        val id = lib.db.podcastsQueries.lastInsertId().executeAsOne()
        val matcher = Matcher(lib.repo)

        // another device reports the bare decoded CDN URL
        val found = matcher.matchEpisode(
            lib.repo.podcastById(podcast)!!,
            "https://d3ctxlq1ktw2nl.cloudfront.net/staging/ep5.mp3",
        )
        assertEquals(id, found?.id)
    }

    @Test
    fun percentDecodingHandlesMultiByteCharacters() {
        assertEquals("/naïve/ep.mp3", UrlMatching.percentDecode("/na%C3%AFve/ep.mp3"))
        assertEquals("/naïve/ep.mp3", UrlMatching.percentDecode("/naïve/ep.mp3"))
    }

    // ---------------------------------------------------------------- resolution

    @Test
    fun resolvesByGuidBeforeUrl() {
        val lib = Library()
        val podcast = lib.addPodcast()
        val id = lib.makeEpisode(podcast, 1)
        val matcher = Matcher(lib.repo)
        // A URL that matches nothing; only the GUID can find it.
        val found = matcher.resolveEpisode(
            feed = Library.FEED,
            guid = "guid-$podcast-1",
            media = "https://totally-different.example/x.mp3",
        )
        assertEquals(id, found?.id)
    }
}
