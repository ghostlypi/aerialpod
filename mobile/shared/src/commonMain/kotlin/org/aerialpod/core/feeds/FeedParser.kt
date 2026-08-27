package org.aerialpod.core.feeds

import org.aerialpod.core.daysFromCivil

/**
 * RSS and Atom, reduced to what the episode columns need.
 *
 * The shape mirrors what feedparser hands the desktop, so `FeedFetcher` can be
 * a line-for-line port of `fetch_and_store` rather than a reinterpretation.
 */
data class ParsedFeed(
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val website: String? = null,
    val entries: List<ParsedEntry> = emptyList(),
)

data class ParsedEntry(
    val guid: String? = null,
    val mediaUrl: String? = null,
    val title: String? = null,
    val description: String? = null,
    val pubDate: Long? = null,
    val durationSecs: Long? = null,
    val mime: String? = null,
    val fileSize: Long? = null,
    val imageUrl: String? = null,
)

private data class Enclosure(val href: String, val type: String?, val length: Long?)

object FeedParser {

    fun parse(source: String): ParsedFeed? {
        val root = parseXml(source) ?: return null
        return when {
            // <rss><channel>, and <rdf:RDF> for the handful of RSS 1.0 feeds left
            root.name == "rss" || root.name.endsWith(":rdf") || root.name == "rdf" ->
                parseRss(root)
            root.name == "feed" -> parseAtom(root)
            // Some servers serve a bare <channel>. Salvage it rather than refuse.
            root.name == "channel" -> parseChannel(root)
            else -> root.child("channel")?.let(::parseChannel)
        }
    }

    private fun parseRss(root: XmlNode): ParsedFeed? {
        val channel = root.child("channel") ?: return null
        // RSS 1.0 puts <item> as a sibling of <channel>, not inside it.
        val loose = root.childrenNamed("item")
        return parseChannel(channel, extraItems = loose)
    }

    private fun parseChannel(channel: XmlNode, extraItems: List<XmlNode> = emptyList()): ParsedFeed {
        val items = channel.childrenNamed("item") + extraItems
        return ParsedFeed(
            title = channel.textOf("title"),
            description = channel.textOf("description", "itunes:summary", "itunes:subtitle"),
            imageUrl = channelImage(channel),
            website = channel.textOf("link"),
            entries = items.map(::parseItem),
        )
    }

    /** RSS puts the artwork in `<image><url>`; iTunes puts it in an attribute. */
    private fun channelImage(channel: XmlNode): String? =
        channel.child("image")?.textOf("url")
            ?: channel.child("itunes:image")?.attr("href")
            ?: channel.child("image")?.attr("href")

    private fun parseItem(item: XmlNode): ParsedEntry {
        val enclosures = item.childrenNamed("enclosure").mapNotNull(::readEnclosure)
        val chosen = chooseEnclosure(enclosures)
        return ParsedEntry(
            guid = item.textOf("guid", "id"),
            mediaUrl = chosen?.href,
            title = item.textOf("title"),
            description = item.textOf("description", "itunes:summary", "content:encoded"),
            pubDate = item.textOf("pubdate", "dc:date", "published")?.let(::parseFeedDate),
            durationSecs = parseDuration(item.textOf("itunes:duration")),
            mime = chosen?.type,
            fileSize = chosen?.length,
            imageUrl = item.child("itunes:image")?.attr("href"),
        )
    }

    private fun parseAtom(root: XmlNode): ParsedFeed = ParsedFeed(
        title = root.textOf("title"),
        description = root.textOf("subtitle", "summary"),
        imageUrl = root.textOf("logo", "icon") ?: root.child("itunes:image")?.attr("href"),
        website = root.childrenNamed("link")
            .firstOrNull { it.attr("rel") == null || it.attr("rel") == "alternate" }
            ?.attr("href"),
        entries = root.childrenNamed("entry").map { entry ->
            // Atom carries enclosures as links, which is how feedparser presents
            // them too — so the selection rule below stays the same.
            val enclosures = entry.childrenNamed("link")
                .filter { it.attr("rel") == "enclosure" }
                .mapNotNull { link ->
                    link.attr("href")?.let {
                        Enclosure(it, link.attr("type"), link.attr("length")?.toLongOrNull())
                    }
                }
            val chosen = chooseEnclosure(enclosures)
            ParsedEntry(
                guid = entry.textOf("id"),
                mediaUrl = chosen?.href,
                title = entry.textOf("title"),
                description = entry.textOf("summary", "content"),
                pubDate = entry.textOf("published", "updated")?.let(::parseFeedDate),
                durationSecs = parseDuration(entry.textOf("itunes:duration")),
                mime = chosen?.type,
                fileSize = chosen?.length,
                imageUrl = entry.child("itunes:image")?.attr("href"),
            )
        },
    )

    private fun readEnclosure(node: XmlNode): Enclosure? {
        val href = node.attr("url") ?: node.attr("href") ?: return null
        return Enclosure(href, node.attr("type"), node.attr("length")?.toLongOrNull())
    }

