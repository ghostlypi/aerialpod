"""Settings page: gpodder.net account, sync, playback and appearance prefs."""

from __future__ import annotations

import logging

from PySide6.QtCore import QRectF, QSignalBlocker, QSize, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtWidgets import (
    QAbstractButton,
    QButtonGroup,
    QCheckBox,
    QComboBox,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from ..ads import detector, spans
from ..core import downloads
from ..db import repo
from ..gpodder import credentials
from ..lan import discovery, pairing

log = logging.getLogger(__name__)

THEME_MODES = [("system", "System"), ("light", "Light"), ("dark", "Dark")]
ACCENTS = [
    ("GNOME Blue", "#3584e4"), ("Green", "#2ec27e"), ("Orange", "#e66100"),
    ("Red", "#c01c28"), ("Purple", "#813d9c"), ("Brown", "#986a44"),
    ("Teal", "#218787"), ("Pink", "#d56199"),
]


class AccentSwatch(QAbstractButton):
    """A round colour button, as on the phone: a thin outline normally, a
    thick ring in the text colour when it is the chosen accent."""

    SIZE = 32

    def __init__(self, name: str, color: str, parent=None):
        super().__init__(parent)
        self.color = color
        self.setCheckable(True)
        self.setToolTip(name)
        self.setAccessibleName(f"{name} accent")
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setFixedSize(self.sizeHint())

    def sizeHint(self) -> QSize:
        return QSize(self.SIZE, self.SIZE)

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        text = self.palette().color(self.palette().ColorRole.WindowText)
        ring = 3.0 if self.isChecked() else 1.0
        outline = QColor(text)
        if not self.isChecked():
            outline.setAlpha(140 if self.underMouse() else 60)
        p.setPen(QPen(outline, ring))
        p.setBrush(QColor(self.color))
        inset = ring / 2 + 0.5
        p.drawEllipse(QRectF(self.rect()).adjusted(inset, inset, -inset, -inset))
        if self.hasFocus():
            focus = QColor(text)
            focus.setAlpha(90)
            p.setPen(QPen(focus, 1, Qt.PenStyle.DotLine))
            p.setBrush(Qt.BrushStyle.NoBrush)
            p.drawEllipse(QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5))

    def enterEvent(self, event) -> None:  # Qt override
        super().enterEvent(event)
        self.update()

    def leaveEvent(self, event) -> None:  # Qt override
        super().leaveEvent(event)
        self.update()


