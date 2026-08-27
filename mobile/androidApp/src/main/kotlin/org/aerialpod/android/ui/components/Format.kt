package org.aerialpod.android.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The same readings the desktop's `episode_list.py` produces, so an episode
 * looks the same on both. Dates render in the device's zone from a UTC epoch,
 * which is what the column holds.
 */

/**
 * Cached per locale rather than built once.
 *
 * A formatter holding the locale it was created with keeps formatting in the
 * old language after the user changes the device's — the process is not
 * restarted for a locale change, so a `val` captured at class-init is stale for
 * the rest of the session.
 */
private var cachedLocale: Locale? = null
private var cachedFormat: DateTimeFormatter? = null

private fun dateFormat(): DateTimeFormatter {
    val locale = Locale.getDefault()
    val cached = cachedFormat
    if (cached != null && cachedLocale == locale) return cached
    return DateTimeFormatter.ofPattern("MMM d, yyyy", locale).also {
        cachedLocale = locale
        cachedFormat = it
    }
}

fun formatDate(epochSecs: Long?): String {
    if (epochSecs == null || epochSecs <= 0L) return ""
    return runCatching {
        Instant.ofEpochSecond(epochSecs).atZone(ZoneId.systemDefault()).format(dateFormat())
    }.getOrDefault("")
}

fun formatDuration(secs: Long?): String {
    val total = secs ?: return ""
    if (total <= 0L) return ""
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** "23 min left" — more useful on a half-played episode than the total. */
fun formatRemaining(positionSecs: Long, totalSecs: Long): String {
    val left = totalSecs - positionSecs
    if (totalSecs <= 0L || left <= 0L) return ""
    val minutes = (left + 59) / 60
    return if (minutes >= 60) {
        val h = minutes / 60
        val m = minutes % 60
        if (m == 0L) "${h}h left" else "${h}h ${m}m left"
    } else {
        "$minutes min left"
    }
}

/**
 * Strip the markup a feed's description arrives wrapped in.
 *
 * Show notes are HTML far more often than not, and rendering the raw source is
 * worse than rendering nothing. This is not a parser — it drops tags, decodes
 * the handful of entities that actually turn up, and collapses whitespace.
 *
 * Tags become whitespace rather than nothing, which matters more than it
 * sounds: `<p>One.</p><p>Two.</p>` stripped naively reads "One.Two.", and real
 * show notes are mostly paragraphs and link lists.
 */
fun plainText(html: String?): String {
    val source = html ?: return ""
    val out = StringBuilder(source.length)
    val tag = StringBuilder()
    var inTag = false
    for (ch in source) {
        when {
            ch == '<' -> { inTag = true; tag.setLength(0) }
            ch == '>' && inTag -> {
                inTag = false
                out.append(if (breaksLine(tag.toString())) '\n' else ' ')
            }
            inTag -> tag.append(ch)
            else -> out.append(ch)
        }
    }
    return out.toString()
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        // One line per block boundary. A closing tag and the next opening one
        // each emit a break, so without this every paragraph gains a blank line
        // and a bulleted list turns into double-spaced prose.
        .replace(Regex("[ \t\r]+"), " ")
        .replace(Regex(" ?\n ?"), "\n")
        .replace(Regex("\n{2,}"), "\n")
        .trim()
}

/** The tags whose absence would run two sentences together. */
private fun breaksLine(tag: String): Boolean {
    val name = tag.trimStart('/').takeWhile { it.isLetterOrDigit() }.lowercase()
    return name in BLOCK_TAGS
}

private val BLOCK_TAGS = setOf(
    "p", "br", "div", "li", "ul", "ol", "tr", "table", "blockquote",
    "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "hr", "pre",
)
