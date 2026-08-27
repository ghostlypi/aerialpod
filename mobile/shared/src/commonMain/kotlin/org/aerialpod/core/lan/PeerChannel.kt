package org.aerialpod.core.lan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The peer wire protocol as a pure state machine — bytes in, messages out.
 * A port of `lan/protocol.py`, and deliberately the same shape.
 *
 * Knows nothing about sockets: feed it whatever arrived, drain whatever it
 * wants written. That keeps the handshake and framing testable without opening
 * a port, and leaves the transport free to drive it from a coroutine.
 *
 * Wire shape:
 *   handshake   newline-delimited JSON (plaintext, nonces and proofs only)
 *   afterwards  4-byte big-endian length + AES-GCM sealed JSON
 *
 * Message types after the handshake:
 *   ident     {"type":"ident", "device_id", "caption", "port"}  — always sent first
 *   snapshot  {"type":"snapshot", ...}                          — full replicated state
 *   position  {"type":"position", ...}                          — live playback push
 *   ping      {"type":"ping"}                                   — keepalive
 */

/**
 * Malformed, unauthenticated or oversized traffic. Always fatal to the
 * connection — there is no partial trust here.
 */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

const val MAX_LINE = 4096                  // a handshake line is ~120 bytes; refuse anything wild
const val MAX_FRAME = 16 * 1024 * 1024     // a snapshot of a large library, with headroom

private val json = Json { ignoreUnknownKeys = true; isLenient = false }

class PeerChannel(
    val role: Role,
    private val key: ByteArray,
    /**
     * Where this end's handshake nonce comes from. Injected rather than called
     * directly so the interop tests can replay a recorded transcript — a nonce
     * drawn at random would make the desktop's bytes unreproducible, and that
     * transcript is the only check that our JSON encoding and framing match
     * theirs exactly.
     */
    private val nonces: () -> ByteArray = ::newNonce,
) {

    var established: Boolean = false
        private set

    private val inbound = ByteBuf()
    private val outbound = ByteBuf()
    private var sealer: Sealer? = null
    private var cn: ByteArray = ByteArray(0)
    private var sn: ByteArray = ByteArray(0)
    private var state: String = "hello"

    // ------------------------------------------------------------ output

    /** Drain everything queued for the socket. */
    fun takeOutput(): ByteArray = outbound.drain()

    /** Client only: open with our nonce. Servers stay silent until spoken to. */
    fun start() {
        if (role != Role.CLIENT) return
        cn = nonces()
        sendLine(buildJsonObject {
            put("v", JsonPrimitive(PROTOCOL_VERSION))
            put("cn", JsonPrimitive(cn.toHex()))
        })
        state = "await_server_nonce"
    }

    /** Queue an application message. Only valid once established. */
    fun send(message: JsonObject) {
        val active = sealer
        if (!established || active == null) throw ProtocolException("channel not established")
        val payload = json.encodeToString(JsonObject.serializer(), message).encodeToByteArray()
        val sealed = active.seal(payload)
        outbound.write(int32BE(sealed.size))
        outbound.write(sealed)
    }

    private fun sendLine(obj: JsonObject) {
        outbound.write(json.encodeToString(JsonObject.serializer(), obj).encodeToByteArray())
        outbound.write(byteArrayOf('\n'.code.toByte()))
    }

    // ------------------------------------------------------------ input

    /** Consume received bytes; returns application messages now readable. */
    fun feed(data: ByteArray): List<JsonObject> {
        inbound.write(data)
        val messages = mutableListOf<JsonObject>()
        while (true) {
            if (established) {
                val frame = takeFrame() ?: return messages
                messages.add(frame)
            } else {
                val line = takeLine() ?: return messages
                handshakeStep(line)
            }
        }
    }

    private fun takeLine(): JsonObject? {
        val idx = inbound.indexOf('\n'.code.toByte())
        if (idx < 0) {
            if (inbound.size > MAX_LINE) throw ProtocolException("handshake line too long")
            return null
        }
        val raw = inbound.take(idx)
        inbound.skip(idx + 1)  // the line and its newline
        val obj = try {
            json.parseToJsonElement(raw.decodeToString())
        } catch (exc: Exception) {
            throw ProtocolException("malformed handshake line: ${exc.message}", exc)
        }
        if (obj !is JsonObject) throw ProtocolException("handshake line is not an object")
        return obj
    }

    private fun takeFrame(): JsonObject? {
        if (inbound.size < 4) return null
        val size = inbound.peekInt32BE()
        if (size < 0 || size > MAX_FRAME) {
            throw ProtocolException("frame of $size bytes exceeds the limit")
        }
        if (inbound.size < 4 + size) return null
        inbound.skip(4)
        val sealed = inbound.take(size)
        inbound.skip(size)
        val active = sealer ?: throw ProtocolException("frame before the handshake finished")
        val plaintext = try {
            active.open(sealed)
        } catch (exc: InvalidTagException) {
            // Forged, corrupted, reordered or replayed — indistinguishable, and
            // all equally fatal.
            throw ProtocolException("frame failed authentication", exc)
        }
        val obj = try {
            json.parseToJsonElement(plaintext.decodeToString())
        } catch (exc: Exception) {
            throw ProtocolException("malformed frame: ${exc.message}", exc)
        }
        if (obj !is JsonObject) throw ProtocolException("frame is not an object")
        return obj
    }

    // ------------------------------------------------------------ handshake

    private fun handshakeStep(msg: JsonObject) {
        if (role == Role.SERVER) serverStep(msg) else clientStep(msg)
    }

    private fun serverStep(msg: JsonObject) {
        when (state) {
            "hello" -> {
                val version = msg["v"]?.jsonPrimitive?.intOrNull
                if (version != PROTOCOL_VERSION) {
                    throw ProtocolException("unsupported protocol version $version")
                }
                cn = nonceFrom(msg, "cn")
                sn = nonces()
                // Nothing but a random nonce until they prove they hold the key:
                // an open port must not be an oracle for guessing the secret.
                sendLine(buildJsonObject {
                    put("v", JsonPrimitive(PROTOCOL_VERSION))
                    put("sn", JsonPrimitive(sn.toHex()))
                })
                state = "await_client_proof"
            }
            "await_client_proof" -> {
                if (!matches(clientProof(key, cn, sn), proofFrom(msg))) {
                    throw ProtocolException("peer failed authentication (different pairing code?)")
                }
                sendLine(buildJsonObject {
                    put("proof", JsonPrimitive(serverProof(key, cn, sn).toHex()))
                })
                establish()
            }
            else -> throw ProtocolException("unexpected handshake message in state $state")
        }
    }

    private fun clientStep(msg: JsonObject) {
        when (state) {
            "await_server_nonce" -> {
                val version = msg["v"]?.jsonPrimitive?.intOrNull
                if (version != PROTOCOL_VERSION) {
                    throw ProtocolException("unsupported protocol version $version")
                }
                sn = nonceFrom(msg, "sn")
                sendLine(buildJsonObject {
                    put("proof", JsonPrimitive(clientProof(key, cn, sn).toHex()))
                })
                state = "await_server_proof"
            }
            "await_server_proof" -> {
                if (!matches(serverProof(key, cn, sn), proofFrom(msg))) {
                    throw ProtocolException("peer failed authentication (different pairing code?)")
                }
                establish()
            }
            else -> throw ProtocolException("unexpected handshake message in state $state")
        }
    }

    private fun establish() {
        val (c2s, s2c) = sessionKeys(key, cn, sn)
        sealer = Sealer(role, c2s, s2c)
        established = true
        state = "ready"
    }
}

