"""MPRIS2 service via dbus-fast, running an asyncio loop inside one QThread.

Bridge rules:
- PlayerService Qt signals → loop.call_soon_threadsafe(...) property updates.
- MPRIS method calls → QMetaObject.invokeMethod(..., QueuedConnection) back
  onto the main thread. Never touch Qt objects from the dbus thread directly.
"""

from __future__ import annotations

import asyncio
import logging
import threading
import time

from dbus_fast import PropertyAccess
from dbus_fast.aio import MessageBus
from dbus_fast.service import ServiceInterface, dbus_property, method, signal as dbus_signal
from PySide6.QtCore import QObject, QThread, Signal
from PySide6.QtMultimedia import QMediaPlayer

log = logging.getLogger(__name__)

BUS_NAME = "org.mpris.MediaPlayer2.aerialpod"
OBJECT_PATH = "/org/mpris/MediaPlayer2"


class _RootInterface(ServiceInterface):
    def __init__(self):
        super().__init__("org.mpris.MediaPlayer2")

    @method()
    def Raise(self):  # noqa: N802
        pass  # single-instance socket already raises the window

    @method()
    def Quit(self):  # noqa: N802
        pass

    @dbus_property(access=PropertyAccess.READ)
    def CanRaise(self) -> "b":  # noqa: F821, N802
        return False

    @dbus_property(access=PropertyAccess.READ)
    def CanQuit(self) -> "b":  # noqa: F821, N802
        return False

    @dbus_property(access=PropertyAccess.READ)
    def HasTrackList(self) -> "b":  # noqa: F821, N802
        return False

    @dbus_property(access=PropertyAccess.READ)
    def Identity(self) -> "s":  # noqa: F821, N802
        return "AerialPod"

    @dbus_property(access=PropertyAccess.READ)
    def DesktopEntry(self) -> "s":  # noqa: F821, N802
        return "aerialpod"

    @dbus_property(access=PropertyAccess.READ)
    def SupportedUriSchemes(self) -> "as":  # noqa: F821, N802
        return []

    @dbus_property(access=PropertyAccess.READ)
    def SupportedMimeTypes(self) -> "as":  # noqa: F821, N802
        return []


class _PlayerInterface(ServiceInterface):
    def __init__(self, bridge: "MprisBridge"):
        super().__init__("org.mpris.MediaPlayer2.Player")
        self.bridge = bridge
        self.playback_status = "Stopped"
        self.metadata: dict = {}
        self.position_us = 0
        self.rate = 1.0
        self.volume = 1.0

    # ---- methods (called from the dbus thread → cross-thread signals, which
    # Qt auto-queues onto the receiver's (main) thread)

    @method()
    def Play(self):  # noqa: N802
        self.bridge.playRequested.emit()

    @method()
    def Pause(self):  # noqa: N802
        self.bridge.pauseRequested.emit()

    @method()
    def PlayPause(self):  # noqa: N802
        self.bridge.playPauseRequested.emit()

    @method()
    def Stop(self):  # noqa: N802
        self.bridge.pauseRequested.emit()

    @method()
    def Next(self):  # noqa: N802
        self.bridge.nextRequested.emit()

    @method()
    def Previous(self):  # noqa: N802
        self.bridge.previousRequested.emit()

    @method()
    def Seek(self, offset: "x"):  # noqa: F821, N802
        self.bridge.seekUsRequested.emit(int(offset))

    @method()
    def SetPosition(self, track_id: "o", position: "x"):  # noqa: F821, N802
        self.bridge.setPositionUsRequested.emit(int(position))

    @method()
    def OpenUri(self, uri: "s"):  # noqa: F821, N802
        pass

    # ---- signals

    @dbus_signal()
    def Seeked(self, position: "x"):  # noqa: F821, N802
        pass

    # ---- properties

    @dbus_property(access=PropertyAccess.READ)
    def PlaybackStatus(self) -> "s":  # noqa: F821, N802
        return self.playback_status

    @dbus_property(access=PropertyAccess.READ)
    def Metadata(self) -> "a{sv}":  # noqa: F821, N802
        return self.metadata

    @dbus_property(access=PropertyAccess.READ)
    def Position(self) -> "x":  # noqa: F821, N802
        return self.position_us

    @dbus_property()
    def Rate(self) -> "d":  # noqa: F821, N802
        return self.rate

    @Rate.setter
    def Rate(self, value: "d"):  # noqa: F821, N802
        self.bridge.setRateRequested.emit(float(value))

    @dbus_property(access=PropertyAccess.READ)
    def MinimumRate(self) -> "d":  # noqa: F821, N802
        return 0.5

    @dbus_property(access=PropertyAccess.READ)
    def MaximumRate(self) -> "d":  # noqa: F821, N802
        return 3.0

    @dbus_property()
    def Volume(self) -> "d":  # noqa: F821, N802
        return self.volume

    @Volume.setter
    def Volume(self, value: "d"):  # noqa: F821, N802
        self.bridge.setVolumeRequested.emit(max(0.0, min(1.0, float(value))))

    @dbus_property(access=PropertyAccess.READ)
    def CanGoNext(self) -> "b":  # noqa: F821, N802
        return True

    @dbus_property(access=PropertyAccess.READ)
    def CanGoPrevious(self) -> "b":  # noqa: F821, N802
        return True

    @dbus_property(access=PropertyAccess.READ)
    def CanPlay(self) -> "b":  # noqa: F821, N802
        return True

    @dbus_property(access=PropertyAccess.READ)
    def CanPause(self) -> "b":  # noqa: F821, N802
        return True

    @dbus_property(access=PropertyAccess.READ)
    def CanSeek(self) -> "b":  # noqa: F821, N802
        return True

    @dbus_property(access=PropertyAccess.READ)
    def CanControl(self) -> "b":  # noqa: F821, N802
        return True


