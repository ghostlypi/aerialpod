package org.aerialpod.core.feeds

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import org.aerialpod.core.db.Repo
import org.aerialpod.core.epochSeconds

/**
 * Fetching and storing one podcast's feed — the port of `feeds/fetcher.py`.
 *
 * Conditional GET with ETag/Last-Modified; episodes identified by GUID falling
 * back to the enclosure URL, with a changed enclosure recorded as an alias so
 * the gpodder matching ladder keeps working across the change.
 */
class FeedFetcher(
    private val repo: Repo,
    private val http: HttpClient,
    private val now: () -> Long = ::epochSeconds,
) {
    private val db get() = repo.db

    class FeedException(message: String) : Exception(message)

    /** Fetch one podcast's feed; returns the number of genuinely new episodes. */
    suspend fun fetchAndStore(podcastId: Long): Int {
        val podcast = repo.podcastById(podcastId) ?: return 0

        // First fetch of a podcast: the back catalogue is NOT "new" — only
        // episodes appearing in later refreshes are (matching AntennaPod). The
        // single most recent one is left 'new' so a fresh subscription surfaces.
        val firstFetch = podcast.last_refresh == null

        val response = try {
            http.get(podcast.feed_url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                podcast.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                podcast.http_last_modified?.let { header(HttpHeaders.IfModifiedSince, it) }
            }
        } catch (exc: FeedException) {
            throw exc
        } catch (exc: Exception) {
            throw FeedException("${podcast.feed_url}: ${exc.message ?: "unreachable"}")
        }

        if (response.status.value == 304) {
            db.podcastsQueries.setPodcastLastRefresh(now(), podcast.id)
            return 0
        }
        if (response.status.value >= 400) {
            throw FeedException("${podcast.feed_url}: HTTP ${response.status.value}")
        }

        val parsed = FeedParser.parse(response.bodyAsText())
            ?: throw FeedException("${podcast.feed_url}: unparseable feed")
        if (parsed.entries.isEmpty() && parsed.title == null) {
            throw FeedException("${podcast.feed_url}: no channel and no items")
        }

        db.podcastsQueries.updatePodcastMeta(
            title = parsed.title ?: podcast.title,
            description = parsed.description ?: podcast.description,
            image_url = parsed.imageUrl ?: podcast.image_url,
            website = parsed.website ?: podcast.website,
            etag = response.headers[HttpHeaders.ETag],
            http_last_modified = response.headers[HttpHeaders.LastModified],
            last_refresh = now(),
            id = podcast.id,
        )

        var newCount = 0
        db.transaction {
            for (entry in parsed.entries) {
                // No enclosure, nothing to play — the desktop skips these too.
                val mediaUrl = entry.mediaUrl?.takeIf { it.isNotBlank() } ?: continue
                val guid = entry.guid?.takeIf { it.isNotBlank() } ?: mediaUrl

                val existing = db.episodesQueries.episodeByGuid(podcast.id, guid)
                    .executeAsOneOrNull()
                if (existing == null) {
                    db.episodesQueries.insertEpisodeWithState(
                        podcast_id = podcast.id,
                        guid = guid,
                        media_url = mediaUrl,
                        title = entry.title,
                        description = entry.description ?: "",
                        pub_date = entry.pubDate,
                        duration_secs = entry.durationSecs,
                        mime = entry.mime,
                        file_size = entry.fileSize,
                        image_url = entry.imageUrl,
                        state = if (firstFetch) "archived" else "new",
                    )
                    if (!firstFetch) newCount++
                } else {
                    // A changed enclosure URL is aliased before it is overwritten,
                    // so an action referring to the old one still resolves.
                    if (existing.media_url != mediaUrl) {
                        db.episodesQueries.addAlias(existing.id, existing.media_url)
                    }
                    db.episodesQueries.updateEpisodeFromFeed(
                        mediaUrl = mediaUrl,
                        title = entry.title,
                        description = entry.description ?: "",
                        pubDate = entry.pubDate,
                        durationSecs = entry.durationSecs,
                        mime = entry.mime,
                        fileSize = entry.fileSize,
                        imageUrl = entry.imageUrl,
                        id = existing.id,
                    )
                }
            }
            if (firstFetch) db.episodesQueries.markLatestArchivedAsNew(podcast.id)
        }
        return newCount
    }

    companion object {
        const val USER_AGENT = "AerialPod/0.1 (+https://github.com/aerialpod)"
    }
}
