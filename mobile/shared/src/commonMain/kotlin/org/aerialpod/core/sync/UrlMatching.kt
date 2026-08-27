package org.aerialpod.core.sync

/**
 * URL identity for episode matching — the port of `gpodder/matching.py`.
 *
 * The #1 interop failure mode: dynamic-ad CDNs rotate enclosure URLs per
 * listener, so the same episode is a different URL on the phone than it is on
 * the desktop. Peers therefore address episodes by (feed, GUID) first and fall
 * back to this ladder, which strips tracking wrappers and compares what is
 * underneath.
 *
 * `urlparse` is hand-rolled here rather than borrowed from a URL library
 * because the desktop's behaviour *is* CPython's `urlparse`, down to details
 * like an empty path for a bare host — and a library that normalises more
 * eagerly would quietly stop agreeing with it.
 */
object UrlMatching {

    // Known tracking/redirect prefixes: strip repeatedly, compare the innermost
    // URL. Do NOT blindly strip query strings — some CDNs require them.
    private val TRACKER_PATTERNS = listOf(
        """^https?://(?:www\.)?podtrac\.com/pts/redirect\.[a-z0-9]+/""",
        """^https?://dts\.podtrac\.com/redirect\.[a-z0-9]+/""",
        """^https?://chtbl\.com/track/[^/]+/""",
        """^https?://pdst\.fm/e/""",
        """^https?://mgln\.ai/e/[^/]+/""",
        """^https?://pfx\.vpixl\.com/[^/]+/""",
        """^https?://claritaspod\.com/measure/""",
        """^https?://pscrb\.fm/rss/p/""",
        """^https?://prfx\.byspotify\.com/e/""",
        """^https?://arttrk\.com/p/[^/]+/""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val EMBEDDED_URL = Regex("""https?%3A%2F%2F.+$""", RegexOption.IGNORE_CASE)

    /** Repeatedly unwrap tracking prefixes; returns the innermost URL. */
    fun stripTrackers(url: String): String {
        var current = url
        repeat(6) { // trackers chain; bound the loop
            var stripped = current
            for (pattern in TRACKER_PATTERNS) {
                val match = pattern.find(stripped) ?: continue
                var rest = stripped.substring(match.range.last + 1)
                // Re-add a scheme if the inner URL lost it (host/path form).
                if (!rest.startsWith("http://") && !rest.startsWith("https://")) {
                    rest = "https://$rest"
                }
                stripped = rest
            }
            if (stripped == current) return current
            current = stripped
        }
        return current
    }

    /** Lowercase scheme+host, force https, strip fragment. Keep query. */
    fun normalize(url: String): String {
        val stripped = stripTrackers(url)
        val parsed = parse(stripped) ?: return stripped
        val port = if (parsed.port != null && parsed.port != 80 && parsed.port != 443) {
            ":${parsed.port}"
        } else {
            ""
        }
        val query = if (parsed.query.isNotEmpty()) "?${parsed.query}" else ""
        return "https://${parsed.host}$port${parsed.path}$query"
    }

    /**
     * All normalized identities of a URL, including a percent-encoded inner URL
     * embedded in the path (anchor.fm style:
     * `.../play/123/https%3A%2F%2Fcdn.example.com%2Fep.mp3`).
     */
    fun variants(url: String): Set<String> {
        val out = mutableSetOf(normalize(url))
        EMBEDDED_URL.find(url)?.let { out.add(normalize(percentDecode(it.value))) }
        return out
    }

    fun basename(url: String): String {
        val path = parse(stripTrackers(url))?.path ?: return ""
        return path.substringAfterLast('/')
    }

    // ------------------------------------------------------------ parsing

    private class Parsed(val host: String, val port: Int?, val path: String, val query: String)

    /**
     * The subset of `urlparse` this file needs: host (lowercased), port, path,
     * query. Returns null for anything that does not look like a URL, which is
     * the caller's cue to pass the string through untouched — same as the
     * desktop's `except ValueError: return url`.
     */
    private fun parse(url: String): Parsed? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        var rest = url.substring(schemeEnd + 3)

        rest = rest.substringBefore('#')            // fragments are dropped
        val query = rest.substringAfter('?', "")
        rest = rest.substringBefore('?')

        val slash = rest.indexOf('/')
        var authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else ""

        // userinfo is not part of an episode's identity
        authority.lastIndexOf('@').let { if (it >= 0) authority = authority.substring(it + 1) }

        val host: String
        val port: Int?
        if (authority.startsWith("[")) {            // [::1]:8080
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1).lowercase()
            port = authority.substring(close + 1).removePrefix(":").toIntOrNull()
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon).lowercase()
                port = authority.substring(colon + 1).toIntOrNull()
            } else {
                host = authority.lowercase()
                port = null
            }
        }
        return Parsed(host, port, path, query)
    }

    /**
     * Percent-decoding, UTF-8 aware.
     *
     * Runs of literal text are encoded in one go rather than character by
     * character, so a multi-byte character that was never escaped survives
     * instead of being mangled into replacement bytes.
     */
    internal fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val bytes = mutableListOf<Byte>()
        var i = 0
        var literalStart = 0

        fun flushLiteral(end: Int) {
            if (end > literalStart) {
                for (b in text.substring(literalStart, end).encodeToByteArray()) bytes.add(b)
            }
        }

        while (i < text.length) {
            if (text[i] == '%' && i + 2 < text.length) {
                val hi = text[i + 1].digitToIntOrNull(16)
                val lo = text[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    flushLiteral(i)
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                    literalStart = i
                    continue
                }
            }
            i++
        }
        flushLiteral(text.length)
        return bytes.toByteArray().decodeToString()
    }
}
