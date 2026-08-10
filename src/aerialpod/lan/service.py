"""LanService: the peer mesh, on its own thread.

Mirrors the gpodder SyncService arrangement — a QObject moved onto a dedicated
QThread, talking to the main thread only through signals — because the same
constraint applies: this code touches the database and blocking sockets, and
must never do either on the UI thread.

Connections are long-lived rather than per-sync. That is what makes "pause on
the desktop, pick it up on the laptop" feel immediate: a position push lands in
milliseconds instead of waiting out the gpodder cycle. Snapshots ride the same
sessions whenever the user changes the queue.
"""

from __future__ import annotations

import logging
import socket
import threading

from PySide6.QtCore import QObject, QThread, QTimer, Signal, Slot
from PySide6.QtNetwork import QHostAddress, QTcpServer, QTcpSocket

from .. import db
from ..db import repo
from . import discovery, pairing, state
from .protocol import Channel, ProtocolError

log = logging.getLogger(__name__)

RETRY_INTERVAL_MS = 30_000        # revisit known peers we aren't connected to
DISCOVERY_INTERVAL_MS = 900_000   # full sweep every 15 minutes
RESYNC_INTERVAL_MS = 300_000      # periodic snapshot exchange as a safety net
CONNECT_TIMEOUT_MS = 5_000


class PeerLink(QObject):
    """One connection to a peer, inbound or outbound.

    Owns the socket and the protocol state machine; knows nothing about what
    the messages mean.
    """

    ready = Signal(object)             # PeerLink — authenticated and identified
    messageReceived = Signal(object, object)  # PeerLink, dict
    closed = Signal(object)            # PeerLink

    def __init__(self, sock: QTcpSocket, role: str, key: bytes, ident: dict,
                 parent: QObject | None = None):
        super().__init__(parent)
        self.socket = sock
        self.role = role
        self.ident = ident
        self.peer_id: str | None = None
        self.caption: str = ""
        self.channel = Channel(role, key)
        self._sent_ident = False
        self._closing = False

        sock.setParent(self)
        sock.readyRead.connect(self._on_ready_read)
        sock.disconnected.connect(self._on_disconnected)
        sock.errorOccurred.connect(self._on_error)

    @property
    def address(self) -> str:
        return self.socket.peerAddress().toString()

    @property
    def established(self) -> bool:
        return self.channel.established and self.peer_id is not None

    def begin(self) -> None:
        """Client links open the conversation; server links wait to be spoken to."""
        self.channel.start()
        self._flush()

    def send(self, message: dict) -> None:
        if not self.channel.established:
            return
        try:
            self.channel.send(message)
        except ProtocolError as exc:
            log.debug("send on a dead channel: %s", exc)
            return
        self._flush()

    def close(self) -> None:
        if self._closing:
            return
        self._closing = True
        self.socket.abort()
        self.closed.emit(self)

    @Slot()
    def abandon_if_unconnected(self) -> None:
        """Handshake deadline. A sweep knocks on every open port in the subnet,
        most of which belong to something else entirely — an SSH daemon will
        happily accept the connection and then wait forever. Anything that
        hasn't proven itself a peer by now is dropped."""
        if not self.established:
            self.close()

    # ------------------------------------------------------------ internals

    def _flush(self) -> None:
        data = self.channel.take_output()
        if data:
            self.socket.write(data)

    def _on_ready_read(self) -> None:
        data = bytes(self.socket.readAll().data())
        try:
            messages = self.channel.feed(data)
            # The first thing over an established channel is always who we are;
            # identity stays behind the handshake so an unauthenticated stranger
            # learns nothing about this install.
            if self.channel.established and not self._sent_ident:
                self._sent_ident = True
                self.channel.send(self.ident)
            self._flush()
        except ProtocolError as exc:
            log.info("dropping peer %s: %s", self.address, exc)
            self.close()
            return

        for message in messages:
            if message.get("type") == "ident":
                self._on_ident(message)
            elif self.established:
                self.messageReceived.emit(self, message)

    def _on_ident(self, message: dict) -> None:
        peer_id = message.get("device_id")
        if not isinstance(peer_id, str) or not peer_id:
            log.info("peer %s sent a malformed ident", self.address)
            self.close()
            return
        self.peer_id = peer_id
        self.caption = str(message.get("caption") or peer_id[:8])
        self.ready.emit(self)

    def _on_disconnected(self) -> None:
        if not self._closing:
            self._closing = True
            self.closed.emit(self)

    def _on_error(self, error) -> None:
        if error != QTcpSocket.SocketError.RemoteHostClosedError:
            log.debug("peer socket error (%s): %s", self.address, self.socket.errorString())
        if not self._closing:
            self._closing = True
            self.closed.emit(self)


