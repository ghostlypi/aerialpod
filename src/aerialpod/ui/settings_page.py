"""Settings page: gpodder.net account, sync, playback and appearance prefs."""

from __future__ import annotations

import logging

from PySide6.QtCore import Signal
from PySide6.QtWidgets import (
    QComboBox,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from ..db import repo
from ..gpodder import credentials

log = logging.getLogger(__name__)


class SettingsPage(QWidget):
    syncRequested = Signal()
    accountChanged = Signal()
    themeChanged = Signal()
    opmlImported = Signal(list)  # new podcast ids

    def __init__(self, parent=None):
        super().__init__(parent)
        outer = QVBoxLayout(self)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        inner = QWidget()
        lay = QVBoxLayout(inner)

        title = QLabel("Settings")
        title.setObjectName("PageTitle")
        lay.addWidget(title)

        # ---- gpodder account
        acct = QGroupBox("gpodder.net account")
        form = QFormLayout(acct)
        self.username = QLineEdit()
        self.password = QLineEdit()
        self.password.setEchoMode(QLineEdit.EchoMode.Password)
        creds = credentials.load()
        if creds:
            self.username.setText(creds[0])
            self.password.setText(creds[1])
        form.addRow("Username", self.username)
        form.addRow("Password", self.password)

        btns = QHBoxLayout()
        save_btn = QPushButton("Save && sync")
        save_btn.setObjectName("PrimaryButton")
        save_btn.clicked.connect(self._on_save)
        btns.addWidget(save_btn)
        sync_btn = QPushButton("Sync now")
        sync_btn.clicked.connect(self.syncRequested)
        btns.addWidget(sync_btn)
        forget = QPushButton("Forget account")
        forget.clicked.connect(self._on_forget)
        btns.addWidget(forget)
        btns.addStretch(1)
        form.addRow(btns)

        self.sync_status = QLabel("")
        self.sync_status.setObjectName("SyncStatus")
        self.sync_status.setWordWrap(True)
        form.addRow(self.sync_status)
        lay.addWidget(acct)

        # ---- playback
        playback = QGroupBox("Playback")
        pform = QFormLayout(playback)
        self.skip_fwd = QSpinBox()
        self.skip_fwd.setRange(5, 300)
        self.skip_fwd.setSuffix(" s")
        self.skip_fwd.setValue(int(repo.get_state("skip_fwd_secs")))
        self.skip_fwd.valueChanged.connect(
            lambda v: repo.set_state("skip_fwd_secs", int(v)))
        pform.addRow("Skip forward", self.skip_fwd)
        self.skip_back = QSpinBox()
        self.skip_back.setRange(5, 300)
        self.skip_back.setSuffix(" s")
        self.skip_back.setValue(int(repo.get_state("skip_back_secs")))
        self.skip_back.valueChanged.connect(
            lambda v: repo.set_state("skip_back_secs", int(v)))
        pform.addRow("Skip back", self.skip_back)
        self.media_next = QComboBox()
        self.media_next.addItem("Skip forward/back (ad skip)", "seek")
        self.media_next.addItem("Next episode in queue", "episode")
        current_next = repo.get_state("media_next_action")
        self.media_next.setCurrentIndex(0 if current_next == "seek" else 1)
        self.media_next.currentIndexChanged.connect(
            lambda i: repo.set_state("media_next_action", self.media_next.itemData(i)))
        pform.addRow("Keyboard ⏭ / ⏮ buttons", self.media_next)

        self.download_n = QSpinBox()
        self.download_n.setRange(0, 10)
        self.download_n.setValue(int(repo.get_state("download_ahead_n")))
        self.download_n.valueChanged.connect(
            lambda v: repo.set_state("download_ahead_n", int(v)))
        pform.addRow("Download next N queue items", self.download_n)
        lay.addWidget(playback)

        # ---- appearance
        appearance = QGroupBox("Appearance")
        aform = QFormLayout(appearance)
        self.theme_mode = QComboBox()
        self.theme_mode.addItems(["Follow system", "Light", "Dark"])
        mode = repo.get_state("theme_mode")
        self.theme_mode.setCurrentIndex({"system": 0, "light": 1, "dark": 2}.get(mode, 0))
        self.theme_mode.currentIndexChanged.connect(self._on_theme_mode)
        aform.addRow("Theme", self.theme_mode)

        self.accent = QComboBox()
        self._accents = [
            ("GNOME Blue", "#3584e4"), ("Green", "#2ec27e"), ("Orange", "#e66100"),
            ("Red", "#c01c28"), ("Purple", "#813d9c"), ("Brown", "#986a44"),
            ("Teal", "#218787"), ("Pink", "#d56199"),
        ]
        for name, color in self._accents:
            self.accent.addItem(name, color)
        current = repo.get_state("accent")
        idx = next((i for i, (_, c) in enumerate(self._accents) if c == current), 0)
        self.accent.setCurrentIndex(idx)
        self.accent.currentIndexChanged.connect(self._on_accent)
        aform.addRow("Accent color", self.accent)
        lay.addWidget(appearance)

        # ---- data
        data = QGroupBox("Data")
        dform = QFormLayout(data)
        opml_row = QHBoxLayout()
        imp = QPushButton("Import OPML…")
        imp.clicked.connect(self._import_opml)
        opml_row.addWidget(imp)
        exp = QPushButton("Export OPML…")
        exp.clicked.connect(self._export_opml)
        opml_row.addWidget(exp)
        opml_row.addStretch(1)
        dform.addRow(opml_row)

        self.unmatched_label = QLabel()
        unmatched_row = QHBoxLayout()
        unmatched_row.addWidget(self.unmatched_label)
        inspect = QPushButton("Inspect…")
        inspect.clicked.connect(self._inspect_unmatched)
        unmatched_row.addWidget(inspect)
        unmatched_row.addStretch(1)
        dform.addRow("Unmatched sync actions", unmatched_row)
        lay.addWidget(data)

        lay.addStretch(1)
        scroll.setWidget(inner)
        outer.addWidget(scroll)
        self.refresh_unmatched()

    # ------------------------------------------------------------ handlers

    def _on_save(self) -> None:
        user = self.username.text().strip()
        pw = self.password.text()
        if not user or not pw:
            self.sync_status.setText("Enter username and password first.")
            return
        credentials.save(user, pw)
        self.sync_status.setText("Saved. Starting sync…")
        self.accountChanged.emit()
        self.syncRequested.emit()

    def _on_forget(self) -> None:
        credentials.clear()
        self.username.clear()
        self.password.clear()
        repo.set_state("device_registered", False)
        repo.set_state("subs_since", 0)
        repo.set_state("actions_since", 0)
        self.sync_status.setText("Account removed.")
        self.accountChanged.emit()

    def _on_theme_mode(self, idx: int) -> None:
        repo.set_state("theme_mode", ["system", "light", "dark"][idx])
        self.themeChanged.emit()

    def _on_accent(self, idx: int) -> None:
        repo.set_state("accent", self.accent.itemData(idx))
        self.themeChanged.emit()

    def show_sync_status(self, message: str) -> None:
        self.sync_status.setText(message)
        self.refresh_unmatched()

    # ------------------------------------------------------------ data tools

    def refresh_unmatched(self) -> None:
        n = repo.unmatched_count()
        self.unmatched_label.setText(str(n))

    def _import_opml(self) -> None:
        from PySide6.QtWidgets import QFileDialog

        from ..feeds import opml

        path, _ = QFileDialog.getOpenFileName(self, "Import OPML", "",
                                              "OPML files (*.opml *.xml);;All files (*)")
        if path:
            added = opml.import_opml(path)
            self.sync_status.setText(f"Imported {len(added)} podcast(s) from OPML.")
            self.opmlImported.emit(added)

    def _export_opml(self) -> None:
        from PySide6.QtWidgets import QFileDialog

        from ..feeds import opml

        path, _ = QFileDialog.getSaveFileName(self, "Export OPML", "aerialpod.opml",
                                              "OPML files (*.opml)")
        if path:
            n = opml.export_opml(path)
            self.sync_status.setText(f"Exported {n} podcast(s) to {path}.")

    def _inspect_unmatched(self) -> None:
        from PySide6.QtWidgets import QDialog, QPlainTextEdit, QVBoxLayout

        from .. import db

        rows = db.connection().execute(
            "SELECT timestamp, action, podcast_url, episode_url FROM unmatched_actions "
            "ORDER BY received_at DESC LIMIT 200"
        ).fetchall()
        dlg = QDialog(self)
        dlg.setWindowTitle("Unmatched sync actions")
        dlg.resize(800, 400)
        lay = QVBoxLayout(dlg)
        text = QPlainTextEdit()
        text.setReadOnly(True)
        text.setPlainText(
            "\n".join(f"{r['timestamp']}  {r['action']:8s}  {r['podcast_url']}\n"
                      f"{'':28s}{r['episode_url']}" for r in rows)
            or "No unmatched actions — everything synced cleanly."
        )
        lay.addWidget(text)
        dlg.exec()
