package org.aerialpod.core.lan

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.aerialpod.core.db.Repo

/**
 * The mobile peer: a dial-out-only member of the desktop's mesh.
 *
 * Speaks the same wire protocol as the desktop, byte for byte, but omits the
 * three things that make `lan/service.py` hostile on a phone —
 *
 *  - **No listening socket.** One outbound TCP connection carries the whole
 *    conversation in both directions, so `QTcpServer` and `_on_incoming` have
 *    no mobile counterpart. `_preferred()` goes with them: two ends can never
 *    dial each other simultaneously when only one end dials at all.
 *  - **No subnet sweep.** `discovery.sweep()` knocks on every host in the local
 *    /22. On iOS that reads as port-scanning and burns battery; on both
 *    platforms it is unnecessary, because the desktop is a stable, remembered
 *    address. "A syncable device is detected" here simply means a remembered
 *    address answered the dial and completed the handshake.
 *  - **No timers.** `RETRY_INTERVAL_MS` is replaced by path callbacks,
 *    `RESYNC_INTERVAL_MS` by the fact that reconnection *is* the safety net (a
 *    dropped or reordered frame fails `Sealer.open()`, which is fatal to the
 *    channel by design, so the next dial begins with a full snapshot exchange),
 *    and `POSITION_THROTTLE_MS` by syncing on transport events only.
 *
 * See docs/mobile-lan-sync.md for the reasoning behind each of those.
 */
