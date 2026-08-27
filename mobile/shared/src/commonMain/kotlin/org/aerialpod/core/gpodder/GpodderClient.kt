package org.aerialpod.core.gpodder

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.aerialpod.core.epochSeconds

/**
 * gpodder.net API v2 — the port of `gpodder/client.py`.
 *
 * Endpoints used (https://gpoddernet.readthedocs.io/en/latest/api/):
 *   POST /api/2/auth/{user}/login.json                     — session cookie
 *   POST /api/2/devices/{user}/{device}.json               — register/rename device
 *   GET/POST /api/2/subscriptions/{user}/{device}.json     — subscription diff
 *   GET/POST /api/2/episodes/{user}.json                   — episode actions
 *
 * Responses are read as text and parsed here rather than through Ktor's
 * ContentNegotiation: gpodder servers are inconsistent about `Content-Type`,
 * and a negotiator that trusts the header fails on bodies that are perfectly
 * good JSON. The desktop's `requests`-based client has the same behaviour for
 * the same reason.
 */

const val GPODDER_DEFAULT_SERVER = "https://gpodder.net"
private const val RETRIES = 3
private const val USER_AGENT = "AerialPod/0.1"

/** Sync-layer error with a user-presentable message. */
open class GpodderError(message: String) : Exception(message)

class GpodderAuthError(message: String) : GpodderError(message)

@Serializable
data class SubscriptionChanges(
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
    val timestamp: Long = 0,
)

@Serializable
data class UploadResult(
    val timestamp: Long = 0,
    @SerialName("update_urls") val updateUrls: List<List<String?>> = emptyList(),
)

@Serializable
data class EpisodeAction(
    val podcast: String = "",
    val episode: String = "",
    val action: String = "",
    val timestamp: String = "",
    val started: Long? = null,
    val position: Long? = null,
    val total: Long? = null,
    val device: String? = null,
)

@Serializable
data class EpisodeActions(
    val actions: List<EpisodeAction> = emptyList(),
    val timestamp: Long = 0,
)

