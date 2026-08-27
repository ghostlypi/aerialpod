package org.aerialpod.core.lan

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the snapshot's **wire field names** to the desktop's.
 *
 * `SnapshotMergeTest` proves the merge rules, but it only ever has Kotlin talk
 * to Kotlin — so a key spelled `updatedAt` instead of `updated_at` would sail
 * through every one of its cases, and then silently read as zero against a real
 * desktop, making every incoming record look infinitely old.
 *
 * These vectors come from the desktop's own `build_snapshot()`
 * (mobile/tools/gen_lan_vectors.py), seeded to match the `Device` harness.
 */
class DesktopSnapshotInteropTest {

    private val desktop: Snapshot =
        LanJson.decodeFromString(Snapshot.serializer(), LanVectors.DESKTOP_SNAPSHOT)

    @Test
    fun decodesEveryFieldTheDesktopSent() {
        assertEquals(SNAPSHOT_VERSION, desktop.v)
        assertEquals(2, desktop.intents.size)
        assertEquals(1, desktop.settings.size)
        assertEquals(1, desktop.positions.size)

        // The stamps are the fields most likely to deserialize to a default
        // without anyone noticing — and they decide every conflict.
        val queued = desktop.intents.single { it.intent == "queued" }
        assertEquals("guid-1-1", queued.guid)
        assertEquals(4100L, queued.updatedAt)
        assertEquals("desktop9", queued.updatedBy)
        assertEquals(1024L, queued.position)
        assertEquals(1L, queued.pinned)
        assertEquals("manual", queued.origin)

        val excluded = desktop.intents.single { it.intent == "excluded" }
        assertEquals("guid-1-3", excluded.guid)
        assertEquals(4200L, excluded.updatedAt)

        val settings = desktop.settings.single()
        assertEquals("Desktop Title", settings.customTitle)
        assertEquals(1.5, settings.playbackSpeed)
        assertEquals(12L, settings.skipIntroSecs)
        assertEquals(30L, settings.skipOutroSecs)
        assertEquals(1L, settings.autoAddToQueue)
        assertEquals("front", settings.autoQueuePosition)
        assertEquals(4300L, settings.updatedAt)
        assertEquals("desktop9", settings.updatedBy)

        val position = desktop.positions.single()
        assertEquals("guid-1-2", position.guid)
        assertEquals(742L, position.position)
        assertEquals(3600L, position.total)
        assertEquals(4400L, position.updatedAt)
    }

    @Test
    fun mergesASnapshotTheDesktopBuilt() {
        val device = Device("mobile01")
        val counts = device.sync.mergeSnapshot(desktop)

        assertEquals(MergeCounts(positions = 1, intents = 2, settings = 1), counts)
        assertEquals(listOf(1L), device.queueIds())
        assertTrue(device.isExcluded(3))
        assertEquals("Desktop Title", device.settings()?.custom_title)
        assertEquals("front", device.settings()?.auto_queue_position)
        assertEquals(742L, device.episode(2).position_secs)
        assertEquals(3600L, device.episode(2).total_secs)

        // The desktop's authorship survived the merge — re-stamping incoming
        // records with our own clock would make them win every later conflict
        // against the device that actually wrote them.
        val intent = device.db.queueQueries.intentFor(1).executeAsOne()
        assertEquals(4100L, intent.updated_at)
        assertEquals("desktop9", intent.updated_by)
    }

    @Test
    fun appliesALivePositionPushTheDesktopBuilt() {
        val device = Device("mobile01")
        val message = LanJson.decodeFromString(
            PositionMessage.serializer(), LanVectors.DESKTOP_POSITION,
        )
        assertEquals("position", message.type)
        assertTrue(device.sync.applyPositionMessage(message))
        assertEquals(742L, device.episode(2).position_secs)
        assertEquals(4400L, device.episode(2).position_updated_at)
    }

    /**
     * What we *send* has to be readable by the desktop too, which no decode
     * test can show. Comparing key sets catches a field we spelled differently
     * and a field we forgot to send at all.
     */
    @Test
    fun emitsExactlyTheKeysTheDesktopEmits() {
        val device = Device("mobile01")
        device.sync.mergeSnapshot(desktop)
        val ours = device.sync.buildSnapshot().toJsonObject()
        val theirs = LanJson.parseToJsonElement(LanVectors.DESKTOP_SNAPSHOT).jsonObject

        assertEquals(theirs.keys, ours.keys, "snapshot envelope")
        for (section in listOf("intents", "settings", "positions")) {
            assertEquals(
                theirs.recordKeys(section),
                ours.recordKeys(section),
                "keys of a $section record",
            )
        }
    }

    @Test
    fun emitsExactlyThePositionKeysTheDesktopEmits() {
        val device = Device("mobile01")
        device.setPosition(2, position = 742, total = 3600, at = 4400)
        val ours = device.sync.positionMessageFor(2)!!.toJsonObject()
        val theirs = LanJson.parseToJsonElement(LanVectors.DESKTOP_POSITION).jsonObject

        assertEquals(theirs.keys, ours.keys)
        assertEquals(
            theirs["record"]!!.jsonObject.keys,
            ours["record"]!!.jsonObject.keys,
        )
    }

    private fun JsonObject.recordKeys(section: String): Set<String> {
        val array = this[section]!!.jsonArray
        assertTrue(array.isNotEmpty(), "$section must be non-empty to compare keys")
        return array.first().jsonObject.keys
    }
}
