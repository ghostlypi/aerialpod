"""PlayerService: QMediaPlayer-based playback with position persistence,
gpodder action emission, per-podcast speed/skip, and runtime audio-device
switching (follow-default or pinned) — the fix for the Kasts pain point.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from pathlib import Path

from PySide6.QtCore import QObject, QTimer, QUrl, Signal
from PySide6.QtMultimedia import QAudio, QAudioOutput, QMediaDevices, QMediaPlayer

from ..db import repo
from ..db.models import Episode

log = logging.getLogger(__name__)

POSITION_WRITE_MS = 5000       # throttled position persistence
PLAY_ACTION_MS = 60000         # periodic gpodder play action while playing
DEVICE_DEBOUNCE_MS = 400       # audioOutputsChanged fires in bursts
VOLUME_WRITE_MS = 600          # the slider fires on every drag frame


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")


class PlayerService(QObject):
    episodeChanged = Signal(object)          # Episode | None
    playbackStateChanged = Signal(object)    # QMediaPlayer.PlaybackState
    positionChanged = Signal(int, int)       # position_secs, total_secs
    episodeFinished = Signal(int)            # episode_id
    seeked = Signal(int)                     # new position secs (for MPRIS Seeked)
    rateChanged = Signal(float)
    volumeChanged = Signal(float)            # linear 0..1 (app stream volume)
    deviceChanged = Signal(str)              # human-readable description
    devicesListChanged = Signal()
    errorOccurred = Signal(str)

    def __init__(self, client, parent: QObject | None = None):
        super().__init__(parent)
        self.client = client
        self.player = QMediaPlayer(self)
        self.audio = QAudioOutput(self)
        self.player.setAudioOutput(self.audio)
        # App-level volume (independent of the system/sink volume): this is
        # the app's own PipeWire stream volume.
        self.audio.setVolume(float(repo.get_state("volume")))
        # (signal-to-signal chaining fails on this Qt signal; .emit works and
        # QAudioOutput.volumeChanged is always emitted on the main thread)
        self.audio.volumeChanged.connect(self.volumeChanged.emit)

        self.episode: Episode | None = None
        self._pending_seek_ms: int | None = None
        self._outro_fired = False

        self.player.positionChanged.connect(self._on_position)
        self.player.durationChanged.connect(self._on_duration)
        self.player.mediaStatusChanged.connect(self._on_media_status)
        self.player.playbackStateChanged.connect(self._on_state)
        self.player.errorOccurred.connect(self._on_error)

        self._write_timer = QTimer(self)
        self._write_timer.setInterval(POSITION_WRITE_MS)
        self._write_timer.timeout.connect(self._persist_position)

        self._action_timer = QTimer(self)
        self._action_timer.setInterval(PLAY_ACTION_MS)
        self._action_timer.timeout.connect(lambda: self._emit_play_action())

        self._volume_write = QTimer(self)
        self._volume_write.setSingleShot(True)
        self._volume_write.setInterval(VOLUME_WRITE_MS)
        self._volume_write.timeout.connect(self._persist_volume)

        # ---- audio devices
        self._media_devices = QMediaDevices(self)
        self._device_debounce = QTimer(self)
        self._device_debounce.setSingleShot(True)
        self._device_debounce.setInterval(DEVICE_DEBOUNCE_MS)
        self._device_debounce.timeout.connect(self._on_devices_changed)
        self._media_devices.audioOutputsChanged.connect(self._device_debounce.start)
        self._apply_device_preference(initial=True)

    # ------------------------------------------------------------ playback

    def play_episode(self, episode_id: int) -> None:
        ep = repo.episode_by_id(episode_id)
        if ep is None:
            return
        if self.episode and self.episode.id == ep.id:
            self.player.play()
            return
        self._persist_position()

        self.episode = ep
        self._outro_fired = False

        # Source: local file when downloaded, else stream.
        if ep.download_state == "done" and ep.downloaded_path and Path(ep.downloaded_path).exists():
            url = QUrl.fromLocalFile(ep.downloaded_path)
        else:
            url = QUrl(ep.media_url)

        # Effective start: saved position vs per-podcast skip-intro.
        settings = repo.podcast_settings(ep.podcast_id)
        skip_intro = settings.get("skip_intro_secs") or 0
        start_secs = max(ep.position_secs, skip_intro)
        self._pending_seek_ms = start_secs * 1000 if start_secs > 0 else None

        self.player.setSource(url)
        self.player.setPlaybackRate(repo.effective_speed(ep.podcast_id))
        self.rateChanged.emit(self.player.playbackRate())
        self.player.play()
        self.episodeChanged.emit(ep)
        log.info("playing %s (%s)", ep.title, url.toString()[:80])

    def resume(self) -> None:
        if self.episode is not None:
            self.player.play()

    def toggle_play_pause(self) -> None:
        if self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState:
            self.pause()
        elif self.episode is not None:
            self.player.play()

    def pause(self) -> None:
        self.player.pause()
        self._persist_position()
        self._emit_play_action()

    def stop(self) -> None:
        self._persist_position()
        self._emit_play_action()
        self.player.stop()

    def seek_relative(self, secs: int) -> None:
        if self.episode is None:
            return
        self.player.setPosition(max(0, self.player.position() + secs * 1000))
        self._persist_position()
        self._emit_play_action()
        self.seeked.emit(self.player.position() // 1000)

    def seek_to(self, secs: int) -> None:
        if self.episode is None:
            return
        self.player.setPosition(max(0, secs * 1000))
        self._persist_position()
        self._emit_play_action()
        self.seeked.emit(self.player.position() // 1000)

    # MPRIS adapters (µs units, per spec)
    def seek_back_default(self) -> None:
        self.seek_relative(-int(repo.get_state("skip_back_secs")))

    def seek_forward_default(self) -> None:
        self.seek_relative(int(repo.get_state("skip_fwd_secs")))

    def seek_by_us(self, offset_us: int) -> None:
        self.seek_relative(int(offset_us) // 1_000_000)

    def set_position_us(self, position_us: int) -> None:
        self.seek_to(int(position_us) // 1_000_000)

    def set_rate(self, rate: float) -> None:
        self.player.setPlaybackRate(rate)
        self.rateChanged.emit(rate)

    # ------------------------------------------------------------ volume

    def volume(self) -> float:
        """Linear 0..1."""
        return self.audio.volume()

    def set_volume(self, linear: float) -> None:
        """User-facing volume change: apply and persist."""
        linear = max(0.0, min(1.0, float(linear)))
        self.audio.setVolume(linear)
        # Debounced: the slider fires on every drag frame, and with a daemon
        # each write is an IPC call.
        self._volume_write.start()

    @staticmethod
    def slider_to_volume(pos: float) -> float:
        """Perceptual (slider) position 0..1 → linear volume."""
        return QAudio.convertVolume(
            pos, QAudio.VolumeScale.LogarithmicVolumeScale,
            QAudio.VolumeScale.LinearVolumeScale,
        )

    @staticmethod
    def volume_to_slider(linear: float) -> float:
        return QAudio.convertVolume(
            linear, QAudio.VolumeScale.LinearVolumeScale,
            QAudio.VolumeScale.LogarithmicVolumeScale,
        )

    def _persist_volume(self) -> None:
        self.client.set_state("volume", self.audio.volume())

    def shutdown(self) -> None:
        """App quit: flush position and volume before the window goes."""
        if self._volume_write.isActive():
            self._volume_write.stop()
            self._persist_volume()
        if self.episode is not None:
            self._report(final=True)

    # ------------------------------------------------------------ devices

    def output_devices(self) -> list:
        return QMediaDevices.audioOutputs()

    def current_device_description(self) -> str:
        return self.audio.device().description()

    def use_system_default(self) -> None:
        self.client.set_state("audio_device_mode", "follow_default")
        self._apply_device_preference()

    def pin_device(self, device) -> None:
        self.client.set_state("audio_device_mode", "pinned")
        self.client.set_state("audio_device_id", bytes(device.id()).hex())
        self.client.set_state("audio_device_description", device.description())
        self._apply_device_preference()

    def _apply_device_preference(self, initial: bool = False) -> None:
        mode = repo.get_state("audio_device_mode")
        if mode == "pinned":
            dev = self._resolve_pinned()
            if dev is None:
                if not initial:
                    self.errorOccurred.emit(
                        "Pinned audio device unavailable — using system default"
                    )
                dev = QMediaDevices.defaultAudioOutput()
        else:
            # Explicitly follow the default; don't trust backend auto-follow.
            dev = QMediaDevices.defaultAudioOutput()
        if not dev.isNull() and dev.id() != self.audio.device().id():
            self.audio.setDevice(dev)
            self.deviceChanged.emit(dev.description())
            log.info("audio output -> %s", dev.description())

    def _resolve_pinned(self):
        want_id = repo.get_state("audio_device_id")
        want_desc = repo.get_state("audio_device_description")
        devices = QMediaDevices.audioOutputs()
        if want_id:
            for d in devices:
                if bytes(d.id()).hex() == want_id:
                    return d
        # BT devices re-enumerate with new ids under PipeWire — fall back to description.
        if want_desc:
            for d in devices:
                if d.description() == want_desc:
                    return d
        return None

    def _on_devices_changed(self) -> None:
        self.devicesListChanged.emit()
        self._apply_device_preference()

    # ------------------------------------------------------------ internals

    def _on_position(self, ms: int) -> None:
        if self.episode is None:
            return
        secs = ms // 1000
        total = self._total_secs()
        self.positionChanged.emit(secs, total)

        # Skip outro: treat (total - skip_outro) as the end.
        settings = repo.podcast_settings(self.episode.podcast_id)
        skip_outro = settings.get("skip_outro_secs") or 0
        if skip_outro and total > 0 and not self._outro_fired and secs >= total - skip_outro:
            self._outro_fired = True
            self._finish_episode()

    def _on_duration(self, ms: int) -> None:
        if self.episode is not None and ms > 0:
            self.episode.total_secs = ms // 1000
            self._report(final=False)

    def _on_media_status(self, status) -> None:
        if status == QMediaPlayer.MediaStatus.LoadedMedia and self._pending_seek_ms:
            self.player.setPosition(self._pending_seek_ms)
            self._pending_seek_ms = None
        elif status == QMediaPlayer.MediaStatus.EndOfMedia and self.episode is not None:
            if not self._outro_fired:
                self._finish_episode()

    def _on_state(self, state) -> None:
        playing = state == QMediaPlayer.PlaybackState.PlayingState
        self._write_timer.start() if playing else self._write_timer.stop()
        self._action_timer.start() if playing else self._action_timer.stop()
        self.playbackStateChanged.emit(state)

    def _on_error(self, error, message: str) -> None:
        log.error("player error %s: %s", error, message)
        self.errorOccurred.emit(message or "Playback error")

    def _total_secs(self) -> int:
        if self.player.duration() > 0:
            return self.player.duration() // 1000
        if self.episode is not None:
            return self.episode.total_secs or self.episode.duration_secs or 0
        return 0

    def _finish_episode(self) -> None:
        ep = self.episode
        if ep is None:
            return
        total = self._total_secs()
        # Played state and the queue removal are the daemon's to apply — the
        # window only reports where playback got to.
        self.client.report_position(ep.id, total, total, final=True)
        self.player.stop()
        self.episode = None
        self.episodeChanged.emit(None)
        self.episodeFinished.emit(ep.id)

    def _report(self, final: bool) -> None:
        """Tell whoever owns the data where playback is.

        This replaced two separate write paths: a position write every 5s and a
        gpodder action on a 60s timer plus pause/seek/stop. The split survives
        as the `final` flag — the receiver persists every report and only
        enqueues an action, and nudges LAN peers, on a final one.
        """
        ep = self.episode
        if ep is None:
            return
        secs = self.player.position() // 1000
        total = self._total_secs() or ep.total_secs
        if secs > 0:
            self.episode.position_secs = secs
        self.client.report_position(ep.id, secs, total, final)

    def _persist_position(self) -> None:
        self._report(final=False)

    def _emit_play_action(self, position: int | None = None, total: int | None = None) -> None:
        self._report(final=True)