class GpodderClient(
    val username: String,
    var password: String,
    private val http: HttpClient,
    server: String = GPODDER_DEFAULT_SERVER,
    private val dryRun: Boolean = false,
    private val shouldAbort: () -> Boolean = { false },
    private val now: () -> Long = ::epochSeconds,
    private val backoff: suspend (Long) -> Unit = { delay(it) },
) {
    val server: String = server.trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val outbound = Json { encodeDefaults = false; explicitNulls = false }
    private var loggedIn = false

    // ------------------------------------------------------------ plumbing

    private suspend fun request(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
        params: Map<String, String> = emptyMap(),
        retryAuth: Boolean = true,
    ): String {
        if (dryRun && method == HttpMethod.Post && "/auth/" !in path) {
            // Log-instead-of-POST, the equivalent of the desktop's --dry-run-sync.
            // The canned body is shaped like a real one so callers need no branch.
            return """{"timestamp": ${now()}, "update_urls": []}"""
        }

        var lastFailure: String? = null
        for (attempt in 0 until RETRIES) {
            if (shouldAbort()) throw GpodderError("sync aborted")

            val response = try {
                http.request("$server$path") {
                    this.method = method
                    // Basic auth on everything; the session cookie rides along
                    // too. Belt and suspenders against cookie expiry.
                    header("Authorization", basicAuth(username, password))
                    header("User-Agent", USER_AGENT)
                    for ((key, value) in params) parameter(key, value)
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(outbound.encodeToString(JsonElement.serializer(), body))
                    }
                }
            } catch (exc: GpodderError) {
                throw exc
            } catch (exc: Exception) {
                lastFailure = exc.message ?: exc::class.simpleName
                backoff(1000L shl attempt)
                continue
            }

            val status = response.status.value
            if (status == 401 && retryAuth && "/auth/" !in path) {
                loggedIn = false // session expired — re-login once and retry
                login()
                return request(method, path, body, params, retryAuth = false)
            }
            if (status >= 500) {
                lastFailure = "server returned $status"
                backoff(1000L shl attempt)
                continue
            }
            if (status == 401) {
                throw GpodderAuthError("gpodder.net login failed — check username/password")
            }
            if (status >= 400) {
                throw GpodderError("gpodder.net error $status for $path")
            }
            return response.bodyAsText()
        }
        throw GpodderError(
            "gpodder.net unreachable after $RETRIES tries" +
                (lastFailure?.let { " ($it)" } ?: "")
        )
    }

    // ------------------------------------------------------------ auth & devices

    suspend fun login() {
        if (loggedIn) return
        request(HttpMethod.Post, "/api/2/auth/$username/login.json")
        loggedIn = true
    }

    suspend fun registerDevice(deviceId: String, caption: String, type: String = "mobile") {
        request(
            HttpMethod.Post,
            "/api/2/devices/$username/$deviceId.json",
            body = JsonObject(mapOf(
                "caption" to JsonPrimitive(caption),
                "type" to JsonPrimitive(type),
            )),
        )
    }

    /** Device ids the account knows about, so they can be grouped for sync. */
    suspend fun listDeviceIds(): List<String> {
        val text = request(HttpMethod.Get, "/api/2/devices/$username.json")
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array = parsed as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
    }

    /**
     * Ask the server to propagate subscription changes between these devices.
     *
     * Best-effort: not every server implements it, and a failure here only
     * means subscriptions do not cross on their own.
     */
    suspend fun linkDevices(deviceIds: List<String>) {
        if (deviceIds.size < 2) return
        request(
            HttpMethod.Post,
            "/api/2/sync-devices/$username.json",
            body = JsonObject(mapOf(
                "synchronize" to JsonArray(listOf(
                    JsonArray(deviceIds.map { JsonPrimitive(it) })
                ))
            )),
        )
    }

    // ------------------------------------------------------------ subscriptions

    suspend fun getSubscriptionChanges(deviceId: String, since: Long): SubscriptionChanges {
        val text = request(
            HttpMethod.Get,
            "/api/2/subscriptions/$username/$deviceId.json",
            params = mapOf("since" to since.toString()),
        )
        return runCatching { json.decodeFromString(SubscriptionChanges.serializer(), text) }
            .getOrElse { SubscriptionChanges(timestamp = since) }
    }

    suspend fun uploadSubscriptionChanges(
        deviceId: String,
        add: List<String>,
        remove: List<String>,
    ): UploadResult {
        val text = request(
            HttpMethod.Post,
            "/api/2/subscriptions/$username/$deviceId.json",
            body = JsonObject(mapOf(
                "add" to JsonArray(add.map { JsonPrimitive(it) }),
                "remove" to JsonArray(remove.map { JsonPrimitive(it) }),
            )),
        )
        return runCatching { json.decodeFromString(UploadResult.serializer(), text) }
            .getOrElse { UploadResult() }
    }

    /**
     * The account's merged subscription list, across every device.
     *
     * Used on first sync only: it is how a phone with no history picks up what
     * the desktop (and AntennaPod) already subscribe to. Some servers answer
     * with objects rather than bare URL strings, so both shapes are accepted.
     */
    suspend fun getAllSubscriptions(): List<String> {
        val text = request(HttpMethod.Get, "/subscriptions/$username.json")
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array = parsed as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element["url"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.filter { it.isNotBlank() }
    }

    // ------------------------------------------------------------ episode actions

    suspend fun getEpisodeActions(since: Long, aggregated: Boolean = true): EpisodeActions {
        val params = mutableMapOf("since" to since.toString())
        if (aggregated) params["aggregated"] = "true"
        val text = request(HttpMethod.Get, "/api/2/episodes/$username.json", params = params)
        return runCatching { json.decodeFromString(EpisodeActions.serializer(), text) }
            .getOrElse { EpisodeActions(timestamp = since) }
    }

    suspend fun uploadEpisodeActions(actions: List<EpisodeAction>): Long {
        val payload = JsonArray(actions.map { action ->
            outbound.encodeToJsonElement(EpisodeAction.serializer(), action).jsonObject
        })
        val text = request(HttpMethod.Post, "/api/2/episodes/$username.json", body = payload)
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        return (parsed as? JsonObject)?.get("timestamp")?.jsonPrimitive?.longOrNull ?: 0
    }
}

// ---------------------------------------------------------------- basic auth

private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * Hand-rolled rather than `kotlin.io.encoding.Base64`, which is still behind an
 * opt-in on some of the toolchains this has to build against. Twelve lines is a
 * cheaper dependency than a moving annotation.
 */
internal fun basicAuth(username: String, password: String): String {
    val bytes = "$username:$password".encodeToByteArray()
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = (bytes[i].toInt() and 0xFF shl 16) or
            (bytes[i + 1].toInt() and 0xFF shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        out.append(B64[n ushr 18 and 63]).append(B64[n ushr 12 and 63])
            .append(B64[n ushr 6 and 63]).append(B64[n and 63])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = bytes[i].toInt() and 0xFF shl 16
            out.append(B64[n ushr 18 and 63]).append(B64[n ushr 12 and 63]).append("==")
        }
        2 -> {
            val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
            out.append(B64[n ushr 18 and 63]).append(B64[n ushr 12 and 63])
                .append(B64[n ushr 6 and 63]).append('=')
        }
    }
    return "Basic $out"
}