class LanService(QObject):
    """Lives on its own QThread; every public entry point is a queued slot."""

    peersChanged = Signal(list)     # [{'device_id', 'caption', 'address'}]
    stateMerged = Signal(dict)      # counts — the main thread reconciles
    statusChanged = Signal(str)

    _sweepFinished = Signal(list)   # internal: worker thread → this thread

    def __init__(self):
        super().__init__()  # no parent — moveToThread requires it
        self._server: QTcpServer | None = None
        self._links: list[PeerLink] = []
        self._by_peer: dict[str, PeerLink] = {}
        self._pending: set[tuple[str, int]] = set()
        self._key: bytes | None = None
        self._sweeping = False
        self._running = False
        self._retry: QTimer | None = None
        self._discovery: QTimer | None = None
        self._resync: QTimer | None = None
        self._sweepFinished.connect(self._on_sweep_finished)

    # ------------------------------------------------------------ lifecycle

    @Slot()
    def start_service(self) -> None:
        if self._running:
            return
        if not repo.get_state("lan_sync_enabled"):
            self.statusChanged.emit("Device sync is off.")
            return

        port = int(repo.get_state("lan_port"))
        self._server = QTcpServer(self)
        self._server.newConnection.connect(self._on_incoming)
        if not self._server.listen(QHostAddress.SpecialAddress.Any, port):
            self.statusChanged.emit(
                f"Device sync couldn't listen on port {port}: {self._server.errorString()}"
            )
            self._server = None
            return
        self._running = True
        log.info("LAN sync listening on port %d as %s", port, repo.lan_device_id()[:8])

        self._retry = QTimer(self)
        self._retry.setInterval(RETRY_INTERVAL_MS)
        self._retry.timeout.connect(self._connect_known_peers)
        self._retry.start()

        self._discovery = QTimer(self)
        self._discovery.setInterval(DISCOVERY_INTERVAL_MS)
        self._discovery.timeout.connect(self.discover_now)
        self._discovery.start()

        self._resync = QTimer(self)
        self._resync.setInterval(RESYNC_INTERVAL_MS)
        self._resync.timeout.connect(self.broadcast_snapshot)
        self._resync.start()

        # Intents for episodes finished long ago can't change any outcome, and
        # every one of them rides in every snapshot. Once per run is plenty.
        dropped = repo.prune_intents()
        if dropped:
            log.debug("pruned %d settled queue intent(s)", dropped)

        self.statusChanged.emit(f"Listening on port {port} — looking for peers…")
        self._connect_known_peers()
        QTimer.singleShot(2000, self.discover_now)

    @Slot()
    def stop_service(self) -> None:
        self._running = False
        for timer in (self._retry, self._discovery, self._resync):
            if timer is not None:
                timer.stop()
        for link in list(self._links):
            link.close()
        self._links.clear()
        self._by_peer.clear()
        if self._server is not None:
            self._server.close()
            self._server = None

    @Slot()
    def restart_service(self) -> None:
        """Settings changed (port, enable, account) — rebuild from scratch."""
        self.stop_service()
        self._key = None
        self.start_service()

    def _session_key(self) -> bytes:
        """Cached because it is needed once per connection attempt, and a
        subnet sweep makes a lot of those. Cleared by restart_service(), which
        is what the pairing UI triggers."""
        if self._key is None:
            self._key = pairing.channel_key()
        return self._key

    # ------------------------------------------------------------ connections

    def _ident(self) -> dict:
        return {
            "type": "ident",
            "device_id": repo.lan_device_id(),
            "caption": socket.gethostname(),
        }

    def _on_incoming(self) -> None:
        assert self._server is not None
        while self._server.hasPendingConnections():
            sock = self._server.nextPendingConnection()
            self._adopt(PeerLink(sock, "server", self._session_key(), self._ident(), self))

    @Slot()
    def _connect_known_peers(self) -> None:
        """Direct dials only — no scanning. Cheap enough to run on a timer."""
        port = int(repo.get_state("lan_port"))
        for peer in repo.known_peers():
            if peer["device_id"] not in self._by_peer and peer["address"]:
                self._connect_to(peer["address"], peer["port"] or port)
        for address, peer_port in repo.manual_peers():
            self._connect_to(address, peer_port)

    def _connect_to(self, address: str, port: int) -> None:
        if not self._running or (address, port) in self._pending:
            return
        if any(link.address == address and link.established for link in self._links):
            return
        sock = QTcpSocket()
        link = PeerLink(sock, "client", self._session_key(), self._ident(), self)
        self._pending.add((address, port))
        link.closed.connect(lambda _l, a=address, p=port: self._pending.discard((a, p)))
        self._adopt(link)
        sock.connected.connect(link.begin)

        # Parented to the link so it dies with it: a bare singleShot would
        # outlive a peer that closed early and fire on a deleted C++ object.
        timeout = QTimer(link)
        timeout.setSingleShot(True)
        timeout.setInterval(CONNECT_TIMEOUT_MS)
        timeout.timeout.connect(link.abandon_if_unconnected)
        timeout.start()

        sock.connectToHost(address, port)

    def _adopt(self, link: PeerLink) -> None:
        self._links.append(link)
        link.ready.connect(self._on_link_ready)
        link.messageReceived.connect(self._on_message)
        link.closed.connect(self._on_link_closed)

    def _on_link_ready(self, link: PeerLink) -> None:
        assert link.peer_id is not None
        if link.peer_id == repo.lan_device_id():
            log.debug("connected to ourselves at %s — dropping", link.address)
            link.close()
            return

        existing = self._by_peer.get(link.peer_id)
        if existing is not None and existing is not link:
            # Both ends dialled each other. Keep one deterministically: the
            # session whose *client* is the lower device id, so both sides drop
            # the same connection and neither is left without a link.
            keeper = self._preferred(existing, link)
            loser = link if keeper is existing else existing
            loser.close()
            if keeper is existing:
                return
        self._by_peer[link.peer_id] = link
        self._pending.discard((link.address, int(repo.get_state("lan_port"))))
        repo.remember_peer(
            link.peer_id, link.caption, link.address,
            self._peer_port(link),
        )
        log.info("peer connected: %s (%s)", link.caption, link.address)
        self._emit_peers()
        link.send(state.build_snapshot())

    def _peer_port(self, link: PeerLink) -> int:
        """The port to dial next time. For an outbound link that's the one we
        connected to; for an inbound one the source port is ephemeral and
        useless, so assume the peer listens where we do."""
        default = int(repo.get_state("lan_port"))
        return link.socket.peerPort() if link.role == "client" else default

    def _preferred(self, a: PeerLink, b: PeerLink) -> PeerLink:
        ours = repo.lan_device_id()
        theirs = a.peer_id or b.peer_id or ""
        want_client_role = ours < theirs
        for link in (a, b):
            if (link.role == "client") == want_client_role:
                return link
        return a

    def _on_link_closed(self, link: PeerLink) -> None:
        if link in self._links:
            self._links.remove(link)
        if link.peer_id and self._by_peer.get(link.peer_id) is link:
            del self._by_peer[link.peer_id]
            log.info("peer disconnected: %s", link.caption)
            self._emit_peers()
        link.deleteLater()

    def _emit_peers(self) -> None:
        self.peersChanged.emit([
            {"device_id": pid, "caption": link.caption, "address": link.address}
            for pid, link in self._by_peer.items()
        ])

    # ------------------------------------------------------------ messages

    def _on_message(self, link: PeerLink, message: dict) -> None:
        kind = message.get("type")
        try:
            if kind == "snapshot":
                counts = state.merge_snapshot(message)
                if any(counts.values()):
                    log.info("merged from %s: %s", link.caption, counts)
                    self.stateMerged.emit(counts)
            elif kind == "position":
                if state.apply_position_message(message):
                    self.stateMerged.emit({"positions": 1})
            elif kind == "ping":
                pass
            else:
                log.debug("ignoring unknown message type %r from %s", kind, link.caption)
        except ValueError as exc:
            log.warning("rejected a message from %s: %s", link.caption, exc)
        except Exception:  # noqa: BLE001 — a bad peer must never kill this thread
            log.exception("failed to apply a %s from %s", kind, link.caption)

    @Slot()
    def broadcast_snapshot(self) -> None:
        if not self._by_peer:
            return
        snapshot = state.build_snapshot()
        for link in self._by_peer.values():
            link.send(snapshot)

    @Slot(object)
    def push_position(self, payload: object) -> None:
        if not self._by_peer or not isinstance(payload, dict):
            return
        for link in self._by_peer.values():
            link.send(payload)

    @Slot(str, int)
    def add_peer(self, address: str, port: int) -> None:
        repo.add_manual_peer(address, port)
        self._connect_to(address, port)

    # ------------------------------------------------------------ discovery

    @Slot()
    def discover_now(self) -> None:
        self._connect_known_peers()
        if self._sweeping or not self._running:
            return
        if not repo.get_state("lan_scan_subnets"):
            return
        self._sweeping = True
        threading.Thread(target=self._sweep_worker, daemon=True,
                         name="aerialpod-lan-sweep").start()

    def _sweep_worker(self) -> None:
        """Off-thread: a subnet sweep blocks for seconds and would stall every
        live session if it ran on the event loop."""
        port = int(repo.get_state("lan_port"))
        try:
            hosts = discovery.sweep(port)
        except Exception:  # noqa: BLE001
            log.exception("peer sweep failed")
            hosts = []
        # Bound method on a QObject in the service thread → queued delivery.
        self._sweepFinished.emit(hosts)

    @Slot(list)
    def _on_sweep_finished(self, hosts: list) -> None:
        self._sweeping = False
        port = int(repo.get_state("lan_port"))
        for address in hosts:
            self._connect_to(address, port)
        if hosts:
            log.debug("sweep found %d candidate host(s)", len(hosts))


