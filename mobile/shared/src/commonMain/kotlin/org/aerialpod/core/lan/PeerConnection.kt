package org.aerialpod.core.lan

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One outbound connection to a peer.
 *
 * Owns the socket and the protocol state machine; knows nothing about what the
 * messages mean. Outbound only, always — a phone never listens (see
 * docs/mobile-lan-sync.md). `Channel`'s role decides only who speaks first
 * during the handshake; once established, both ends hold keys for both
 * directions and messages flow each way over this one socket, so a listening
 * socket on the phone would buy nothing.
 */

const val CONNECT_TIMEOUT_MS = 5_000L

/**
 * How long a socket has to prove it belongs to a peer.
 *
 * The desktop needs this because its subnet sweep knocks on every open port,
 * most of which belong to something else entirely — an SSH daemon will happily
 * accept the connection and then wait forever. The phone does not sweep, but a
 * remembered address can just as easily be answered by something new, so the
 * deadline stays.
 */
const val HANDSHAKE_TIMEOUT_MS = 10_000L

private const val READ_CHUNK = 32 * 1024

/**
 * Qt reports an IPv4 peer on a dual-stack listener as `::ffff:192.168.1.24`.
 *
 * Left alone, the same machine looks like two different addresses depending on
 * which end dialled, so a peer we remembered from one path would never match
 * the one we later dial.
 */
fun plainAddress(text: String): String =
    if (text.startsWith("::ffff:")) text.removePrefix("::ffff:") else text

class PeerConnection(
    val address: String,
    val port: Int,
    private val key: ByteArray,
    private val ident: Ident,
    private val selector: SelectorManager,
) {
    var peerId: String? = null
        private set
    var caption: String = ""
        private set
    var peerPort: Int = 0
        private set

    val established: Boolean get() = channel.established && peerId != null

    private val channel = PeerChannel(Role.CLIENT, key)
    private val lock = Mutex()
    private var socket: Socket? = null
    private var writeChannel: ByteWriteChannel? = null
    private var sentIdent = false
    private var closed = false

    /**
     * Connect, handshake, and pump until the peer goes away or [close] is called.
     *
     * Returns normally on a clean disconnect; throws on a failed dial or a
     * protocol violation, both of which the caller treats the same way — back
     * off and wait for a reason to try again.
     */
    suspend fun run(
        scope: CoroutineScope,
        onReady: suspend (PeerConnection) -> Unit,
        onMessage: suspend (PeerConnection, JsonObject) -> Unit,
    ) {
        val sock = withTimeout(CONNECT_TIMEOUT_MS) {
            aSocket(selector).tcp().connect(InetSocketAddress(address, port))
        }
        socket = sock

        // Mirrors the desktop's abandon_if_unconnected timer: a peer that has
        // not identified itself by the deadline is dropped, which unblocks the
        // read loop below by closing the socket under it.
        val watchdog = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (!established) close()
        }

        try {
            val read = sock.openReadChannel()
            val write = sock.openWriteChannel(autoFlush = true)
            writeChannel = write

            lock.withLock {
                channel.start()
                flushLocked(write)
            }

            val buffer = ByteArray(READ_CHUNK)
            while (true) {
                val count = read.readAvailable(buffer)
                if (count <= 0) break

                val messages = lock.withLock {
                    val received = channel.feed(buffer.copyOf(count))
                    // The first thing over an established channel is always who
                    // we are; identity stays behind the handshake so an
                    // unauthenticated stranger learns nothing about this install.
                    if (channel.established && !sentIdent) {
                        sentIdent = true
                        channel.send(ident.toJsonObject())
                    }
                    flushLocked(write)
                    received
                }

                for (message in messages) {
                    when {
                        message.typeOrNull() == "ident" -> {
                            adoptIdent(message)
                            onReady(this)
                        }
                        established -> onMessage(this, message)
                        // Anything before ident is traffic from a peer that has
                        // authenticated but not introduced itself; the desktop
                        // ignores it too.
                    }
                }
            }
        } finally {
            watchdog.cancel()
            close()
        }
    }

    suspend fun send(message: JsonObject) {
        if (closed) return
        lock.withLock {
            val write = writeChannel ?: return
            if (!channel.established) return
            try {
                channel.send(message)
            } catch (exc: ProtocolException) {
                return // send on a dead channel
            }
            flushLocked(write)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        // Closing the socket is what unblocks readAvailable() in run().
        runCatching { socket?.close() }
    }

    private suspend fun flushLocked(write: ByteWriteChannel) {
        val data = channel.takeOutput()
        if (data.isNotEmpty()) write.writeFully(data)
    }

    private fun adoptIdent(message: JsonObject) {
        val id = message["device_id"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrEmpty()) {
            close()
            throw ProtocolException("peer $address sent a malformed ident")
        }
        peerId = id
        caption = message["caption"]?.jsonPrimitive?.contentOrNull ?: id.take(8)
        peerPort = message["port"]?.jsonPrimitive?.intOrNull ?: 0
    }
}

internal fun JsonObject.typeOrNull(): String? = this["type"]?.jsonPrimitive?.contentOrNull