class LanPeerService(
    private val repo: Repo,
    private val sync: SnapshotSync,
    private val channelKey: () -> ByteArray,
    private val deviceCaption: String,
    private val scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    companion object {
        /** Tuned on the desktop; do not re-derive. Coalesces a burst of queue edits. */
        const val SNAPSHOT_DEBOUNCE_MS = 2_000L

        /**
         * One WiFi association fires several path callbacks in a row (associate,
         * DHCP, route install); coalesce them into a single dial.
         */
        const val PATH_DEBOUNCE_MS = 2_000L

        const val BACKOFF_BASE_MS = 2_000L
        const val BACKOFF_MAX_MS = 300_000L
        private const val BACKOFF_MAX_SHIFT = 7
    }

    data class PeerInfo(val deviceId: String, val caption: String, val address: String)

    private val selector = SelectorManager(ioDispatcher)

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    // Not "starting…": nothing is starting until start() is called, and on
    // Android that waits for the platform callbacks in step 6.4. A status line
    // that claims progress the service is not making is worse than a blunt one.
    private val _status = MutableStateFlow("Device sync has not started.")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _merged = MutableSharedFlow<MergeCounts>(extraBufferCapacity = 16)
    /** Emitted after a peer's state landed — the app reconciles its queue from here. */
    val merged: SharedFlow<MergeCounts> = _merged.asSharedFlow()

    private val mutex = Mutex()
    private val links = mutableMapOf<String, PeerConnection>()
    private val pending = mutableSetOf<String>()
    private var running = false
    private var failures = 0

    private var retryJob: Job? = null
    private var pathJob: Job? = null
    private var snapshotJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        scope.launch {
            val begin = mutex.withLock {
                if (running) return@withLock false
                if (!repo.lanSyncEnabled()) {
                    _status.value = "Device sync is off."
                    return@withLock false
                }
                running = true
                true
            }
            if (!begin) return@launch

            // Intents for episodes finished long ago can't change any outcome,
            // and every one of them rides in every snapshot. Once per run is plenty.
            val dropped = repo.pruneIntents()
            if (dropped > 0) _status.value = "Pruned $dropped settled queue intent(s)."

            _status.value = "Looking for your other devices…"
            dialKnownPeers()
        }
    }

    fun stop() {
        scope.launch {
            val open = mutex.withLock {
                running = false
                retryJob?.cancel(); pathJob?.cancel(); snapshotJob?.cancel()
                val open = links.values.toList()
                links.clear()
                pending.clear()
                open
            }
            open.forEach { it.close() }
            _peers.value = emptyList()
            _status.value = "Device sync stopped."
        }
    }

    // ---------------------------------------------------------------- triggers
    //
    // The table in docs/mobile-lan-sync.md, one function per row.

    /** Network path became syncable, or changed. Debounced, and resets backoff. */
    fun onPathChanged() {
        pathJob?.cancel()
        pathJob = scope.launch {
            delay(PATH_DEBOUNCE_MS)
            resetBackoff()
            dialKnownPeers()
        }
    }

    /** App foregrounded on a syncable path. */
    fun onForegrounded() {
        scope.launch {
            resetBackoff()
            dialKnownPeers()
        }
    }

    /** Playback started; dial only if nothing is up. */
    fun onPlaybackStarted() {
        scope.launch {
            val idle = mutex.withLock { links.isEmpty() }
            if (idle) {
                resetBackoff()
                dialKnownPeers()
            }
        }
    }

    /**
     * Play / pause / seek / stop / episode change — the moment the other device
     * most wants to know. The caller has already written the position, so the
     * row read here is current.
     *
     * There is deliberately no `note_position()` counterpart: the desktop's 5 s
     * heartbeat while playing is dropped on mobile, because a position push is
     * 261 bytes and transport events are the only ones a handoff actually needs.
     */
    fun onTransportEvent(episodeId: Long?) {
        if (episodeId == null) return
        scope.launch {
            val message = sync.positionMessageFor(episodeId) ?: return@launch
            broadcast(message.toJsonObject())
        }
    }

    /** Queue edit — add, remove, reorder, pin. Coalesced over [SNAPSHOT_DEBOUNCE_MS]. */
    fun onQueueEdited() {
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            delay(SNAPSHOT_DEBOUNCE_MS)
            pushSnapshot()
        }
    }

    /**
     * Backgrounded. While audio is playing the foreground service keeps us
     * alive and the link is worth holding open — pause and stop pushes then
     * land live. Otherwise there is nothing to say and a socket to stop paying
     * for.
     */
    fun onBackgrounded(isPlaying: Boolean) {
        if (isPlaying) return
        scope.launch {
            val open = mutex.withLock {
                val open = links.values.toList()
                links.clear()
                open
            }
            open.forEach { it.close() }
            _peers.value = emptyList()
        }
    }

    /** The WireGuard `/32` case: an address the user typed in by hand. */
    fun addManualPeer(address: String, port: Int) {
        scope.launch {
            repo.addManualPeer(address, port)
            resetBackoff()
            connectTo(address, port)
        }
    }

    /** Fire the first dial from the pairing screen, so an iOS local-network
     *  prompt arrives with visible context rather than at first launch. */
    fun dialNow() {
        scope.launch {
            resetBackoff()
            dialKnownPeers()
        }
    }

    // ---------------------------------------------------------------- dialling

    private suspend fun dialKnownPeers() {
        if (!mutex.withLock { running }) return
        val fallbackPort = repo.lanPort()
        val targets = LinkedHashSet<Pair<String, Int>>()
        for (peer in repo.knownPeers()) {
            val address = peer.address ?: continue
            if (address.isEmpty()) continue
            targets += address to (peer.port.toInt().takeIf { it > 0 } ?: fallbackPort)
        }
        targets += repo.manualPeers()

        if (targets.isEmpty()) {
            _status.value = "No paired devices yet."
            return
        }
        for ((address, port) in targets) connectTo(address, port)
    }

    private suspend fun connectTo(address: String, port: Int) {
        val target = "$address:$port"
        val proceed = mutex.withLock {
            if (!running || target in pending) return@withLock false
            // Already talking to this machine — a flapping network must not
            // stack duplicate connections.
            if (links.values.any { it.address == address && it.established }) return@withLock false
            pending += target
            true
        }
        if (!proceed) return

        scope.launch {
            val connection = PeerConnection(
                address = address,
                port = port,
                key = channelKey(),
                ident = ident(),
                selector = selector,
            )
            var reported = false
            try {
                connection.run(scope, ::onReady, ::onMessage)
            } catch (exc: TimeoutCancellationException) {
                // The connect deadline. It is a CancellationException, so it has
                // to be caught before the branch below or it rethrows and gets
                // reported as a refusal — which is a different problem with a
                // different fix, and sends the user looking at pairing codes
                // when the connection never left the device.
                reported = true
                onDialFailed(address, exc)
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                reported = true
                // A dial to an RFC1918 address over cellular dies immediately,
                // and that is fine: the cost is one TCP SYN and no snapshot is
                // ever exchanged. Nothing here distinguishes it from any other
                // failure, which is exactly why there is no metered-network gate.
                onDialFailed(address, exc)
            } finally {
                mutex.withLock { pending -= target }
                // A peer that rejects our handshake just closes the socket, so
                // the read loop ends normally and nothing above throws. Without
                // this the commonest setup mistake — a pairing code that does
                // not match — produces complete silence: no error, no peer, and
                // a status line still showing whatever it said beforehand.
                if (!reported && !connection.established) onHandshakeRejected(address)
                onClosed(connection)
            }
        }
    }

    /**
     * Reached the peer, and it would not talk to us.
     *
     * Practically always a pairing code that does not match — the handshake is
     * the only thing that can fail this way — so the message names that rather
     * than reporting a generic connection problem the user cannot act on.
     */
    private suspend fun onHandshakeRejected(address: String) {
        val attempts = mutex.withLock { ++failures }
        _status.value = "$address refused the pairing. Check that both devices show the same code."
        scheduleRetry(attempts)
    }

    private suspend fun onDialFailed(address: String, exc: Exception) {
        val attempts = mutex.withLock { ++failures }
        _status.value = "Couldn't reach $address (${exc.message ?: "no route"})."
        scheduleRetry(attempts)
    }

    private suspend fun scheduleRetry(attempts: Int) {
        mutex.withLock {
            if (!running || retryJob?.isActive == true) return
            val shift = minOf(attempts - 1, BACKOFF_MAX_SHIFT)
            val wait = minOf(BACKOFF_BASE_MS shl shift, BACKOFF_MAX_MS)
            retryJob = scope.launch {
                delay(wait)
                dialKnownPeers()
            }
        }
    }

    private suspend fun resetBackoff() {
        mutex.withLock {
            failures = 0
            retryJob?.cancel()
            retryJob = null
        }
    }

    // ---------------------------------------------------------------- events

    private suspend fun onReady(connection: PeerConnection) {
        val peerId = connection.peerId ?: return
        if (peerId == repo.lanDeviceId()) {
            // Dialled our own address — nothing to sync with ourselves.
            connection.close()
            return
        }

        val adopted = mutex.withLock {
            val existing = links[peerId]
            if (existing != null && existing !== connection) return@withLock false
            links[peerId] = connection
            true
        }
        if (!adopted) {
            connection.close()
            return
        }

        repo.rememberPeer(
            deviceId = peerId,
            caption = connection.caption,
            address = plainAddress(connection.address),
            // The peer's ident is authoritative about where to reach it next
            // time; falling back on the port we dialled is the best we can do.
            port = connection.peerPort.takeIf { it > 0 } ?: connection.port,
        )
        resetBackoff()
        emitPeers()
        _status.value = "Synced with ${connection.caption}."

        // Snapshot exchange on connect — both ends send one, so this is the
        // whole catch-up protocol.
        connection.send(sync.buildSnapshot().toJsonObject())
    }

    private suspend fun onMessage(connection: PeerConnection, message: JsonObject) {
        try {
            when (message.typeOrNull()) {
                "snapshot" -> {
                    val counts = sync.mergeSnapshot(message.decodeAs<Snapshot>())
                    if (counts.any()) _merged.emit(counts)
                }
                "position" -> {
                    if (sync.applyPositionMessage(message.decodeAs<PositionMessage>())) {
                        _merged.emit(MergeCounts(positions = 1))
                    }
                }
                "ping" -> Unit
                else -> Unit // unknown type: a newer peer talking about something we don't have
            }
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            // A bad peer must never take the service down with it.
            _status.value = "Ignored an unreadable message from ${connection.caption}."
        }
    }

    private suspend fun onClosed(connection: PeerConnection) {
        val peerId = connection.peerId
        val removed = mutex.withLock {
            if (peerId != null && links[peerId] === connection) {
                links.remove(peerId)
                true
            } else {
                false
            }
        }
        if (removed) emitPeers()
    }

    private suspend fun broadcast(message: JsonObject) {
        val open = mutex.withLock { links.values.toList() }
        for (connection in open) connection.send(message)
    }

    /**
     * Send our state to every peer whether or not it has changed.
     *
     * This is the change-driven path, so it must not second-guess whether there
     * is news: `replicatedVersion()` has one-second resolution and would swallow
     * an edit made in the same second as the previous push.
     */
    private suspend fun pushSnapshot() {
        val open = mutex.withLock { links.values.toList() }
        if (open.isEmpty()) return
        val snapshot = sync.buildSnapshot().toJsonObject()
        for (connection in open) connection.send(snapshot)
    }

    private suspend fun emitPeers() {
        _peers.value = mutex.withLock {
            links.map { (id, link) -> PeerInfo(id, link.caption, plainAddress(link.address)) }
        }
    }

    private fun ident() = Ident(
        deviceId = repo.lanDeviceId(),
        caption = deviceCaption,
        port = 0, // a phone never listens; see Ident.port
    )
}
