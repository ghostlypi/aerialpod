package org.aerialpod.core.lan

import io.ktor.network.selector.SelectorManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Fetching the exact audio file a desktop found ads in, from that desktop —
 * the client half of the desktop's `lan/files.py`, which explains why.
 *
 * Its own connection, dialled for the one transfer: an ident with
 * `"purpose": "file"`, then `file_get`, then `file_head`, the file in
 * `file_chunk`s, and `file_end` (or a `file_err` at any point). The sync link
 * is left alone, so a hundred megabytes moving does not hold up a position push.
 *
 * Knows nothing about files on disk: the [FileSink] does the writing and the
 * checking, which keeps platform I/O and hashing out of common code.
 */

/** One episode's analysed file, from [offset] (a resumed transfer). */
data class FileRequest(val feed: String, val guid: String?, val media: String, val offset: Long = 0)

interface FileSink {
    /** What the desktop is about to send. False refuses it — not the file expected. */
    suspend fun head(bytes: Long, sha256: String): Boolean

    /** The next run of bytes, starting at [offset], which is always where the last ended. */
    suspend fun write(offset: Long, data: ByteArray)
}

sealed interface FetchResult {
    /** Every byte arrived; the sink still has to check them. */
    data object Complete : FetchResult

    /** The desktop answered, and the answer was no (or not the file expected). */
    data class Refused(val reason: String) : FetchResult

    /** Unreachable, or the link died part-way. */
    data class Failed(val reason: String) : FetchResult
}

/** A link with nothing arriving for this long is given up on. */
private val IDLE_LIMIT = 30.seconds

internal class FileFetch(
    private val address: String,
    private val port: Int,
    private val key: ByteArray,
    private val ident: Ident,
    private val selector: SelectorManager,
) {
    suspend fun run(request: FileRequest, sink: FileSink): FetchResult {
        val connection = PeerConnection(address, port, key, ident.copy(purpose = "file"), selector)
        var result: FetchResult = FetchResult.Failed("the connection closed mid-transfer")
        var expected = request.offset
        var lastHeard = TimeSource.Monotonic.markNow()

        suspend fun onMessage(conn: PeerConnection, message: JsonObject) {
            lastHeard = TimeSource.Monotonic.markNow()
            when (message.typeOrNull()) {
                "file_head" -> {
                    val bytes = message["bytes"]?.jsonPrimitive?.longOrNull ?: -1
                    val sha = message["sha256"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (!sink.head(bytes, sha)) {
                        result = FetchResult.Refused("the desktop offered a different file")
                        conn.close()
                    }
                }
                "file_chunk" -> {
                    val offset = message["offset"]?.jsonPrimitive?.longOrNull
                    val data = message["data"]?.jsonPrimitive?.contentOrNull?.let(::decode)
                    if (offset != expected || data == null) {
                        result = FetchResult.Failed("a chunk arrived out of order")
                        conn.close()
                        return
                    }
                    sink.write(offset, data)
                    expected += data.size
                }
                "file_end" -> {
                    result = FetchResult.Complete
                    conn.close()
                }
                "file_err" -> {
                    result = FetchResult.Refused(
                        message["reason"]?.jsonPrimitive?.contentOrNull ?: "refused",
                    )
                    conn.close()
                }
            }
        }

        try {
            coroutineScope {
                val watchdog = launch {
                    while (true) {
                        delay(5_000)
                        if (lastHeard.elapsedNow() > IDLE_LIMIT) connection.close()
                    }
                }
                try {
                    connection.run(
                        this,
                        onReady = { it.send(fileGet(request)) },
                        onMessage = ::onMessage,
                    )
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (exc: CancellationException) {
            connection.close()
            throw exc
        } catch (exc: Exception) {
            if (result !is FetchResult.Complete) {
                result = FetchResult.Failed(exc.message ?: "could not reach $address")
            }
        }
        return result
    }

    private fun fileGet(request: FileRequest) = buildJsonObject {
        put("type", "file_get")
        put("feed", request.feed)
        put("guid", request.guid)
        put("media", request.media)
        put("offset", request.offset)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decode(text: String): ByteArray? = runCatching { Base64.decode(text) }.getOrNull()
}