    /**
     * The first enclosure whose type starts with `audio/`, or the last one
     * otherwise.
     *
     * Matches the desktop exactly, including the fallback: an item that offers a
     * video and a chapters file but nothing marked audio still yields something
     * playable rather than being skipped.
     */
    private fun chooseEnclosure(enclosures: List<Enclosure>): Enclosure? {
        enclosures.firstOrNull { it.type?.startsWith("audio/") == true }?.let { return it }
        return enclosures.lastOrNull()
    }
}

// ---------------------------------------------------------------- duration

/** `itunes:duration` is either seconds or `HH:MM:SS` / `MM:SS`. */
internal fun parseDuration(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (':' in text) {
        var seconds = 0L
        for (part in text.split(':')) {
            val n = part.trim().toLongOrNull() ?: return null
            seconds = seconds * 60 + n
        }
        return seconds
    }
    return text.toDoubleOrNull()?.toLong()
}

// ---------------------------------------------------------------- dates

private val MONTHS = listOf(
    "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
)

private val ZONES = mapOf(
    "gmt" to 0, "ut" to 0, "utc" to 0, "z" to 0,
    "est" to -5, "edt" to -4, "cst" to -6, "cdt" to -5,
    "mst" to -7, "mdt" to -6, "pst" to -8, "pdt" to -7,
)

/**
 * A publication date, in whatever shape the feed felt like.
 *
 * Handles RFC 822 (`Tue, 16 Jul 2024 10:00:00 +0100`) and RFC 3339
 * (`2024-07-16T10:00:00Z`), with or without seconds, and with named or numeric
 * zones. Returns null rather than guessing when nothing parses — a null pub_date
 * sorts an episode to the end of the queue, which is a far better failure than a
 * wrong date sorting it to the front.
 *
 * Unlike `parseIso8601Utc`, this **applies** the offset. That function
 * deliberately discards it to match the desktop's gpodder timestamp handling;
 * here the desktop uses feedparser, which converts properly. The two look
 * similar and must not be merged.
 */
fun parseFeedDate(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return parseRfc3339(text) ?: parseRfc822(text)
}

private fun parseRfc3339(text: String): Long? {
    if (text.length < 10 || text[4] != '-' || text[7] != '-') return null
    val year = text.substring(0, 4).toLongOrNull() ?: return null
    val month = text.substring(5, 7).toLongOrNull() ?: return null
    val day = text.substring(8, 10).toLongOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null

    var seconds = daysFromCivil(year, month, day) * 86_400L
    if (text.length < 16) return seconds // date only

    val rest = text.substring(11)
    val hour = rest.substring(0, 2).toLongOrNull() ?: return seconds
    val minute = rest.substring(3, 5).toLongOrNull() ?: return seconds
    val second = if (rest.length >= 8 && rest[5] == ':') rest.substring(6, 8).toLongOrNull() ?: 0 else 0
    seconds += hour * 3600 + minute * 60 + second

    return seconds - offsetSeconds(rest)
}

private fun parseRfc822(text: String): Long? {
    // Drop the optional leading day name, then split on whitespace.
    val body = text.substringAfter(',', text).trim()
    val parts = body.split(' ', '\t').filter { it.isNotEmpty() }
    if (parts.size < 3) return null

    val day = parts[0].toLongOrNull() ?: return null
    val month = MONTHS.indexOf(parts[1].lowercase().take(3)).takeIf { it >= 0 }?.plus(1)?.toLong()
        ?: return null
    var year = parts[2].toLongOrNull() ?: return null
    // Two-digit years still turn up; RFC 2822's rule is 00-49 => 2000s.
    if (year < 100) year += if (year < 50) 2000 else 1900
    if (day !in 1..31) return null

    var seconds = daysFromCivil(year, month, day) * 86_400L
    if (parts.size > 3 && ':' in parts[3]) {
        val time = parts[3].split(':')
        seconds += (time.getOrNull(0)?.toLongOrNull() ?: 0) * 3600
        seconds += (time.getOrNull(1)?.toLongOrNull() ?: 0) * 60
        seconds += time.getOrNull(2)?.toLongOrNull() ?: 0
    }
    val zone = parts.getOrNull(4) ?: ""
    return seconds - offsetSeconds(zone)
}

/** Seconds to subtract to reach UTC. Unrecognised or absent reads as UTC. */
private fun offsetSeconds(tail: String): Long {
    val sign = tail.lastIndexOfFirst { it == '+' || it == '-' }
    if (sign >= 0) {
        val digits = tail.substring(sign + 1).filter { it.isDigit() }
        if (digits.length >= 3) {
            val hours = digits.substring(0, digits.length - 2).toLongOrNull() ?: 0
            val minutes = digits.substring(digits.length - 2).toLongOrNull() ?: 0
            val magnitude = hours * 3600 + minutes * 60
            return if (tail[sign] == '-') -magnitude else magnitude
        }
    }
    val named = ZONES[tail.trim().lowercase()] ?: return 0
    return named * 3600L
}

/** The last `+`/`-` that could start a zone offset — not one inside a date. */
private fun String.lastIndexOfFirst(predicate: (Char) -> Boolean): Int {
    for (i in indices.reversed()) if (predicate(this[i])) return i
    return -1
}