// ---------------------------------------------------------------- parsing helpers

private fun nonceFrom(msg: JsonObject, field: String): ByteArray {
    val raw = msg[field]?.jsonPrimitive?.contentOrNull
        ?: throw ProtocolException("handshake missing $field")
    val nonce = raw.hexToBytesOrNull()
        ?: throw ProtocolException("handshake $field is not hex")
    if (nonce.size != NONCE_LEN) throw ProtocolException("handshake $field has wrong length")
    return nonce
}

private fun proofFrom(msg: JsonObject): ByteArray {
    val raw = msg["proof"]?.jsonPrimitive?.contentOrNull
        ?: throw ProtocolException("handshake missing proof")
    return raw.hexToBytesOrNull() ?: throw ProtocolException("handshake proof is not hex")
}

// ---------------------------------------------------------------- bytes

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return out.toString()
}

internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = this[i * 2].digitToIntOrNull(16) ?: return null
        val lo = this[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun int32BE(value: Int) = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
)

/**
 * A grow-and-compact byte queue.
 *
 * Snapshots run to hundreds of kilobytes and arrive in socket-sized pieces, so
 * the naive "concatenate and re-slice" of the Python original would copy the
 * whole buffer per read. This keeps a read cursor and only compacts when it
 * has to make room.
 */
private class ByteBuf {
    private var data = ByteArray(8192)
    private var start = 0
    private var end = 0

    val size: Int get() = end - start

    fun write(bytes: ByteArray) {
        ensureRoom(bytes.size)
        bytes.copyInto(data, end)
        end += bytes.size
    }

    fun indexOf(needle: Byte): Int {
        for (i in start until end) if (data[i] == needle) return i - start
        return -1
    }

    /** The next [count] bytes, without consuming them. */
    fun take(count: Int): ByteArray = data.copyOfRange(start, start + count)

    fun skip(count: Int) {
        start += count
        if (start == end) { start = 0; end = 0 }
    }

    fun peekInt32BE(): Int =
        ((data[start].toInt() and 0xFF) shl 24) or
            ((data[start + 1].toInt() and 0xFF) shl 16) or
            ((data[start + 2].toInt() and 0xFF) shl 8) or
            (data[start + 3].toInt() and 0xFF)

    fun drain(): ByteArray = take(size).also { skip(size) }

    private fun ensureRoom(count: Int) {
        if (end + count <= data.size) return
        if (size + count <= data.size) {
            data.copyInto(data, 0, start, end)
        } else {
            var capacity = data.size
            while (capacity < size + count) capacity *= 2
            val grown = ByteArray(capacity)
            data.copyInto(grown, 0, start, end)
            data = grown
        }
        end -= start
        start = 0
    }
}
