package org.aerialpod.core.sync

import org.aerialpod.core.db.Episodes
import org.aerialpod.core.db.Podcasts
import org.aerialpod.core.db.Repo

/**
 * Resolve a peer's (or gpodder's) episode reference to a local row.
 *
 * A successful match through the fuzzy rungs records an alias, so the next
 * lookup for that URL is an index hit rather than a table scan. That write is a
 * side effect of *reading*, which is why callers resolve before opening the
 * merge transaction — rolling the merge back must not also roll back what we
 * learned about the URL.
 */
class Matcher(private val repo: Repo) {

    fun matchPodcast(feedUrl: String): Podcasts? {
        repo.podcastByFeedUrl(feedUrl)?.let { return it }
        // scheme/case tolerance
        val wanted = UrlMatching.normalize(feedUrl)
        return repo.allPodcasts().firstOrNull { UrlMatching.normalize(it.feed_url) == wanted }
    }

    fun matchEpisode(podcast: Podcasts, episodeUrl: String): Episodes? {
        val db = repo.db

        // 1. exact media_url within podcast
        db.episodesQueries.episodeByMediaUrl(podcast.id, episodeUrl).executeAsOneOrNull()
            ?.let { return it }

        // 2. alias table
        db.episodesQueries.episodeByAlias(episodeUrl, podcast.id).executeAsOneOrNull()
            ?.let { return it }

        // 3. normalized comparison (tracker-stripped, case/scheme-tolerant,
        // including percent-encoded inner URLs)
        val wanted = UrlMatching.variants(episodeUrl)
        val all = db.episodesQueries.allEpisodesForPodcast(podcast.id).executeAsList()
        all.firstOrNull { UrlMatching.variants(it.media_url).any(wanted::contains) }?.let {
            repo.addAlias(it.id, episodeUrl) // exact next time
            return it
        }

        // 4. last resort: URL path basename within the podcast
        val base = UrlMatching.basename(episodeUrl)
        if (base.length > 5) { // avoid matching 'ep.mp3'-style stubs too eagerly
            val hits = all.filter { UrlMatching.basename(it.media_url) == base }
            if (hits.size == 1) {
                repo.addAlias(hits[0].id, episodeUrl)
                return hits[0]
            }
        }
        return null
    }

    /**
     * Find the local row for a peer's episode reference.
     *
     * GUID first: it is stable across devices, while enclosure URLs are
     * rewritten per-listener by ad-injecting CDNs. The URL ladder is the
     * fallback for feeds that ship no usable GUID.
     */
    fun resolveEpisode(feed: String?, guid: String?, media: String?): Episodes? {
        val podcast = matchPodcast(feed ?: return null) ?: return null
        if (!guid.isNullOrEmpty()) {
            repo.db.episodesQueries.episodeByGuid(podcast.id, guid).executeAsOneOrNull()
                ?.let { return it }
        }
        if (media.isNullOrEmpty()) return null
        return matchEpisode(podcast, media)
    }
}
