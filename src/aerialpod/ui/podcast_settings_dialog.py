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

        # One control for two columns: where new episodes go is only meaningful
        # if they are auto-added at all, and splitting them into two combos
        # invites setting a position that silently never applies.
        self.auto_add = QComboBox()
        self.auto_add.addItems(
            ["Global default", "Add to top of queue", "Add to bottom of queue", "Never"]
        )
        self.auto_add.setToolTip(
            "Top of queue suits a daily show — each new episode lands directly "
            "under whatever is playing, ahead of the rest of the queue."
        )
        val = s.get("auto_add_to_queue")
        if val is None:
            index = 0
        elif not val:
            index = 3
        else:
            index = 1 if s.get("auto_queue_position") == "front" else 2
        self.auto_add.setCurrentIndex(index)
        form.addRow("New episodes", self.auto_add)

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
        auto, position = {
            0: (None, None),      # inherit the global default
            1: (1, "front"),
            2: (1, "back"),
            3: (0, None),
        }[self.auto_add.currentIndex()]
        repo.set_podcast_setting(pid, "auto_add_to_queue", auto)
        repo.set_podcast_setting(pid, "auto_queue_position", position)
        self.accept()
