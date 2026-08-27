package org.aerialpod.core.feeds

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hand-rolled parser against real subscriptions, head to head with feedparser.
 *
 * The whole case for writing our own parser was that it had to be at least as
 * forgiving as the desktop's, because a stricter one loses episodes rather than
 * reporting an error. That claim is only worth anything if it is measured
 * against feedparser on the *same bytes*, which is what this does: a snapshot of
 * the live subscriptions, the reference results recorded from feedparser, and
 * every entry compared.
 *
 * Opt-in; the corpus is a local snapshot, not something to fetch on every run:
 *
 *     AERIALPOD_IT_FEEDS=<dir> ./gradlew :shared:jvmTest --tests '*RealFeedsIT*' --rerun-tasks
 */
class RealFeedsIT {

    @Serializable
    private data class Reference(
        val file: String,
        val title: String? = null,
        val host: String = "",
        val bytes: Int = 0,
        val entries: Int = 0,
        val guids: List<String> = emptyList(),
        val media: List<String> = emptyList(),
        val bozo: Boolean = false,
    )

    private val dir = System.getenv("AERIALPOD_IT_FEEDS")

    private fun corpus(): List<Pair<Reference, String>>? {
        val root = dir?.let(::File)
        if (root == null || !root.isDirectory) {
            println("RealFeedsIT skipped: set AERIALPOD_IT_FEEDS to a corpus directory")
            return null
        }
        val manifest = File(root, "manifest.json")
        if (!manifest.isFile) {
            println("RealFeedsIT skipped: no manifest.json in $root")
            return null
        }
        val json = Json { ignoreUnknownKeys = true }
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Reference.serializer()), manifest.readText())
            .map { it to File(root, it.file).readText() }
    }

    /**
     * The headline claim: every episode feedparser finds, we find — same order,
     * same enclosure URL.
     */
    @Test
    fun findsTheSameEpisodesAsFeedparser() {
        val corpus = corpus() ?: return
        var totalEntries = 0
        val problems = mutableListOf<String>()

        for ((reference, xml) in corpus) {
            val parsed = FeedParser.parse(xml)
            if (parsed == null) {
                problems += "${reference.host}: returned null for ${reference.bytes} bytes"
                continue
            }
            val ours = parsed.entries.mapNotNull { it.mediaUrl }
            totalEntries += ours.size

            if (ours.size != reference.media.size) {
                problems += "${reference.host}: ${ours.size} playable vs feedparser's " +
                    "${reference.media.size}"
            }
            val missing = reference.media.toSet() - ours.toSet()
            val extra = ours.toSet() - reference.media.toSet()
            if (missing.isNotEmpty()) {
                problems += "${reference.host}: missed ${missing.size}, e.g. ${missing.first()}"
            }
            if (extra.isNotEmpty()) {
                problems += "${reference.host}: invented ${extra.size}, e.g. ${extra.first()}"
            }
            println("  ${reference.host.padEnd(34)} ${ours.size.toString().padStart(4)} episodes" +
                if (ours.size == reference.media.size && missing.isEmpty()) "  ok" else "  MISMATCH")
        }
        println("  total: $totalEntries episodes across ${corpus.size} feeds")
        assertTrue(problems.isEmpty(), "parser disagreed with feedparser:\n  " + problems.joinToString("\n  "))
    }

    /** Enclosure order matters: the queue is built from these, in feed order. */
    @Test
    fun preservesEntryOrder() {
        val corpus = corpus() ?: return
        for ((reference, xml) in corpus) {
            val ours = FeedParser.parse(xml)?.entries?.mapNotNull { it.mediaUrl } ?: emptyList()
            assertEquals(reference.media, ours, "entry order differs for ${reference.host}")
        }
    }

    /**
     * A null pub_date sorts an episode to the end of the queue, so a parser that
     * quietly fails to read dates would reorder a real library.
     */
    @Test
    fun readsPublicationDates() {
        val corpus = corpus() ?: return
        var dated = 0
        var total = 0
        for ((reference, xml) in corpus) {
            val entries = FeedParser.parse(xml)?.entries ?: emptyList()
            val withDates = entries.count { (it.pubDate ?: 0) > 0 }
            total += entries.size
            dated += withDates
            assertTrue(
                withDates >= entries.size * 0.99,
                "${reference.host}: only $withDates/${entries.size} entries dated",
            )
        }
        println("  dated: $dated/$total")
    }

    @Test
    fun readsChannelMetadataAndTitles() {
        val corpus = corpus() ?: return
        for ((reference, xml) in corpus) {
            val feed = FeedParser.parse(xml) ?: continue
            assertTrue(!feed.title.isNullOrBlank(), "${reference.host}: no channel title")
            reference.title?.let { assertEquals(it, feed.title, "channel title for ${reference.host}") }
            val untitled = feed.entries.count { it.title.isNullOrBlank() }
            assertTrue(untitled == 0, "${reference.host}: $untitled entries with no title")
        }
    }

    /** Real feeds are large; the parser has to stay usable on them. */
    @Test
    fun parsesALargeLibraryQuickly() {
        val corpus = corpus() ?: return
        val bytes = corpus.sumOf { it.second.length.toLong() }
        val start = System.nanoTime()
        for ((_, xml) in corpus) FeedParser.parse(xml)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("  parsed ${bytes / 1024}KiB in ${ms}ms")
        assertTrue(ms < 15_000, "parsing the corpus took ${ms}ms")
    }
}
