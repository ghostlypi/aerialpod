package org.aerialpod.core.feeds

/**
 * A deliberately forgiving XML reader, for feeds and OPML.
 *
 * The desktop parses with feedparser, which recovers from almost anything. That
 * tolerance is not a nicety here: if this parser is stricter than the desktop's,
 * the phone ends up with *fewer episodes* from the same feed — which reads to a
 * user as sync being broken, not as a parse failure. So every rule below errs
 * toward salvaging content rather than rejecting the document:
 *
 *  - a bare `&` that starts no known entity stays a literal `&` (extremely
 *    common in real feeds, and fatal to a conforming parser);
 *  - an unknown entity is left as written rather than dropped;
 *  - `</b>` with no open `<b>` is ignored, and unclosed elements are closed by
 *    their parent;
 *  - declarations, doctypes, comments and processing instructions are skipped;
 *  - attribute values may be single-quoted, double-quoted, or bare.
 *
 * Element and attribute names are lowercased, so `<ITEM>` and `<item>` are one
 * thing and namespace prefixes survive as part of the name (`itunes:duration`).
 * Lookups must therefore use lowercase keys — including OPML's `xmlUrl`, which
 * is read as `xmlurl`.
 *
 * The result is a small tree rather than a token stream: feeds are shallow and
 * a few hundred kilobytes at most, and a tree is far harder to misread.
 */
class XmlNode(
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    val children: MutableList<XmlNode> = mutableListOf()
    private val textBuilder = StringBuilder()

    /** This element's own text, with entities resolved and edges trimmed. */
    val text: String get() = textBuilder.toString().trim()

    internal fun addText(value: String) {
        textBuilder.append(value)
    }

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    fun childrenNamed(name: String): List<XmlNode> = children.filter { it.name == name }

    /**
     * The text of the first child matching any of [names], in the order given —
     * so a caller can express "prefer `itunes:summary`, fall back to
     * `description`" as one call.
     */
    fun textOf(vararg names: String): String? {
        for (name in names) {
            val value = child(name)?.text
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    fun attr(name: String): String? = attributes[name]?.takeIf { it.isNotEmpty() }

    /** Every descendant with this name, at any depth. */
    fun descendants(name: String): List<XmlNode> {
        val out = mutableListOf<XmlNode>()
        fun walk(node: XmlNode) {
            for (child in node.children) {
                if (child.name == name) out += child
                walk(child)
            }
        }
        walk(this)
        return out
    }
}

/** Parses [source]; returns the root element, or null if there is no element at all. */
fun parseXml(source: String): XmlNode? = XmlReader(source).parse()

private class XmlReader(source: String) {
    // A BOM before the declaration is common from Windows-authored feeds and
    // would otherwise make the very first '<' unfindable.
    private val text = source.removePrefix("﻿")
    private var pos = 0

    private var root: XmlNode? = null
    private val stack = ArrayDeque<XmlNode>()

    fun parse(): XmlNode? {
        while (pos < text.length) {
            val open = text.indexOf('<', pos)
            if (open < 0) {
                appendText(text.substring(pos))
                break
            }
            if (open > pos) appendText(text.substring(pos, open))
            pos = open
            if (!readMarkup()) break
        }
        return root
    }

    /** False when the document is exhausted mid-token. */
    private fun readMarkup(): Boolean {
        if (text.startsWith("<!--", pos)) return skipTo("-->")
        if (text.startsWith("<![CDATA[", pos)) {
            val end = text.indexOf("]]>", pos + 9)
            val body = if (end < 0) text.substring(pos + 9) else text.substring(pos + 9, end)
            // CDATA is literal by definition — no entity resolution.
            stack.lastOrNull()?.addText(body)
            pos = if (end < 0) text.length else end + 3
            return true
        }
        if (text.startsWith("<?", pos)) return skipTo("?>")
        if (text.startsWith("<!", pos)) return skipDoctype()
        if (text.startsWith("</", pos)) return readEndTag()
        return readStartTag()
    }

    private fun skipTo(terminator: String): Boolean {
        val end = text.indexOf(terminator, pos)
        pos = if (end < 0) text.length else end + terminator.length
        return end >= 0
    }

    /** `<!DOCTYPE ...>`, including an internal subset in brackets. */
    private fun skipDoctype(): Boolean {
        var depth = 0
        var i = pos
        while (i < text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) { pos = i + 1; return true }
            }
            i++
        }
        pos = text.length
        return false
    }

    private fun readEndTag(): Boolean {
        val end = text.indexOf('>', pos)
        if (end < 0) { pos = text.length; return false }
        val name = text.substring(pos + 2, end).trim().lowercase()
        pos = end + 1

        // Close up to the nearest matching open element. A stray end tag with no
        // match at all is dropped rather than treated as an error.
        val index = stack.indexOfLast { it.name == name }
        if (index >= 0) repeat(stack.size - index) { stack.removeLast() }
        return true
    }

    private fun readStartTag(): Boolean {
        val end = findTagEnd(pos + 1)
        if (end < 0) { pos = text.length; return false }
        var body = text.substring(pos + 1, end)
        pos = end + 1

        val selfClosing = body.trimEnd().endsWith("/")
        if (selfClosing) body = body.trimEnd().dropLast(1)

        val name = body.takeWhile { !it.isWhitespace() }.lowercase()
        if (name.isEmpty()) return true // `< ` in prose — not markup at all

        val node = XmlNode(name, readAttributes(body.drop(name.length)))
        val parent = stack.lastOrNull()
        if (parent != null) {
            parent.children += node
        } else if (root == null) {
            root = node
        } else {
            // A second root: keep the first, but let the content be reachable.
            root!!.children += node
        }
        if (!selfClosing) stack.addLast(node)
        return true
    }

    /** The `>` that ends the tag, ignoring any inside a quoted attribute value. */
    private fun findTagEnd(from: Int): Int {
        var i = from
        var quote = ' '
        while (i < text.length) {
            val c = text[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return -1
    }

    private fun readAttributes(source: String): Map<String, String> {
        if (source.isBlank()) return emptyMap()
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < source.length) {
            while (i < source.length && source[i].isWhitespace()) i++
            if (i >= source.length) break

            val nameStart = i
            while (i < source.length && !source[i].isWhitespace() && source[i] != '=') i++
            val name = source.substring(nameStart, i).lowercase()
            if (name.isEmpty()) break

            while (i < source.length && source[i].isWhitespace()) i++
            if (i >= source.length || source[i] != '=') {
                out[name] = "" // valueless attribute
                continue
            }
            i++ // '='
            while (i < source.length && source[i].isWhitespace()) i++
            if (i >= source.length) break

            val value: String
            val c = source[i]
            if (c == '"' || c == '\'') {
                val close = source.indexOf(c, i + 1)
                value = if (close < 0) source.substring(i + 1) else source.substring(i + 1, close)
                i = if (close < 0) source.length else close + 1
            } else {
                val start = i
                while (i < source.length && !source[i].isWhitespace()) i++
                value = source.substring(start, i)
            }
            out[name] = decodeEntities(value)
        }
        return out
    }

    private fun appendText(raw: String) {
        val node = stack.lastOrNull() ?: return
        if (raw.isEmpty()) return
        node.addText(decodeEntities(raw))
    }
}

// ---------------------------------------------------------------- entities

private val NAMED = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    // Feeds routinely embed HTML in descriptions, so the handful that actually
    // show up there are worth resolving too.
    "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”",
    "eacute" to "é", "egrave" to "è", "uuml" to "ü", "ouml" to "ö", "auml" to "ä",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·",
)

