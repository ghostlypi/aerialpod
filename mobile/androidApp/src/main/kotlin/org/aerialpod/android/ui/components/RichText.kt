package org.aerialpod.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration

private val HTML_TAG = Regex("<[a-zA-Z/][^>]*>")

// Bare URLs in show notes. Trailing punctuation is left off so "see
// https://example.com." links the site, not the full stop.
private val BARE_URL = Regex("""https?://[^\s<>"]+[^\s<>".,;:!?)\]'”’]""")

/**
 * Feed HTML (a podcast description or an episode's show notes) as text with
 * tappable links. Links open through LocalUriHandler — the browser.
 *
 * Plenty of feeds send plain text with newlines and bare URLs rather than
 * HTML, so plain text keeps its line breaks and any URL in the text becomes a
 * link whether or not the feed wrapped it in an anchor.
 */
@Composable
fun rememberRichText(html: String?): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(html, linkColor) {
        richText(
            html,
            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
        )
    }
}

private fun richText(html: String?, styles: TextLinkStyles): AnnotatedString {
    val source = html?.trim().orEmpty()
    if (source.isEmpty()) return AnnotatedString("")
    val markup = if (HTML_TAG.containsMatchIn(source)) source else {
        source.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\n", "<br>")
    }
    val parsed = AnnotatedString.fromHtml(markup, linkStyles = styles)
    val linked = buildAnnotatedString {
        append(parsed)
        for (m in BARE_URL.findAll(parsed.text)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (parsed.getLinkAnnotations(start, end).isEmpty()) {
                addLink(LinkAnnotation.Url(m.value, styles), start, end)
            }
        }
    }
    // Paragraph and list markup leave trailing newlines behind.
    val last = linked.text.indexOfLast { !it.isWhitespace() }
    return linked.subSequence(0, last + 1)
}
