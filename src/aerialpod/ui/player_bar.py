"""Bottom player bar: cover, title, transport, seek slider, speed, device menu."""

from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QAction, QActionGroup, QPixmap
from PySide6.QtMultimedia import QMediaPlayer
from PySide6.QtWidgets import (
    QHBoxLayout,
    QLabel,
    QMenu,
    QPushButton,
    QSlider,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

from ..core.player import PlayerService
from ..db import repo
from ..db.models import Episode
from . import images
from .episode_list import fmt_duration

COVER = 48


def _label_button(btn: QToolButton, icon_names: list[str], text: str) -> None:
    """Give a tool button a visible text label, plus a themed icon when the
    desktop icon theme provides one. Never rely on emoji glyphs — they don't
    render in Qt widget fonts on many setups."""
    from PySide6.QtGui import QIcon

    btn.setText(text)
    for name in icon_names:
        icon = QIcon.fromTheme(name)
        if not icon.isNull():
            btn.setIcon(icon)
            btn.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextBesideIcon)
            return
    btn.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextOnly)


class PlayerBar(QWidget):
    def __init__(self, player: PlayerService, parent=None):
        super().__init__(parent)
        self.player = player
        self.setObjectName("PlayerBar")
        self.setFixedHeight(84)
        self._dragging = False
        self._cover_url: str | None = None

        lay = QHBoxLayout(self)
        lay.setContentsMargins(12, 8, 12, 8)

        self.cover = QLabel()
        self.cover.setFixedSize(COVER, COVER)
        self.cover.setScaledContents(True)
        lay.addWidget(self.cover)

        mid = QVBoxLayout()
        mid.setSpacing(2)
        self.title = QLabel("Nothing playing")
        self.title.setObjectName("PlayerTitle")
        mid.addWidget(self.title)

        seek_row = QHBoxLayout()
        self.pos_label = QLabel("0:00")
        self.pos_label.setObjectName("PlayerTime")
        seek_row.addWidget(self.pos_label)
        self.slider = QSlider(Qt.Orientation.Horizontal)
        self.slider.setObjectName("SeekSlider")
        self.slider.sliderPressed.connect(lambda: setattr(self, "_dragging", True))
        self.slider.sliderReleased.connect(self._on_slider_released)
        seek_row.addWidget(self.slider, 1)
        self.total_label = QLabel("0:00")
        self.total_label.setObjectName("PlayerTime")
        seek_row.addWidget(self.total_label)
        mid.addLayout(seek_row)
        lay.addLayout(mid, 1)

        self.back_btn = QPushButton()
        self.back_btn.setObjectName("SkipBackButton")
        self.back_btn.setFixedSize(40, 40)
        self.back_btn.clicked.connect(
            lambda: self.player.seek_relative(-int(repo.get_state("skip_back_secs")))
        )
        lay.addWidget(self.back_btn)

        from PySide6.QtGui import QIcon

        self._play_icon = QIcon.fromTheme("media-playback-start-symbolic",
                                          QIcon.fromTheme("media-playback-start"))
        self._pause_icon = QIcon.fromTheme("media-playback-pause-symbolic",
                                           QIcon.fromTheme("media-playback-pause"))
        self.play_btn = QPushButton()
        self.play_btn.setObjectName("PlayPauseButton")
        self.play_btn.setFixedSize(48, 48)
        self.play_btn.setToolTip("Play/Pause (Space)")
        self._set_play_visual(playing=False)
        self.play_btn.clicked.connect(self.player.toggle_play_pause)
        lay.addWidget(self.play_btn)

        self.fwd_btn = QPushButton()
        self.fwd_btn.setObjectName("SkipFwdButton")
        self.fwd_btn.setFixedSize(40, 40)
        self.fwd_btn.clicked.connect(
            lambda: self.player.seek_relative(int(repo.get_state("skip_fwd_secs")))
        )
        lay.addWidget(self.fwd_btn)
        self._update_skip_labels()

        self.speed_btn = QToolButton()
        self.speed_btn.setObjectName("SpeedButton")
        self.speed_btn.setText("1.0×")
        self.speed_btn.setToolTip("Playback speed")
        self.speed_btn.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        self._rebuild_speed_menu()
        lay.addWidget(self.speed_btn)

        # App-level volume (the app's own audio stream, independent of the
        # system volume): logarithmic taper via PlayerService converters.
        vol_icon = QLabel("Vol")
        vol_icon.setObjectName("VolumeIcon")
        vol_icon.setToolTip("App volume — independent of system volume")
        lay.addWidget(vol_icon)
        self.volume_slider = QSlider(Qt.Orientation.Horizontal)
        self.volume_slider.setObjectName("VolumeSlider")
        self.volume_slider.setRange(0, 100)
        self.volume_slider.setFixedWidth(110)
        self.volume_slider.setValue(
            round(self.player.volume_to_slider(self.player.volume()) * 100)
        )
        self._show_volume_tooltip()
        self.volume_slider.valueChanged.connect(self._on_volume_slider)
        lay.addWidget(self.volume_slider)

        self.sleep_btn = QToolButton()
        self.sleep_btn.setObjectName("SleepButton")
        _label_button(self.sleep_btn, ["night-light-symbolic", "alarm-symbolic"], "Sleep")
        self.sleep_btn.setToolTip("Sleep timer")
        self.sleep_btn.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        lay.addWidget(self.sleep_btn)

        self.device_btn = QToolButton()
        self.device_btn.setObjectName("DeviceButton")
        _label_button(self.device_btn,
                      ["audio-speakers-symbolic", "audio-volume-high-symbolic"], "Output")
        self.device_btn.setToolTip("Audio output device")
        self.device_btn.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        self._rebuild_device_menu()
        lay.addWidget(self.device_btn)

        # player signals
        player.episodeChanged.connect(self._on_episode)
        player.playbackStateChanged.connect(self._on_state)
        player.positionChanged.connect(self._on_position)
        player.rateChanged.connect(lambda r: self.speed_btn.setText(f"{r:g}×"))
        player.devicesListChanged.connect(self._rebuild_device_menu)
        player.volumeChanged.connect(self._on_player_volume)
        player.deviceChanged.connect(lambda d: self.device_btn.setToolTip(f"Audio output: {d}"))
        images.loader().loaded.connect(self._on_cover_loaded)

    def _on_volume_slider(self, value: int) -> None:
        self.player.set_volume(self.player.slider_to_volume(value / 100))
        self._show_volume_tooltip()

    def _on_player_volume(self, linear: float) -> None:
        """Volume changed elsewhere (sleep-timer fade, MPRIS) — follow it."""
        pos = round(self.player.volume_to_slider(linear) * 100)
        if pos != self.volume_slider.value():
            self.volume_slider.blockSignals(True)
            self.volume_slider.setValue(pos)
            self.volume_slider.blockSignals(False)
            self._show_volume_tooltip()

    def _show_volume_tooltip(self) -> None:
        self.volume_slider.setToolTip(f"App volume: {self.volume_slider.value()}%")

    def attach_sleep_timer(self, timer) -> None:
        """Wire the sleep-timer menu (called by MainWindow after construction)."""
        menu = QMenu(self)
        for mins in (10, 20, 30, 45, 60):
            menu.addAction(f"{mins} minutes",
                           lambda _=False, m=mins: timer.start_minutes(m))
        menu.addAction("End of episode", timer.start_end_of_episode)
        menu.addSeparator()
        menu.addAction("Extend +10 min", timer.extend)
        menu.addAction("Cancel", timer.cancel)
        self.sleep_btn.setMenu(menu)
        timer.stateChanged.connect(self._on_sleep_state)

    def _on_sleep_state(self, label: str) -> None:
        # label is e.g. "Sleep: 12:34" while running, "" when off
        self.sleep_btn.setText(label.replace("Sleep: ", "Sleep ") if label else "Sleep")
        self.sleep_btn.setToolTip(label or "Sleep timer")

    def _update_skip_labels(self) -> None:
        back, fwd = repo.get_state("skip_back_secs"), repo.get_state("skip_fwd_secs")
        self.back_btn.setText(f"↺{back}")
        self.back_btn.setToolTip(f"Skip back {back}s (←)")
        self.fwd_btn.setText(f"{fwd}↻")
        self.fwd_btn.setToolTip(f"Skip forward {fwd}s (→)")

    # ------------------------------------------------------------ menus

    def _rebuild_speed_menu(self) -> None:
        menu = QMenu(self)
        for preset in repo.get_state("speed_presets"):
            act = QAction(f"{preset:g}×", menu)
            act.triggered.connect(lambda _=False, r=preset: self.player.set_rate(r))
            menu.addAction(act)
        self.speed_btn.setMenu(menu)

    def _rebuild_device_menu(self) -> None:
        menu = QMenu(self)
        group = QActionGroup(menu)
        group.setExclusive(True)

        mode = repo.get_state("audio_device_mode")
        follow = QAction("Follow system default", menu)
        follow.setCheckable(True)
        follow.setChecked(mode == "follow_default")
        follow.triggered.connect(self.player.use_system_default)
        group.addAction(follow)
        menu.addAction(follow)
        menu.addSeparator()

        pinned_id = repo.get_state("audio_device_id")
        for dev in self.player.output_devices():
            act = QAction(dev.description(), menu)
            act.setCheckable(True)
            act.setChecked(mode == "pinned" and bytes(dev.id()).hex() == pinned_id)
            act.triggered.connect(lambda _=False, d=dev: self.player.pin_device(d))
            group.addAction(act)
            menu.addAction(act)
        self.device_btn.setMenu(menu)

    # ------------------------------------------------------------ signals

    def _on_episode(self, ep: Episode | None) -> None:
        if ep is None:
            self.title.setText("Nothing playing")
            self.cover.setPixmap(QPixmap())
            self.slider.setValue(0)
            return
        p = repo.podcast_by_id(ep.podcast_id)
        pod_title = repo.display_title(p) if p else ""
        self.title.setText(f"{ep.title}  —  {pod_title}" if pod_title else ep.title or "")
        self._cover_url = ep.image_url or (p.image_url if p else None)
        pm = images.loader().get(self._cover_url, COVER)
        self.cover.setPixmap(pm if pm is not None else QPixmap())
        self._update_skip_labels()

    def _on_cover_loaded(self, url: str, pm: QPixmap) -> None:
        if url == self._cover_url:
            self.cover.setPixmap(pm)

    def _set_play_visual(self, playing: bool) -> None:
        icon = self._pause_icon if playing else self._play_icon
        if not icon.isNull():
            self.play_btn.setIcon(icon)
            self.play_btn.setText("")
        else:  # glyphs known to exist in DejaVu Sans (no emoji dependency)
            from PySide6.QtGui import QIcon

            self.play_btn.setIcon(QIcon())  # clear
            self.play_btn.setText("‖" if playing else "▶")

    def _on_state(self, state) -> None:
        playing = state == QMediaPlayer.PlaybackState.PlayingState
        self._set_play_visual(playing)

    def _on_position(self, secs: int, total: int) -> None:
        self.pos_label.setText(fmt_duration(secs) or "0:00")
        self.total_label.setText(fmt_duration(total) or "—")
        if not self._dragging and total > 0:
            self.slider.setMaximum(total)
            self.slider.setValue(secs)

    def _on_slider_released(self) -> None:
        self._dragging = False
        self.player.seek_to(self.slider.value())