/**
 * Resolve entities, leaving anything unrecognised exactly as written.
 *
 * The bare-`&` case is the important one: `Rock & Roll` appears in real feeds
 * and must not cost us the episode.
 */
internal fun decodeEntities(raw: String): String {
    if ('&' !in raw) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '&') { out.append(c); i++; continue }

        val semi = raw.indexOf(';', i + 1)
        // A reference is short; a distant ';' means this '&' was just an '&'.
        if (semi < 0 || semi - i > 12) { out.append('&'); i++; continue }

        val body = raw.substring(i + 1, semi)
        val resolved = when {
            body.isEmpty() -> null
            body[0] == '#' -> {
                val digits = body.drop(1)
                val code = if (digits.startsWith("x") || digits.startsWith("X")) {
                    digits.drop(1).toIntOrNull(16)
                } else {
                    digits.toIntOrNull()
                }
                code?.takeIf { it in 1..0x10FFFF }?.let { charAt(it) }
            }
            else -> NAMED[body.lowercase()]
        }
        if (resolved != null) {
            out.append(resolved)
            i = semi + 1
        } else {
            out.append('&')
            i++
        }
    }
    return out.toString()
}

private fun charAt(codePoint: Int): String =
    if (codePoint <= 0xFFFF) codePoint.toChar().toString() else {
        val v = codePoint - 0x10000
        charArrayOf(
            (0xD800 + (v shr 10)).toChar(),
            (0xDC00 + (v and 0x3FF)).toChar(),
        ).concatToString()
    }