class MprisBridge(QObject):
    """Owns the dbus thread; call the update_* methods from the main thread."""

    # Emitted from the dbus thread; connect to main-thread QObject slots only.
    playRequested = Signal()
    pauseRequested = Signal()
    playPauseRequested = Signal()
    nextRequested = Signal()
    previousRequested = Signal()
    seekUsRequested = Signal(object)         # int µs offset (object: no 32-bit clamp)
    setPositionUsRequested = Signal(object)  # int µs absolute
    setRateRequested = Signal(float)
    setVolumeRequested = Signal(float)

    def __init__(self, player, parent: QObject | None = None):
        super().__init__(parent)
        self.player = player
        self.loop: asyncio.AbstractEventLoop | None = None
        self.iface: _PlayerInterface | None = None
        self._bus = None
        self._ready = threading.Event()
        self._last_bump = 0.0

        self.thread = QThread()
        self.thread.setObjectName("mpris")
        self.thread.run = self._thread_main  # plain run override; no Qt event loop needed
        self.thread.start()

        # main-thread signal wiring (player state → dbus properties)
        player.playbackStateChanged.connect(self._on_state)
        player.episodeChanged.connect(self._on_episode)
        player.positionChanged.connect(self._on_position)
        player.rateChanged.connect(self._on_rate)
        player.volumeChanged.connect(self._on_volume)
        player.seeked.connect(self.notify_seeked)

        # dbus commands → player (cross-thread; auto-queued: receiver is a
        # main-thread QObject and these connect to its bound methods)
        self.playRequested.connect(player.resume)
        self.pauseRequested.connect(player.pause)
        self.playPauseRequested.connect(player.toggle_play_pause)
        self.seekUsRequested.connect(player.seek_by_us)
        self.setPositionUsRequested.connect(player.set_position_us)
        self.setRateRequested.connect(player.set_rate)
        self.setVolumeRequested.connect(player.set_volume)
        # nextRequested/previousRequested are wired by MainWindow (behavior
        # depends on the media_next_action setting and the queue)

    # ------------------------------------------------------------ dbus thread

    def _thread_main(self) -> None:
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self._serve())
        except RuntimeError:
            log.debug("MPRIS loop stopped (shutdown)")
        except Exception:  # noqa: BLE001
            log.exception("MPRIS service failed — media keys unavailable")

    async def _serve(self) -> None:
        self._bus = bus = await MessageBus().connect()
        self.iface = _PlayerInterface(self)
        bus.export(OBJECT_PATH, _RootInterface())
        bus.export(OBJECT_PATH, self.iface)
        await bus.request_name(BUS_NAME)
        log.info("MPRIS service up at %s", BUS_NAME)
        self._ready.set()
        await bus.wait_for_disconnect()

    async def _bump_priority_async(self) -> None:
        """Re-register the well-known name. gnome-settings-daemon routes media
        keys to the most recently appeared MPRIS player, and there is no grab
        API anymore — cycling our name puts AerialPod at the head of gsd's
        player list, so the keyboard's play/pause/seek keys control the
        podcast instead of e.g. a browser tab that registered later."""
        try:
            await self._bus.release_name(BUS_NAME)
            await self._bus.request_name(BUS_NAME)
            log.debug("MPRIS priority bump ok")
        except Exception as exc:  # noqa: BLE001 — never kill the dbus loop
            log.warning("MPRIS priority bump failed: %s", exc)

    def _post(self, fn) -> None:
        if self.loop is not None and self._ready.is_set():
            self.loop.call_soon_threadsafe(fn)

    def shutdown(self) -> None:
        if self.loop is not None:
            self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.quit()
        self.thread.wait(2000)

    # ------------------------------------------------------------ main thread → dbus

    def _on_state(self, state) -> None:
        status = {
            QMediaPlayer.PlaybackState.PlayingState: "Playing",
            QMediaPlayer.PlaybackState.PausedState: "Paused",
        }.get(state, "Stopped")

        def apply():
            if self.iface:
                self.iface.playback_status = status
                self.iface.emit_properties_changed({"PlaybackStatus": status})

        self._post(apply)

        # Starting playback claims the media keys (debounced against rapid
        # play/pause toggling).
        if status == "Playing" and time.monotonic() - self._last_bump > 5.0:
            self._last_bump = time.monotonic()
            if self.loop is not None and self._ready.is_set():
                self.loop.call_soon_threadsafe(
                    lambda: asyncio.ensure_future(self._bump_priority_async())
                )

    def _on_episode(self, ep) -> None:
        from dbus_fast import Variant

        if ep is None:
            meta = {"mpris:trackid": Variant("o", "/org/mpris/MediaPlayer2/TrackList/NoTrack")}
        else:
            from ..db import repo

            p = repo.podcast_by_id(ep.podcast_id)
            meta = {
                "mpris:trackid": Variant("o", f"/org/aerialpod/track/{ep.id}"),
                "xesam:title": Variant("s", ep.title or ""),
                "xesam:artist": Variant("as", [repo.display_title(p)] if p else []),
                "xesam:album": Variant("s", repo.display_title(p) if p else ""),
            }
            total = ep.total_secs or ep.duration_secs or 0
            if total:
                meta["mpris:length"] = Variant("x", total * 1_000_000)
            art = ep.image_url or (p.image_url if p else None)
            if art:
                meta["mpris:artUrl"] = Variant("s", art)

        def apply():
            if self.iface:
                self.iface.metadata = meta
                self.iface.emit_properties_changed({"Metadata": meta})

        self._post(apply)

    def _on_position(self, secs: int, _total: int) -> None:
        def apply():
            if self.iface:
                self.iface.position_us = secs * 1_000_000

        self._post(apply)

    def _on_rate(self, rate: float) -> None:
        def apply():
            if self.iface:
                self.iface.rate = rate
                self.iface.emit_properties_changed({"Rate": rate})

        self._post(apply)

    def _on_volume(self, linear: float) -> None:
        def apply():
            if self.iface:
                self.iface.volume = linear
                self.iface.emit_properties_changed({"Volume": linear})

        self._post(apply)

    def notify_seeked(self, secs: int) -> None:
        def apply():
            if self.iface:
                self.iface.position_us = secs * 1_000_000
                self.iface.Seeked(secs * 1_000_000)

        self._post(apply)
