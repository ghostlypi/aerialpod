package org.aerialpod.core.feeds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser's tolerance, case by case.
 *
 * Each of these is something a real podcast feed does. A conforming XML parser
 * rejects several of them outright, and the cost of that is not an error
 * message — it is an episode that never appears on the phone while sitting
 * happily in the desktop's list.
 */
class XmlTest {

    @Test
    fun readsAPlainDocument() {
        val root = assertNotNull(parseXml("""<rss><channel><title>Hello</title></channel></rss>"""))
        assertEquals("rss", root.name)
        assertEquals("Hello", root.child("channel")?.textOf("title"))
    }

    @Test
    fun skipsDeclarationDoctypeAndComments() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE rss PUBLIC "-//X//DTD//EN" "http://example.com/x.dtd">
            <!-- a comment with <tags> inside -->
            <rss><channel><title>Ok</title></channel></rss>
        """.trimIndent()
        assertEquals("Ok", parseXml(xml)?.child("channel")?.textOf("title"))
    }

    @Test
    fun stripsAByteOrderMark() {
        val root = assertNotNull(parseXml("﻿<rss><channel/></rss>"))
        assertEquals("rss", root.name)
    }

    /** The one that breaks conforming parsers, and appears constantly. */
    @Test
    fun keepsABareAmpersand() {
        val root = parseXml("<item><title>Rock & Roll</title></item>")
        assertEquals("Rock & Roll", root?.textOf("title"))
    }

    @Test
    fun resolvesEntities() {
        val root = parseXml(
            "<item><title>A &amp; B &lt;tag&gt; &#65; &#x42; &nbsp;end &unknownthing;</title></item>"
        )
        // &nbsp; decodes to U+00A0, not a plain space — written explicitly here
        // because the two are indistinguishable in source.
        assertEquals("A & B <tag> A B \u00A0end &unknownthing;", root?.textOf("title"))
    }

    /**
     * An unknown entity short enough to look like one is kept verbatim rather
     * than dropped. Distinct from the case above: `&unknownthing;` is too long
     * to be read as a reference at all and takes the bare-`&` path instead.
     */
    @Test
    fun keepsAShortUnknownEntityVerbatim() {
        assertEquals("a &foo; b", parseXml("<t>a &foo; b</t>")?.text)
        assertEquals("50&percnt; off", parseXml("<t>50&percnt; off</t>")?.text)
    }

    @Test
    fun resolvesAstralCodePoints() {
        assertEquals("🎧", parseXml("<t>&#x1F3A7;</t>")?.text)
    }

    @Test
    fun readsCdataLiterally() {
        val root = parseXml("<item><description><![CDATA[<p>Hi & bye</p>]]></description></item>")
        assertEquals("<p>Hi & bye</p>", root?.textOf("description"))
    }

    @Test
    fun handlesUnclosedCdata() {
        val root = parseXml("<item><description><![CDATA[truncated...")
        assertTrue(root?.textOf("description")?.startsWith("truncated") == true)
    }

    @Test
    fun lowercasesNamesSoCaseDoesNotMatter() {
        val root = parseXml("<RSS><Channel><TITLE>x</TITLE></Channel></RSS>")
        assertEquals("rss", root?.name)
        assertEquals("x", root?.child("channel")?.textOf("title"))
    }

    @Test
    fun keepsNamespacePrefixesAsPartOfTheName() {
        val root = parseXml("""<item><itunes:duration>1:02:03</itunes:duration></item>""")
        assertEquals("1:02:03", root?.textOf("itunes:duration"))
    }

    @Test
    fun readsAttributesInEveryQuotingStyle() {
        val root = assertNotNull(parseXml("""<enclosure url="a.mp3" type='audio/mpeg' length=1234 checked/>"""))
        assertEquals("a.mp3", root.attr("url"))
        assertEquals("audio/mpeg", root.attr("type"))
        assertEquals("1234", root.attr("length"))
        assertNull(root.attr("checked")) // present but empty
    }

    @Test
    fun doesNotEndTheTagOnAGreaterThanInsideAnAttribute() {
        val root = assertNotNull(parseXml("""<item title="a > b"><x/></item>"""))
        assertEquals("a > b", root.attr("title"))
        assertNotNull(root.child("x"))
    }

    @Test
    fun decodesEntitiesInAttributes() {
        val root = parseXml("""<enclosure url="http://x/a.mp3?a=1&amp;b=2"/>""")
        assertEquals("http://x/a.mp3?a=1&b=2", root?.attr("url"))
    }

    @Test
    fun treatsSelfClosingTagsAsEmpty() {
        val root = assertNotNull(parseXml("<channel><image/><title>t</title></channel>"))
        assertEquals(2, root.children.size)
        assertEquals("t", root.textOf("title"))
    }

    @Test
    fun ignoresAStrayEndTag() {
        val root = parseXml("<item><title>a</b> b</title></item>")
        assertEquals("a b", root?.textOf("title"))
    }

    /** An unclosed element must not swallow its siblings. */
    @Test
    fun closesUnclosedElementsAtTheParent() {
        val root = assertNotNull(parseXml("<channel><item><title>one<item><title>two</channel>"))
        val items = root.descendants("item")
        assertEquals(2, items.size)
    }

    @Test
    fun survivesTruncationMidTag() {
        val root = parseXml("<rss><channel><title>partial</title><item url=\"x")
        assertEquals("partial", root?.child("channel")?.textOf("title"))
    }

    @Test
    fun returnsNullForSomethingThatIsNotXmlAtAll() {
        assertNull(parseXml("404 Not Found"))
        assertNull(parseXml(""))
    }

    @Test
    fun findsDescendantsAtAnyDepth() {
        val root = assertNotNull(parseXml("<a><b><c><item/></c></b><item/></a>"))
        assertEquals(2, root.descendants("item").size)
    }

    @Test
    fun textOfPrefersTheFirstNamePresent() {
        val root = assertNotNull(parseXml("<item><description>plain</description></item>"))
        assertEquals("plain", root.textOf("itunes:summary", "description"))
        assertNull(root.textOf("itunes:summary"))
    }
}