class SettingsPage(QWidget):
    themeChanged = Signal(dict)   # {state key: new value}
    adsSkipChanged = Signal(bool)

    def __init__(self, client, parent=None):
        super().__init__(parent)
        self.client = client
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
        sync_btn.clicked.connect(self.client.sync_now)
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

        # ---- device sync
        lay.addWidget(self._build_lan_group())

        lay.addWidget(self._build_ads_group())

        # ---- playback
        playback = QGroupBox("Playback")
        pform = QFormLayout(playback)
        self.skip_fwd = QSpinBox()
        self.skip_fwd.setRange(5, 300)
        self.skip_fwd.setSuffix(" s")
        self.skip_fwd.setValue(int(repo.get_state("skip_fwd_secs")))
        self.skip_fwd.valueChanged.connect(
            lambda v: self.client.set_state("skip_fwd_secs", int(v)))
        pform.addRow("Skip forward", self.skip_fwd)
        self.skip_back = QSpinBox()
        self.skip_back.setRange(5, 300)
        self.skip_back.setSuffix(" s")
        self.skip_back.setValue(int(repo.get_state("skip_back_secs")))
        self.skip_back.valueChanged.connect(
            lambda v: self.client.set_state("skip_back_secs", int(v)))
        pform.addRow("Skip back", self.skip_back)
        self.media_next = QComboBox()
        self.media_next.addItem("Skip forward/back (ad skip)", "seek")
        self.media_next.addItem("Next episode in queue", "episode")
        current_next = repo.get_state("media_next_action")
        self.media_next.setCurrentIndex(0 if current_next == "seek" else 1)
        self.media_next.currentIndexChanged.connect(
            lambda i: self.client.set_state("media_next_action", self.media_next.itemData(i)))
        pform.addRow("Keyboard ⏭ / ⏮ buttons", self.media_next)

        # Same choices as the phone's chips.
        current_ahead = downloads.download_ahead()
        self.download_ahead = QButtonGroup(self)
        ahead = QHBoxLayout()
        ahead.setSpacing(8)
        for i, choice in enumerate(downloads.DOWNLOAD_AHEAD_CHOICES):
            chip = QPushButton(downloads.download_ahead_label(choice))
            chip.setObjectName("Chip")
            chip.setCheckable(True)
            chip.setChecked(choice == current_ahead)
            chip.setCursor(Qt.CursorShape.PointingHandCursor)
            self.download_ahead.addButton(chip, i)
            ahead.addWidget(chip)
        ahead.addStretch(1)
        self.download_ahead.idClicked.connect(self._on_download_ahead)
        ahead_box = QVBoxLayout()
        ahead_box.setSpacing(4)
        ahead_box.addLayout(ahead)
        ahead_note = QLabel(
            "How much of the queue to keep on this device: a fixed number, or a "
            "share of however long the queue is. A share rounds up, so a non-empty "
            "queue always keeps at least one. Everything else streams.")
        ahead_note.setObjectName("SyncStatus")
        ahead_note.setWordWrap(True)
        ahead_box.addWidget(ahead_note)
        pform.addRow("Download ahead", ahead_box)
        lay.addWidget(playback)

        # ---- appearance
        appearance = QGroupBox("Appearance")
        aform = QFormLayout(appearance)
        aform.setVerticalSpacing(14)

        # Chips and swatches, as on the phone, rather than two dropdowns.
        mode = repo.get_state("theme_mode")
        self.theme_mode = QButtonGroup(self)
        chips = QHBoxLayout()
        chips.setSpacing(8)
        for i, (key, label) in enumerate(THEME_MODES):
            chip = QPushButton(label)
            chip.setObjectName("Chip")
            chip.setCheckable(True)
            chip.setChecked(key == mode or (i == 0 and mode not in dict(THEME_MODES)))
            chip.setCursor(Qt.CursorShape.PointingHandCursor)
            self.theme_mode.addButton(chip, i)
            chips.addWidget(chip)
        chips.addStretch(1)
        self.theme_mode.idClicked.connect(self._on_theme_mode)
        aform.addRow("Theme", chips)

        current = (repo.get_state("accent") or "").lower()
        self.accent = QButtonGroup(self)
        swatches = QHBoxLayout()
        swatches.setSpacing(12)
        for i, (name, color) in enumerate(ACCENTS):
            swatch = AccentSwatch(name, color)
            swatch.setChecked(color == current)
            self.accent.addButton(swatch, i)
            swatches.addWidget(swatch)
        swatches.addStretch(1)
        self.accent.idClicked.connect(self._on_accent)
        aform.addRow("Accent", swatches)
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

    # ------------------------------------------------------------ device sync

    def _build_lan_group(self) -> QGroupBox:
        box = QGroupBox("Device sync (same network)")
        form = QFormLayout(box)

        blurb = QLabel(
            "Your other AerialPod installs sync directly with this one: queue "
            "order, what you pinned or removed, per-podcast settings, and live "
            "playback position. Works over a VPN such as WireGuard as well as "
            "a home network. Pair each device once, using the code below."
        )
        blurb.setWordWrap(True)
        form.addRow(blurb)

        # ---- pairing
        code_row = QHBoxLayout()
        self.lan_code = QLineEdit()
        self.lan_code.setReadOnly(True)
        self.lan_code.setEchoMode(QLineEdit.EchoMode.Password)
        self.lan_code.setText(pairing.pairing_code())
        code_row.addWidget(self.lan_code, 1)
        self.lan_code_show = QPushButton("Show")
        self.lan_code_show.setCheckable(True)
        self.lan_code_show.toggled.connect(self._on_show_code)
        code_row.addWidget(self.lan_code_show)
        copy_btn = QPushButton("Copy")
        copy_btn.clicked.connect(self._on_copy_code)
        code_row.addWidget(copy_btn)
        new_btn = QPushButton("New code")
        new_btn.setToolTip(
            "Generate a fresh key. Your other devices stop syncing until you "
            "pair them again with the new code."
        )
        new_btn.clicked.connect(self._on_new_code)
        code_row.addWidget(new_btn)
        form.addRow("This device's code", code_row)

        pair_row = QHBoxLayout()
        self.lan_pair_code = QLineEdit()
        self.lan_pair_code.setPlaceholderText("Paste the code from your other device")
        self.lan_pair_code.returnPressed.connect(self._on_pair)
        pair_row.addWidget(self.lan_pair_code, 1)
        pair_btn = QPushButton("Pair")
        pair_btn.clicked.connect(self._on_pair)
        pair_row.addWidget(pair_btn)
        form.addRow("Pair with a device", pair_row)

        self.lan_enabled = QCheckBox("Sync with my other devices")
        self.lan_enabled.setChecked(bool(repo.get_state("lan_sync_enabled")))
        self.lan_enabled.toggled.connect(self._on_lan_enabled)
        form.addRow(self.lan_enabled)

        self.lan_port = QSpinBox()
        self.lan_port.setRange(1024, 65535)
        self.lan_port.setValue(int(repo.get_state("lan_port")))
        self.lan_port.editingFinished.connect(self._on_lan_port)
        form.addRow("Port", self.lan_port)

        self.lan_scan = QCheckBox("Look for devices on my network automatically")
        self.lan_scan.setChecked(bool(repo.get_state("lan_scan_subnets")))
        self.lan_scan.setToolTip(
            "Checks the addresses of the networks this machine is on, one port "
            "each. Turn it off to only use the devices listed below."
        )
        self.lan_scan.toggled.connect(self._on_lan_scan)
        form.addRow(self.lan_scan)

        self.lan_peers = QListWidget()
        self.lan_peers.setMaximumHeight(90)
        form.addRow("Connected", self.lan_peers)

        add_row = QHBoxLayout()
        self.lan_peer_host = QLineEdit()
        self.lan_peer_host.setPlaceholderText("Add a device by address, e.g. 10.0.0.2")
        self.lan_peer_host.returnPressed.connect(self._on_add_peer)
        add_row.addWidget(self.lan_peer_host, 1)
        add_btn = QPushButton("Add")
        add_btn.clicked.connect(self._on_add_peer)
        add_row.addWidget(add_btn)
        find_btn = QPushButton("Find devices now")
        find_btn.clicked.connect(self.client.lan_discover)
        add_row.addWidget(find_btn)
        form.addRow(add_row)

        self.lan_manual = QListWidget()
        self.lan_manual.setMaximumHeight(70)
        self.lan_manual.setToolTip("Double-click an entry to remove it.")
        self.lan_manual.itemDoubleClicked.connect(self._on_remove_manual_peer)
        form.addRow("Added by hand", self.lan_manual)

        self.lan_status = QLabel("")
        self.lan_status.setObjectName("SyncStatus")
        self.lan_status.setWordWrap(True)
        form.addRow(self.lan_status)
        self.lan_status.setText(self._reachable_hint())

        self.show_lan_peers([])
        self._reload_manual_peers()
        return box

    def _build_ads_group(self) -> QGroupBox:
        box = QGroupBox("Ad removal")
        form = QFormLayout(box)

        self.ads_skip = QCheckBox("Skip ads automatically")
        self.ads_skip.toggled.connect(self._on_ads_skip)
        form.addRow(self.ads_skip)

        if detector.toolchain_python() is None:
            note = QLabel(
                "This device skips ads that a paired device has found. To find them "
                "here as well, install the detector with <code>./install.sh ai</code> "
                "(a GPU is recommended)."
            )
            note.setWordWrap(True)
            form.addRow(note)
            self.ads_status = QLabel()
            self._refresh_ads()
            return box

        blurb = QLabel(
            "This device transcribes your queued episodes and finds the ads in them "
            "with AI models that run here, on this machine. Your paired devices, "
            "including your phone, get the ad timings and can download the exact "
            "audio files that were analysed, so the skips line up. Nothing is sent "
            "anywhere else."
        )
        blurb.setWordWrap(True)
        form.addRow(blurb)

        detect_row = QHBoxLayout()
        self.ads_detect = QCheckBox("Find ads in queued episodes")
        self.ads_detect.toggled.connect(self._on_ads_detect)
        detect_row.addWidget(self.ads_detect)
        detect_row.addStretch(1)
        self.ads_pause = QPushButton()
        self.ads_pause.setToolTip(
            "Stops the episode being analysed now; it starts over when you resume. "
            "Resuming also retries any episode that failed.")
        self.ads_pause.clicked.connect(self._on_ads_pause)
        self.ads_retry = QPushButton("Retry failed")
        self.ads_retry.setToolTip("Try the episodes that failed again now.")
        self.ads_retry.clicked.connect(self.client.ads_retry)
        self.ads_retry.hide()  # until something has failed
        detect_row.addWidget(self.ads_retry)
        detect_row.addWidget(self.ads_pause)
        form.addRow(detect_row)

        self.ads_model = QComboBox()
        for key, spec in spans.MODELS.items():
            self.ads_model.addItem(spec["label"], key)
        self.ads_model.currentIndexChanged.connect(
            lambda i: self.client.set_state("ads_model", self.ads_model.itemData(i)))
        form.addRow("Model", self.ads_model)

        self.ads_battery = QCheckBox("Also while on battery")
        self.ads_battery.toggled.connect(
            lambda on: self.client.set_state("ads_on_battery", bool(on)))
        form.addRow(self.ads_battery)

        self.ads_status = QLabel()
        self.ads_status.setObjectName("AdsStatus")
        self.ads_status.setWordWrap(True)
        # Selectable, so an error can be copied into a search or a bug report.
        self.ads_status.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        form.addRow(self.ads_status)
        self._refresh_ads()
        return box

    def _refresh_ads(self) -> None:
        """Show what is stored now. These settings change from elsewhere — the
        daemon's defaults, a command line, another window — and a page built
        once at startup would otherwise go on showing whatever was true then.
        Signals are blocked so that showing a value never writes it back."""
        with QSignalBlocker(self.ads_skip):
            self.ads_skip.setChecked(bool(repo.get_state("ads_skip_enabled")))
        if not hasattr(self, "ads_detect"):
            return  # no detector toolchain on this device
        detecting = detector.enabled()
        with QSignalBlocker(self.ads_detect):
            self.ads_detect.setChecked(detecting)
        current = repo.get_state("ads_model")
        if current not in spans.MODELS:
            current = spans.DEFAULT_MODEL
        with QSignalBlocker(self.ads_model):
            self.ads_model.setCurrentIndex(max(0, self.ads_model.findData(current)))
        with QSignalBlocker(self.ads_battery):
            self.ads_battery.setChecked(bool(repo.get_state("ads_on_battery")))
        self.ads_model.setEnabled(detecting)
        self.ads_battery.setEnabled(detecting)
        self._show_ads_paused(bool(repo.get_state("ads_paused")))
        self.ads_pause.setEnabled(detecting)

    def _on_ads_detect(self, on: bool) -> None:
        self.client.set_state("ads_detect_enabled", bool(on))
        self.ads_model.setEnabled(on)
        self.ads_battery.setEnabled(on)
        self.ads_pause.setEnabled(on)

    def _on_ads_pause(self) -> None:
        # Our own idea of the state, not the database's: the write goes to
        # the daemon asynchronously, and a quick second click must undo the first.
        paused = not self._ads_paused
        self.client.set_state("ads_paused", paused)
        self._show_ads_paused(paused)

    def _show_ads_paused(self, paused: bool) -> None:
        self._ads_paused = paused
        self.ads_pause.setText("Resume" if paused else "Pause")
        self.ads_pause.setObjectName("PrimaryButton" if paused else "")
        self.ads_pause.style().unpolish(self.ads_pause)
        self.ads_pause.style().polish(self.ads_pause)

    def showEvent(self, event) -> None:  # Qt override
        super().showEvent(event)
        if hasattr(self, "ads_skip"):
            self._refresh_ads()

    def _on_ads_skip(self, on: bool) -> None:
        self.client.set_state("ads_skip_enabled", bool(on))
        self.adsSkipChanged.emit(bool(on))

    def show_ads_status(self, message: str) -> None:
        self.ads_status.setText(message)
        if hasattr(self, "ads_retry"):
            self.ads_retry.setVisible(detector.FAILURE_MARK in message)

    def _on_show_code(self, shown: bool) -> None:
        self.lan_code.setEchoMode(
            QLineEdit.EchoMode.Normal if shown else QLineEdit.EchoMode.Password
        )
        self.lan_code_show.setText("Hide" if shown else "Show")

    def _on_copy_code(self) -> None:
        from PySide6.QtWidgets import QApplication

        QApplication.clipboard().setText(pairing.pairing_code())
        self.lan_status.setText("Pairing code copied — paste it on your other device.")

    def _on_new_code(self) -> None:
        from PySide6.QtWidgets import QMessageBox

        confirm = QMessageBox.question(
            self,
            "New pairing code",
            "Generate a new key for this device?\n\n"
            "Your other devices will stop syncing with it until you pair them "
            "again using the new code.",
        )
        if confirm != QMessageBox.StandardButton.Yes:
            return
        self.client.lan_new_code()
        self.lan_status.setText("New code generated — pair your other devices with it.")

    def _on_pair(self) -> None:
        code = self.lan_pair_code.text()
        try:
            # Checked here so a typo is reported immediately and precisely;
            # parse_code is pure, so nothing is stored until the daemon agrees.
            pairing.parse_code(code)
        except ValueError as exc:
            self.lan_status.setText(str(exc))
            return
        self.client.lan_pair(code)
        self.lan_pair_code.clear()
        self.lan_status.setText("Paired. Looking for that device…")

    def refresh_pairing_code(self) -> None:
        """The key changed underneath us — show what to type on other devices."""
        self.lan_code.setText(pairing.pairing_code())

    def _on_lan_enabled(self, on: bool) -> None:
        self.client.set_state("lan_sync_enabled", bool(on))

    def _on_lan_scan(self, on: bool) -> None:
        self.client.set_state("lan_scan_subnets", bool(on))

    def _on_lan_port(self) -> None:
        port = int(self.lan_port.value())
        if port != int(repo.get_state("lan_port")):
            self.client.set_state("lan_port", port)

    def _on_add_peer(self) -> None:
        text = self.lan_peer_host.text().strip()
        if not text:
            return
        # "host" or "host:port" — the port is optional and usually ours.
        address, _, port_text = text.rpartition(":")
        if not address:
            address, port = text, int(repo.get_state("lan_port"))
        else:
            try:
                port = int(port_text)
            except ValueError:
                address, port = text, int(repo.get_state("lan_port"))
        self.client.lan_add_peer(address, port)
        self.lan_peer_host.clear()
        self._reload_manual_peers()
        self.lan_status.setText(f"Looking for {address} on port {port}…")

    def _on_remove_manual_peer(self, item) -> None:
        address, _, port = item.text().rpartition(":")
        self.client.lan_remove_peer(address, int(port))
        self._reload_manual_peers()

    def _reload_manual_peers(self) -> None:
        self.lan_manual.clear()
        for address, port in repo.manual_peers():
            self.lan_manual.addItem(f"{address}:{port}")

    def show_lan_peers(self, peers: list) -> None:
        self.lan_peers.clear()
        if not peers:
            self.lan_peers.addItem("No devices connected")
            return
        for peer in peers:
            self.lan_peers.addItem(f"{peer['caption']} — {peer['address']}")

    def show_lan_status(self, message: str) -> None:
        self.lan_status.setText(message)

    def _reachable_hint(self) -> str:
        """What to type on the *other* machine to reach this one — the piece a
        user needs when automatic discovery can't help, which is exactly the
        WireGuard /32 case."""
        port = int(repo.get_state("lan_port"))
        addresses = sorted(
            a for a in discovery.own_addresses() if not a.startswith("127.")
        )
        if not addresses:
            return ""
        return "This device is reachable at " + ", ".join(
            f"{a}:{port}" for a in addresses
        )

    # ------------------------------------------------------------ handlers

    def _on_save(self) -> None:
        user = self.username.text().strip()
        pw = self.password.text()
        if not user or not pw:
            self.sync_status.setText("Enter username and password first.")
            return
        self.client.set_account(user, pw)
        self.sync_status.setText("Saved. Starting sync…")

    def _on_forget(self) -> None:
        self.client.forget_account()
        self.username.clear()
        self.password.clear()
        self.sync_status.setText("Account removed.")

    def _on_download_ahead(self, idx: int) -> None:
        choice = downloads.DOWNLOAD_AHEAD_CHOICES[idx]
        # The integer key too, as the phone does: anything that only knows
        # download_ahead_n still reads something close.
        self.client.set_state("download_ahead_n", int(choice) if choice.isdigit() else 1)
        self.client.set_state("download_ahead", choice)

    def _on_theme_mode(self, idx: int) -> None:
        mode = THEME_MODES[idx][0]
        self.client.set_state("theme_mode", mode)
        self.themeChanged.emit({"theme_mode": mode})

    def _on_accent(self, idx: int) -> None:
        accent = ACCENTS[idx][1]
        self.client.set_state("accent", accent)
        self.themeChanged.emit({"accent": accent})

    def show_sync_status(self, message: str) -> None:
        self.sync_status.setText(message)
        self.refresh_unmatched()

    # ------------------------------------------------------------ data tools

    def refresh_unmatched(self) -> None:
        n = repo.unmatched_count()
        self.unmatched_label.setText(str(n))

    def _import_opml(self) -> None:
        from PySide6.QtWidgets import QFileDialog

        path, _ = QFileDialog.getOpenFileName(self, "Import OPML", "",
                                              "OPML files (*.opml *.xml);;All files (*)")
        if path:
            self.client.import_opml(path)
            self.sync_status.setText("Importing podcasts from OPML…")

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
