package org.aerialpod.core.lan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

/**
 * What peers exchange.
 *
 * Field names are the wire contract with `lan/state.py` — hence the
 * `@SerialName` on every snake_case one. Snapshots are *complete*, not deltas:
 * every exchange carries the whole replicated state, which buys self-healing.
 * A peer that was offline, or that hadn't yet fetched an episode when a record
 * about it arrived, needs no cursor and no catch-up protocol to converge.
 *
 * Episodes are addressed the way peers can both resolve them: feed URL plus
 * GUID, falling back to the enclosure URL through the same matching ladder the
 * gpodder sync uses, since ad-injecting CDNs rotate enclosure URLs per device.
 */

const val SNAPSHOT_VERSION: Int = 1
const val QUEUE_GAP: Long = 1024
const val POSITION_LIMIT: Long = 1000  // most recently touched in-progress episodes

/**
 * `encodeDefaults` so `type` and `v` are actually emitted; nulls stay explicit
 * because Python's `json.dumps` writes them and the desktop's readers use
 * `.get(...)` semantics that treat missing and null alike.
 */
internal val LanJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
data class IntentRecord(
    val feed: String,
    val guid: String? = null,
    val media: String,
    val intent: String,
    val position: Long = 0,
    val pinned: Long = 0,
    val origin: String = "manual",
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("updated_by") val updatedBy: String = "",
)

/**
 * Per-podcast settings.
 *
 * The desktop builds this section from `repo.SETTING_KEYS`, so a new setting
 * replicates without anyone remembering to wire it up. A typed record cannot do
 * that: **adding a per-podcast setting means adding a field here too**, or it
 * will simply never leave the phone.
 */
@Serializable
data class SettingsRecord(
    val feed: String,
    @SerialName("custom_title") val customTitle: String? = null,
    @SerialName("playback_speed") val playbackSpeed: Double? = null,
    @SerialName("skip_intro_secs") val skipIntroSecs: Long? = null,
    @SerialName("skip_outro_secs") val skipOutroSecs: Long? = null,
    @SerialName("auto_add_to_queue") val autoAddToQueue: Long? = null,
    @SerialName("auto_queue_position") val autoQueuePosition: String? = null,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("updated_by") val updatedBy: String? = null,
)

@Serializable
data class PositionRecord(
    val feed: String,
    val guid: String? = null,
    val media: String,
    val position: Long = 0,
    val total: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    /**
     * The sender considers this episode done.
     *
     * A position alone cannot say so. An episode abandoned twenty minutes into
     * an hour and then marked played reads as "in progress" to anyone who only
     * sees the number, and lands back in their queue — which is exactly what a
     * phone syncing with an established desktop sees.
     *
     * Defaulted, so it is wire-compatible in both directions: a peer that
     * predates the field simply omits it. **Only `true` is acted on.** A `false`
     * from an old peer means "I have nothing to say", not "this is unplayed",
     * and treating it as the latter would wipe played state across the mesh.
     */
    val finished: Boolean = false,
)

@Serializable
data class Snapshot(
    val type: String = "snapshot",
    val v: Int = SNAPSHOT_VERSION,
    val intents: List<IntentRecord> = emptyList(),
    val settings: List<SettingsRecord> = emptyList(),
    val positions: List<PositionRecord> = emptyList(),
)

/**
 * A single live position push — the same record shape as a snapshot's, so the
 * receiving side has only one code path to maintain.
 */
@Serializable
data class PositionMessage(
    val type: String = "position",
    val v: Int = SNAPSHOT_VERSION,
    val record: PositionRecord,
)

/**
 * Who we are. Always the first thing over an established channel — identity
 * stays behind the handshake so an unauthenticated stranger learns nothing
 * about this install.
 */
@Serializable
data class Ident(
    val type: String = "ident",
    @SerialName("device_id") val deviceId: String,
    val caption: String,
    /**
     * Where to reach us next time — zero from a phone, which never listens.
     *
     * The desktop reads a zero as "no opinion" and falls back to its own port,
     * so it will remember us and dial an address nothing is bound to. That
     * costs one TCP SYN per retry and is the accepted price of dial-out-only:
     * the alternative is a new protocol field the desktop does not yet know.
     */
    val port: Int = 0,
)

data class MergeCounts(val positions: Int = 0, val intents: Int = 0, val settings: Int = 0) {
    fun any(): Boolean = positions > 0 || intents > 0 || settings > 0
    operator fun plus(other: MergeCounts) = MergeCounts(
        positions + other.positions, intents + other.intents, settings + other.settings,
    )
    override fun toString() =
        "positions=$positions intents=$intents settings=$settings"
}

// ---------------------------------------------------------------- encoding

internal inline fun <reified T> T.toJsonObject(): JsonObject =
    LanJson.encodeToJsonElement(serializer<T>(), this).jsonObject

internal inline fun <reified T> JsonObject.decodeAs(): T =
    LanJson.decodeFromJsonElement(serializer<T>(), this)
