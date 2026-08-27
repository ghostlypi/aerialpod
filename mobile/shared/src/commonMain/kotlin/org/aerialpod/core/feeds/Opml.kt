package org.aerialpod.core.feeds

import org.aerialpod.core.db.Repo
import org.aerialpod.core.epochSeconds
import org.aerialpod.core.iso8601Utc

/**
 * OPML import/export for subscriptions — the port of `feeds/opml.py`.
 *
 * Import walks every `<outline>` at any depth, because exporters disagree about
 * whether to group feeds under category outlines, and a subscription nested one
 * level down is still a subscription.
 *
 * Note the attribute names are read lowercase (`xmlurl`, `htmlurl`): the parser
 * folds case so `xmlUrl` and `XMLURL` are the same key.
 */
object Opml {

    /** Subscribe to every feed in the document; returns the new podcast ids. */
    fun import(source: String, repo: Repo): List<Long> {
        val root = parseXml(source) ?: return emptyList()
        val added = mutableListOf<Long>()
        for (outline in root.descendants("outline")) {
            val url = outline.attr("xmlurl") ?: continue
            if (repo.podcastByFeedUrl(url) != null) continue
            val id = repo.upsertPodcast(url)
            val title = outline.attr("title") ?: outline.attr("text")
            if (title != null) {
                val podcast = repo.podcastById(id)
                repo.db.podcastsQueries.updatePodcastMeta(
                    title = title,
                    description = podcast?.description,
                    image_url = podcast?.image_url,
                    website = podcast?.website,
                    etag = podcast?.etag,
                    http_last_modified = podcast?.http_last_modified,
                    last_refresh = podcast?.last_refresh,
                    id = id,
                )
            }
            added += id
        }
        return added
    }

    /** The subscription list as an OPML document. */
    fun export(repo: Repo, now: () -> Long = ::epochSeconds): String {
        val podcasts = repo.subscribedPodcasts()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<opml version=\"2.0\">\n")
            append("  <head>\n")
            append("    <title>AerialPod subscriptions</title>\n")
            append("    <dateCreated>").append(iso8601Utc(now())).append("Z</dateCreated>\n")
            append("  </head>\n")
            append("  <body>\n")
            for (podcast in podcasts) {
                val title = repo.displayTitle(podcast)
                append("    <outline type=\"rss\"")
                append(" text=\"").append(escapeAttr(title)).append('"')
                append(" title=\"").append(escapeAttr(title)).append('"')
                append(" xmlUrl=\"").append(escapeAttr(podcast.feed_url)).append('"')
                podcast.website?.takeIf { it.isNotBlank() }?.let {
                    append(" htmlUrl=\"").append(escapeAttr(it)).append('"')
                }
                append("/>\n")
            }
            append("  </body>\n")
            append("</opml>\n")
        }
    }

    private fun escapeAttr(value: String): String = buildString(value.length) {
        for (c in value) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
