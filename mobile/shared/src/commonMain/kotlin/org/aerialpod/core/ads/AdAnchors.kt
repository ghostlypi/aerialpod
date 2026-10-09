package org.aerialpod.core.ads

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.max
import kotlin.math.roundToLong
import org.aerialpod.core.lan.Needle
import org.aerialpod.core.lan.SpanAnchors

/** Random access to a local audio file; the platform supplies one (FileByteSource). */
interface ByteSource {
    val size: Long
    /** Up to [length] bytes from [offset]; fewer at the end of the file. */
    fun read(offset: Long, length: Int): ByteArray
}

/**
 * Carry ad spans from the analysed copy of an episode to the one on this
 * device — the port of the desktop's `ads/anchors.py`, which explains it.
 *
 * In short: dynamic ad insertion gives every download different ads, of
 * different lengths, spliced between byte-identical stretches of the show. The
 * detector records runs of those show bytes either side of each span; finding
 * them here says how far each edge moved.
 */
object AdAnchors {
    const val NEEDLE_BYTES = 48
    const val SEARCH_WINDOW_S = 180
    const val EDGE_MS = 10_000L

    /** Byte offset where the audio begins: after the ID3v2 tag, if any. */
    fun audioStart(head: ByteArray): Long {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() ||
            head[2] != '3'.code.toByte()
        ) {
            return 0
        }
        fun b(i: Int) = (head[i].toInt() and 0x7F).toLong()
        val size = (b(6) shl 21) or (b(7) shl 14) or (b(8) shl 7) or b(9)
        val footer = if (head[5].toInt() and 0x10 != 0) 10L else 0L
        return 10 + size + footer
    }

    /** One local copy: where its audio starts and how many bytes a second takes. */
    class Audio(private val source: ByteSource, durationMs: Long) {
        val start: Long = audioStart(source.read(0, 10))
        val rate: Double = (source.size - start) / max(durationMs / 1000.0, 1.0)

        /** [offset] is relative to the audio start. */
        fun read(offset: Long, length: Int): ByteArray =
            source.read(start + max(0L, offset), length)
    }

    /**
     * The spans, moved onto this copy. A span with an edge whose anchors are
     * all missing is dropped: skipping the wrong stretch is worse than not
     * skipping. Shifts accumulate, so each search starts where the last edge landed.
     */
    fun mapSpans(
        spans: List<AdSpan>,
        anchors: List<SpanAnchors>,
        analysedMs: Long,
        audio: Audio,
        localMs: Long,
    ): List<AdSpan> {
        val out = mutableListOf<AdSpan>()
        var shift = 0L
        for ((span, anchor) in spans.zip(anchors)) {
            val newStart = if (span.startMs <= EDGE_MS) span.startMs else {
                shift = findShift(audio, anchor.pre, shift) ?: continue
                span.startMs + (shift / audio.rate * 1000).roundToLong()
            }
            val newEnd = if (span.endMs >= analysedMs - EDGE_MS) localMs else {
                shift = findShift(audio, anchor.post, shift) ?: continue
                span.endMs + (shift / audio.rate * 1000).roundToLong()
            }
            if (newEnd > newStart) out += AdSpan(newStart, newEnd, span.confidence)
        }
        return out
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun findShift(audio: Audio, needles: List<Needle>, guess: Long): Long? {
        val window = (SEARCH_WINDOW_S * audio.rate).toLong()
        for (needle in needles) {
            val data = runCatching { Base64.decode(needle.b) }.getOrNull() ?: continue
            if (data.size < NEEDLE_BYTES) continue
            val lo = max(0L, needle.at + guess - window)
            val hay = audio.read(lo, (2 * window + data.size).toInt())
            val i = indexOf(hay, data)
            if (i >= 0) return lo + i - needle.at
        }
        return null
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        val first = needle[0]
        val last = hay.size - needle.size
        var i = 0
        outer@ while (i <= last) {
            if (hay[i] != first) {
                i++
                continue
            }
            for (j in 1 until needle.size) {
                if (hay[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }
}
