package org.aerialpod.core.ads

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.aerialpod.core.lan.LanVectors
import org.aerialpod.core.lan.SpanAnchors

/**
 * The desktop's anchors, applied here, must land where the desktop's own
 * mapping did. Both copies are rebuilt from the formula in the desktop's
 * tests/test_ads_anchors.py; the anchors and the expected result come from
 * running ads/anchors.py over them (gen_lan_vectors.py).
 */
class AdAnchorsTest {
    private val case: JsonObject = Json.parseToJsonElement(LanVectors.ANCHOR_CASE).jsonObject
    private val dir = createTempDirectory("anchors").toFile()

    /** fill() from test_ads_anchors.py: a 32-bit integer hash of (seed, index). */
    private fun fill(seed: Int, seconds: Int): ByteArray = ByteArray(seconds * RATE) { i ->
        var h = (seed * 1_000_003L + i) and MASK
        h = (h * 0x9E3779B1L) and MASK
        h = h xor (h ushr 15)
        h = (h * 0x85EBCA77L) and MASK
        h = h xor (h ushr 13)
        (h ushr 24).toByte()
    }

    private fun id3(payload: Int): ByteArray =
        byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0) +
            byteArrayOf(
                (payload shr 21 and 0x7F).toByte(), (payload shr 14 and 0x7F).toByte(),
                (payload shr 7 and 0x7F).toByte(), (payload and 0x7F).toByte(),
            ) + ByteArray(payload)

    private fun episode(ads: List<Int>, tag: ByteArray = ByteArray(0)): File {
        val (a, b, c) = ads
        val bytes = tag + fill(1, 300) + fill(100 + a, a) + fill(2, 400) + fill(200 + b, b) +
            fill(3, 200) + fill(300 + c, c)
        return File(dir, "ep-${ads.joinToString("-")}.mp3").apply { writeBytes(bytes) }
    }

    @Test
    fun readsTheId3TagSize() {
        assertEquals(5010L, AdAnchors.audioStart(id3(5000)))
        assertEquals(0L, AdAnchors.audioStart(ByteArray(10)))
    }

    @Test
    fun placesTheDesktopsSpansInACopyWithDifferentAds() {
        val other = episode(
            case["other_ads"]!!.jsonArray.map { it.jsonPrimitive.int },
            id3(case["other_id3"]!!.jsonPrimitive.int),
        )
        val otherMs = case["other_ms"]!!.jsonPrimitive.long
        val spans = case["spans"]!!.jsonArray.map {
            val (s, e, c) = it.jsonArray.map { v -> v.jsonPrimitive.double }
            AdSpan(s.toLong(), e.toLong(), c)
        }
        val anchors = Json.decodeFromString<List<SpanAnchors>>(case["anchors"]!!.toString())

        val mapped = AdAnchors.mapSpans(
            spans, anchors, case["analysed_ms"]!!.jsonPrimitive.long,
            AdAnchors.Audio(FileByteSource(other), otherMs), otherMs,
        )

        val expected = case["expected"]!!.jsonArray.map {
            val (s, e, c) = it.jsonArray.map { v -> v.jsonPrimitive.double }
            AdSpan(s.toLong(), e.toLong(), c)
        }
        assertEquals(expected, mapped)
    }

    private companion object {
        const val RATE = 1000
        const val MASK = 0xFFFFFFFFL
    }
}
