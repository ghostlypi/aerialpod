"""The daemon's side of the bus: export the hub, marshal calls onto its thread.

Same arrangement as the MPRIS bridge — an asyncio loop inside one QThread —
because the same rule applies: D-Bus callbacks arrive on that loop's thread and
must never touch the hub or its Qt objects directly. Commands cross back on a
queued Qt signal; signals cross out through call_soon_threadsafe.
"""

from __future__ import annotations

import asyncio
import logging
import threading

from dbus_fast import RequestNameReply
from dbus_fast.aio import MessageBus
from dbus_fast.service import ServiceInterface, method, signal as dbus_signal
from PySide6.QtCore import QObject, QThread, Signal

from .protocol import BUS_NAME, INTERFACE, OBJECT_PATH, decode_args

log = logging.getLogger(__name__)


class _Interface(ServiceInterface):
    """Every command is void, so each of these can return the moment it has
    handed the work to the hub's thread. Nothing here blocks on a reply."""

    def __init__(self, relay: "DaemonBus", app_version: str, schema: int):
        super().__init__(INTERFACE)
        self.relay = relay
        self.app_version = app_version
        self.schema = schema

    def _cmd(self, name: str, *args) -> None:
        self.relay.command.emit(name, decode_args(name, list(args)))

    # ---- handshake

    @method()
    def Version(self) -> "su":  # noqa: F821, N802
        return [self.app_version, self.schema]

    # ---- sync, feeds, subscriptions

    @method()
    def SyncNow(self):  # noqa: N802
        self._cmd("sync_now")

    @method()
    def RefreshAll(self):  # noqa: N802
        self._cmd("refresh_all")

    @method()
    def RefreshOne(self, podcast_id: "u"):  # noqa: F821, N802
        self._cmd("refresh_one", podcast_id)

    @method()
    def Subscribe(self, feed_url: "s"):  # noqa: F821, N802
        self._cmd("subscribe", feed_url)

    @method()
    def Unsubscribe(self, podcast_id: "u"):  # noqa: F821, N802
        self._cmd("unsubscribe", podcast_id)

    @method()
    def ImportOpml(self, path: "s"):  # noqa: F821, N802
        self._cmd("import_opml", path)

    @method()
    def SetAccount(self, username: "s", password: "s"):  # noqa: F821, N802
        self._cmd("set_account", username, password)

    @method()
    def ForgetAccount(self):  # noqa: N802
        self._cmd("forget_account")

    # ---- queue

    @method()
    def QueueAdd(self, episode_id: "u", to_front: "b"):  # noqa: F821, N802
        self._cmd("queue_add", episode_id, to_front)

    @method()
    def QueueRemove(self, episode_id: "u", exclude: "b"):  # noqa: F821, N802
        self._cmd("queue_remove", episode_id, exclude)

    @method()
    def QueueToggle(self, episode_id: "u"):  # noqa: F821, N802
        self._cmd("queue_toggle", episode_id)

    @method()
    def QueueMove(self, episode_id: "u", new_index: "i"):  # noqa: F821, N802
        self._cmd("queue_move", episode_id, new_index)

    @method()
    def QueuePin(self, episode_id: "u"):  # noqa: F821, N802
        self._cmd("queue_pin", episode_id)

    @method()
    def QueueReleaseToAuto(self, episode_id: "u"):  # noqa: F821, N802
        self._cmd("queue_release_to_auto", episode_id)

    @method()
    def MarkPlayed(self, episode_id: "u"):  # noqa: F821, N802
        self._cmd("mark_played", episode_id)

    @method()
    def MarkUnplayed(self, episode_id: "u"):  # noqa: F821, N802
        self._cmd("mark_unplayed", episode_id)

    @method()
    def Reconcile(self):  # noqa: N802
        self._cmd("reconcile")

    # ---- playback reported by the window

    @method()
    def ReportPosition(self, episode_id: "u", position: "u", total: "u",  # noqa: F821, N802
                       final: "b"):  # noqa: F821
        self._cmd("report_position", episode_id, position, total, final)

    @method()
    def SetPlaying(self, episode_id: "u"):  # noqa: F821, N802
        self._cmd("set_playing", episode_id)

    # ---- settings

    @method()
    def SetPodcastSetting(self, podcast_id: "u", key: "s", value: "s"):  # noqa: F821, N802
        self._cmd("set_podcast_setting", podcast_id, key, value)

    @method()
    def SetState(self, key: "s", value: "s"):  # noqa: F821, N802
        self._cmd("set_state", key, value)

    # ---- device sync

    @method()
    def LanPair(self, code: "s"):  # noqa: F821, N802
        self._cmd("lan_pair", code)

    @method()
    def LanNewCode(self):  # noqa: N802
        self._cmd("lan_new_code")

    @method()
    def LanAddPeer(self, address: "s", port: "u"):  # noqa: F821, N802
        self._cmd("lan_add_peer", address, port)

    @method()
    def LanRemovePeer(self, address: "s", port: "u"):  # noqa: F821, N802
        self._cmd("lan_remove_peer", address, port)

    @method()
    def LanDiscover(self):  # noqa: N802
        self._cmd("lan_discover")

    @method()
    def AnnounceState(self):  # noqa: N802
        self._cmd("announce_state")

    # ---- signals out

    @dbus_signal()
    def QueueChanged(self):  # noqa: N802
        pass

    @dbus_signal()
    def SyncStarted(self):  # noqa: N802
        pass

    @dbus_signal()
    def SyncFinished(self, message: "s") -> "s":  # noqa: F821, N802
        return message

    @dbus_signal()
    def SyncFailed(self, message: "s") -> "s":  # noqa: F821, N802
        return message

    @dbus_signal()
    def SubscriptionsChanged(self, podcast_ids: "au") -> "au":  # noqa: F821, N802
        return podcast_ids

    @dbus_signal()
    def RefreshStarted(self):  # noqa: N802
        pass

    @dbus_signal()
    def PodcastRefreshed(self, podcast_id: "u") -> "u":  # noqa: F821, N802
        return podcast_id

    @dbus_signal()
    def RefreshFinished(self, new_total: "u") -> "u":  # noqa: F821, N802
        return new_total

    @dbus_signal()
    def RefreshError(self, podcast_id: "u", message: "s") -> "us":  # noqa: F821, N802
        return [podcast_id, message]

    @dbus_signal()
    def PeersChanged(self, peers: "aa{ss}") -> "aa{ss}":  # noqa: F821, N802
        return peers

    @dbus_signal()
    def LanStatus(self, message: "s") -> "s":  # noqa: F821, N802
        return message

    @dbus_signal()
    def PairingChanged(self):  # noqa: N802
        pass

    @dbus_signal()
    def StateMerged(self, counts: "a{si}") -> "a{si}":  # noqa: F821, N802
        return counts

    @dbus_signal()
    def DownloadStarted(self, episode_id: "u") -> "u":  # noqa: F821, N802
        return episode_id

    @dbus_signal()
    def DownloadFinished(self, episode_id: "u") -> "u":  # noqa: F821, N802
        return episode_id

    @dbus_signal()
    def DownloadFailed(self, episode_id: "u", message: "s") -> "us":  # noqa: F821, N802
        return [episode_id, message]


