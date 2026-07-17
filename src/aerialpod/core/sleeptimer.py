"""Sleep timer: N minutes or end-of-episode, with a volume fade near expiry."""

from __future__ import annotations

import logging

from PySide6.QtCore import QObject, QTimer, Signal

log = logging.getLogger(__name__)

FADE_SECS = 10


class SleepTimer(QObject):
    stateChanged = Signal(str)  # human-readable status ('' = off)

    def __init__(self, player, parent: QObject | None = None):
        super().__init__(parent)
        self.player = player
        self._remaining = 0          # seconds; -1 = end-of-episode mode
        self._saved_volume = 1.0

        self._tick = QTimer(self)
        self._tick.setInterval(1000)
        self._tick.timeout.connect(self._on_tick)

        player.episodeFinished.connect(self._on_episode_finished)

    # ------------------------------------------------------------ control

    def start_minutes(self, minutes: int) -> None:
        self._restore_volume()
        self._saved_volume = self.player.audio.volume()
        self._remaining = minutes * 60
        self._tick.start()
        self.stateChanged.emit(self._label())

    def start_end_of_episode(self) -> None:
        self._restore_volume()
        self._saved_volume = self.player.audio.volume()
        self._remaining = -1
        self._tick.stop()
        self.stateChanged.emit("Sleep: end of episode")

    def extend(self, minutes: int = 10) -> None:
        if self._remaining > 0:
            self._remaining += minutes * 60
            self.stateChanged.emit(self._label())

    def cancel(self) -> None:
        self._tick.stop()
        self._restore_volume()
        self._remaining = 0
        self.stateChanged.emit("")

    @property
    def active(self) -> bool:
        return self._remaining != 0

    # ------------------------------------------------------------ internals

    def _label(self) -> str:
        m, s = divmod(max(self._remaining, 0), 60)
        return f"Sleep: {m}:{s:02d}"

    def _on_tick(self) -> None:
        self._remaining -= 1
        if self._remaining <= 0:
            self._fire()
            return
        if self._remaining <= FADE_SECS:
            self.player.audio.setVolume(
                self._saved_volume * self._remaining / FADE_SECS
            )
        self.stateChanged.emit(self._label())

    def _fire(self) -> None:
        self._tick.stop()
        self._remaining = 0
        self.player.pause()
        self._restore_volume()
        self.stateChanged.emit("")
        log.info("sleep timer fired")

    def _on_episode_finished(self, _eid: int) -> None:
        if self._remaining == -1:
            self._remaining = 0
            self.player.pause()  # stop auto-advance playback
            self.stateChanged.emit("")

    def _restore_volume(self) -> None:
        if self.player.audio.volume() < self._saved_volume:
            self.player.audio.setVolume(self._saved_volume)
