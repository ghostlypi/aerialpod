package org.aerialpod.core.ads

import org.aerialpod.core.db.AerialPodDatabase
import org.aerialpod.core.lan.SpanAnchors
import org.aerialpod.core.lan.parseAnchors
import org.aerialpod.core.lan.parseSpans
import org.aerialpod.core.lan.validSpans
import kotlin.math.abs

/** One detected ad, in the analysed copy's timeline. */
data class AdSpan(val startMs: Long, val endMs: Long, val confidence: Double)

/**
 * An episode's stored detection: the spans, the length of the copy they were
 * found in, and — when the detector recorded them — anchors to find them in
 * another copy (AdAnchors).
 */
data class EpisodeAds(
    val durationMs: Long,
    val spans: List<AdSpan>,
    val anchors: List<SpanAnchors>? = null,
)

fun AerialPodDatabase.adsFor(episodeId: Long): EpisodeAds? {
    val row = adSegmentsQueries.adSegmentsFor(episodeId).executeAsOneOrNull() ?: return null
    val spans = parseSpans(row.spans)?.let(::validSpans) ?: return null
    val anchors = row.anchors?.let(::parseAnchors)?.takeIf { it.size == spans.size }
    return EpisodeAds(row.duration_ms, spans, anchors)
}

/**
 * When playback should jump an ad — the port of the desktop's `ads/skip.py`,
 * kept line for line so both players behave the same.
 *
 * Spans only apply to the copy that was analysed: a dynamically inserted ad
 * makes another download of the "same" episode a different length, and the
 * spans would land in the wrong places. Skipping is for running *into* an ad —
 * crossing its start, resuming inside it, or a forward seek landing in it.
 * Seeking back into one already passed reads as wanting to hear it.
 */
object AdSkip {
    const val DURATION_TOLERANCE_MS = 2_000L
    const val MIN_CONFIDENCE = 0.6
    const val ENTRY_GRACE_MS = 2_000L
    const val END_MARGIN_MS = 1_000L

    fun applies(analysedMs: Long, actualMs: Long): Boolean =
        analysedMs > 0 && actualMs > 0 && abs(analysedMs - actualMs) <= DURATION_TOLERANCE_MS

    /** Index of the span to jump past now, or null. `prevMs` is null right after loading. */
    fun spanToSkip(spans: List<AdSpan>, allowed: Set<Int>, prevMs: Long?, posMs: Long): Int? {
        for ((i, span) in spans.withIndex()) {
            if (i in allowed || span.confidence < MIN_CONFIDENCE) continue
            if (posMs >= span.startMs && posMs < span.endMs &&
                (prevMs == null || prevMs < span.startMs + ENTRY_GRACE_MS)
            ) {
                return i
            }
        }
        return null
    }
}