class DaemonBus(QObject):
    """Owns the bus connection and relays in both directions."""

    command = Signal(str, object)   # dbus thread → hub thread
    nameLost = Signal()

    def __init__(self, hub, app_version: str, schema: int, parent: QObject | None = None):
        super().__init__(parent)
        self.hub = hub
        self.app_version = app_version
        self.schema = schema
        self.iface: _Interface | None = None
        self.loop: asyncio.AbstractEventLoop | None = None
        self._bus = None
        self._ready = threading.Event()
        self._failed = False

        self.command.connect(hub.execute)
        for qt_name in hub_signal_names():
            getattr(hub, qt_name).connect(getattr(self, f"_on_{qt_name}"))

        self.thread = QThread()
        self.thread.setObjectName("dbus-daemon")
        self.thread.run = self._run  # type: ignore[method-assign]
        self.thread.start()

    def wait_until_ready(self, timeout: float = 10.0) -> bool:
        self._ready.wait(timeout)
        return self._ready.is_set() and not self._failed

    # ------------------------------------------------------------ loop

    def _run(self) -> None:
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self._serve())
        except RuntimeError:
            log.debug("D-Bus loop stopped (shutdown)")
        except Exception:  # noqa: BLE001
            log.exception("D-Bus service failed")
            self._failed = True
            self._ready.set()

    async def _serve(self) -> None:
        self._bus = bus = await MessageBus().connect()
        self.iface = _Interface(self, self.app_version, self.schema)
        bus.export(OBJECT_PATH, self.iface)
        reply = await bus.request_name(BUS_NAME)
        if reply not in (RequestNameReply.PRIMARY_OWNER, RequestNameReply.ALREADY_OWNER):
            log.error("another AerialPod service already owns %s", BUS_NAME)
            self._failed = True
            self._ready.set()
            return
        log.info("service up at %s", BUS_NAME)
        self._ready.set()
        await bus.wait_for_disconnect()

    def _post(self, fn) -> None:
        if self.loop is not None and self._ready.is_set() and not self._failed:
            self.loop.call_soon_threadsafe(fn)

    def shutdown(self) -> None:
        if self.loop is not None:
            self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.quit()
        self.thread.wait(2000)

    # ------------------------------------------------------------ hub → bus

    def _emit(self, member: str, *args) -> None:
        def fire():
            if self.iface is not None:
                getattr(self.iface, member)(*args)

        self._post(fire)

    def _on_queueChanged(self) -> None:  # noqa: N802
        self._emit("QueueChanged")

    def _on_syncStarted(self) -> None:  # noqa: N802
        self._emit("SyncStarted")

    def _on_syncFinished(self, message: str) -> None:  # noqa: N802
        self._emit("SyncFinished", message)

    def _on_syncFailed(self, message: str) -> None:  # noqa: N802
        self._emit("SyncFailed", message)

    def _on_subscriptionsChanged(self, ids: list) -> None:  # noqa: N802
        self._emit("SubscriptionsChanged", [int(i) for i in ids])

    def _on_refreshStarted(self) -> None:  # noqa: N802
        self._emit("RefreshStarted")

    def _on_podcastRefreshed(self, podcast_id: int) -> None:  # noqa: N802
        self._emit("PodcastRefreshed", int(podcast_id))

    def _on_refreshFinished(self, new_total: int) -> None:  # noqa: N802
        self._emit("RefreshFinished", max(0, int(new_total)))

    def _on_refreshError(self, podcast_id: int, message: str) -> None:  # noqa: N802
        self._emit("RefreshError", int(podcast_id), message)

    def _on_peersChanged(self, peers: list) -> None:  # noqa: N802
        self._emit("PeersChanged", peers_payload(peers))

    def _on_lanStatus(self, message: str) -> None:  # noqa: N802
        self._emit("LanStatus", message)

    def _on_pairingChanged(self) -> None:  # noqa: N802
        self._emit("PairingChanged")

    def _on_stateMerged(self, counts: dict) -> None:  # noqa: N802
        self._emit("StateMerged", {str(k): int(v) for k, v in counts.items()})

    def _on_downloadStarted(self, episode_id: int) -> None:  # noqa: N802
        self._emit("DownloadStarted", int(episode_id))

    def _on_downloadFinished(self, episode_id: int) -> None:  # noqa: N802
        self._emit("DownloadFinished", int(episode_id))

    def _on_downloadFailed(self, episode_id: int, message: str) -> None:  # noqa: N802
        self._emit("DownloadFailed", int(episode_id), message)


def peers_payload(peers: list) -> list[dict[str, str]]:
    """Peer records as aa{ss}. D-Bus dictionaries are homogeneous, so the port
    has to travel as text — the window only displays these, and reads anything
    it needs to act on from the database."""
    return [{str(k): str(v) for k, v in peer.items()} for peer in peers]


def hub_signal_names() -> tuple[str, ...]:
    from .protocol import SIGNALS

    return tuple(SIGNALS)
