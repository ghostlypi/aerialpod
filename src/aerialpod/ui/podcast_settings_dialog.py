"""Per-podcast settings: rename, speed, skip intro/outro, auto-add-to-queue."""

from __future__ import annotations

from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QDoubleSpinBox,
    QFormLayout,
    QLineEdit,
    QSpinBox,
)

from ..db import repo


class PodcastSettingsDialog(QDialog):
    def __init__(self, podcast_id: int, parent=None):
        super().__init__(parent)
        self.podcast_id = podcast_id
        p = repo.podcast_by_id(podcast_id)
        s = repo.podcast_settings(podcast_id)
        self.setWindowTitle(f"Settings — {repo.display_title(p)}")
        self.setMinimumWidth(420)

        form = QFormLayout(self)

        self.custom_title = QLineEdit(s.get("custom_title") or "")
        self.custom_title.setPlaceholderText(p.title or "")
        form.addRow("Custom name", self.custom_title)

        self.speed = QDoubleSpinBox()
        self.speed.setRange(0.0, 3.0)
        self.speed.setSingleStep(0.05)
        self.speed.setDecimals(2)
        self.speed.setSpecialValueText("Global default")
        self.speed.setValue(float(s.get("playback_speed") or 0.0))
        form.addRow("Playback speed", self.speed)

        self.skip_intro = QSpinBox()
        self.skip_intro.setRange(0, 600)
        self.skip_intro.setSuffix(" s")
        self.skip_intro.setValue(int(s.get("skip_intro_secs") or 0))
        form.addRow("Skip intro", self.skip_intro)

        self.skip_outro = QSpinBox()
        self.skip_outro.setRange(0, 600)
        self.skip_outro.setSuffix(" s")
        self.skip_outro.setValue(int(s.get("skip_outro_secs") or 0))
        form.addRow("Skip outro", self.skip_outro)

        self.auto_add = QComboBox()
        self.auto_add.addItems(["Global default", "Always", "Never"])
        val = s.get("auto_add_to_queue")
        self.auto_add.setCurrentIndex(0 if val is None else (1 if val else 2))
        form.addRow("Auto-add new episodes to queue", self.auto_add)

        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self._save)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)

    def _save(self) -> None:
        pid = self.podcast_id
        repo.set_podcast_setting(pid, "custom_title", self.custom_title.text().strip() or None)
        repo.set_podcast_setting(
            pid, "playback_speed", self.speed.value() if self.speed.value() > 0 else None
        )
        repo.set_podcast_setting(pid, "skip_intro_secs", self.skip_intro.value() or None)
        repo.set_podcast_setting(pid, "skip_outro_secs", self.skip_outro.value() or None)
        auto = {0: None, 1: 1, 2: 0}[self.auto_add.currentIndex()]
        repo.set_podcast_setting(pid, "auto_add_to_queue", auto)
        self.accept()