def start_lan_service() -> tuple[LanService, QThread]:
    """Create the service on its own thread. Caller keeps both references."""
    thread = QThread()
    thread.setObjectName("lan-sync")
    service = LanService()
    service.moveToThread(thread)
    thread.finished.connect(lambda: db.close_thread_connection())
    thread.start()
    return service, thread


class LanScheduler(QObject):
    """Main-thread half: throttles what gets pushed and when.

    Signals here are connected to LanService's slots, which live on the other
    thread — Qt queues them automatically. (Calling the service directly, or
    connecting a lambda, would run that code on this thread and touch the
    peer sockets from the wrong place.)
    """

    startRequested = Signal()
    stopRequested = Signal()
    restartRequested = Signal()
    discoverRequested = Signal()
    snapshotRequested = Signal()
    positionRequested = Signal(object)
    peerAddRequested = Signal(str, int)

    POSITION_THROTTLE_MS = 5000
    SNAPSHOT_DEBOUNCE_MS = 2000

    def __init__(self, service: LanService, parent: QObject | None = None):
        super().__init__(parent)
        self.service = service
        self.startRequested.connect(service.start_service)
        self.stopRequested.connect(service.stop_service)
        self.restartRequested.connect(service.restart_service)
        self.discoverRequested.connect(service.discover_now)
        self.snapshotRequested.connect(service.broadcast_snapshot)
        self.positionRequested.connect(service.push_position)
        self.peerAddRequested.connect(service.add_peer)

        self._pending_episode_id: int | None = None
        self._position_timer = QTimer(self)
        self._position_timer.setInterval(self.POSITION_THROTTLE_MS)
        self._position_timer.timeout.connect(self._flush_position)

        self._snapshot_timer = QTimer(self)
        self._snapshot_timer.setSingleShot(True)
        self._snapshot_timer.setInterval(self.SNAPSHOT_DEBOUNCE_MS)
        self._snapshot_timer.timeout.connect(self.snapshotRequested)

    def start(self) -> None:
        self.startRequested.emit()

    def stop(self) -> None:
        self.stopRequested.emit()

    def restart(self) -> None:
        self.restartRequested.emit()

    def discover(self) -> None:
        self.discoverRequested.emit()

    def add_peer(self, address: str, port: int) -> None:
        self.peerAddRequested.emit(address, port)

    def push_snapshot_soon(self) -> None:
        """Coalesce a burst of queue edits into one push."""
        self._snapshot_timer.start()

    def note_position(self, episode_id: int | None) -> None:
        """Called as playback advances — several times a second. Only the
        episode id is kept here; the row is read at flush time, so a busy
        player costs one query per throttle window rather than per tick."""
        if episode_id is None:
            return
        self._pending_episode_id = episode_id
        if not self._position_timer.isActive():
            self._flush_position()
            self._position_timer.start()

    def flush_now(self) -> None:
        """Pause/seek/stop — the moment the other device most wants to know.
        PlayerService has already written the position by the time these fire,
        so the row we read is current."""
        self._flush_position()

    def _flush_position(self) -> None:
        episode_id = self._pending_episode_id
        self._pending_episode_id = None
        if episode_id is None:
            self._position_timer.stop()
            return
        episode = repo.episode_by_id(episode_id)
        if episode is None:
            return
        message = state.position_message(episode)
        if message is not None:
            self.positionRequested.emit(message)
