"""Theme engine: QSS template + palette tokens, light/dark × accent,
following the GNOME (freedesktop portal) color scheme via Qt styleHints.
"""

from __future__ import annotations

import logging
from importlib import resources

from PySide6.QtCore import QObject, Qt
from PySide6.QtGui import QColor, QGuiApplication, QPalette

from ..db import repo

log = logging.getLogger(__name__)


def _mix(c1: str, c2: str, ratio: float) -> str:
    a, b = QColor(c1), QColor(c2)
    return QColor(
        round(a.red() * (1 - ratio) + b.red() * ratio),
        round(a.green() * (1 - ratio) + b.green() * ratio),
        round(a.blue() * (1 - ratio) + b.blue() * ratio),
    ).name()


def _palette(dark: bool, accent: str) -> dict[str, str]:
    if dark:
        bg, surface, text = "#1e1e1e", "#2a2a2a", "#eeeeec"
        border, dim = "#3d3d3d", "#9a9996"
        hover = "#333333"
    else:
        bg, surface, text = "#fafafa", "#f0f0ee", "#2e3436"
        border, dim = "#d5d5d3", "#77767b"
        hover = "#e6e6e4"
    accent_hover = _mix(accent, "#ffffff" if dark else "#000000", 0.15)
    return {
        "bg": bg,
        "surface": surface,
        "surface-hover": hover,
        "text": text,
        "text-dim": dim,
        "border": border,
        "accent": accent,
        "accent-hover": accent_hover,
        "on-accent": "#ffffff",
        "danger": "#e01b24" if not dark else "#ff7b63",
    }


class ThemeManager(QObject):
    def __init__(self, app, parent: QObject | None = None):
        super().__init__(parent)
        self.app = app
        hints = QGuiApplication.styleHints()
        hints.colorSchemeChanged.connect(lambda _s: self.apply())

    def _dark(self) -> bool:
        mode = repo.get_state("theme_mode")
        if mode == "light":
            return False
        if mode == "dark":
            return True
        return QGuiApplication.styleHints().colorScheme() == Qt.ColorScheme.Dark

    def apply(self) -> None:
        dark = self._dark()
        accent = repo.get_state("accent")
        tokens = _palette(dark, accent)

        tmpl = (
            resources.files("aerialpod.ui.themes")
            .joinpath("base.qss.tmpl")
            .read_text(encoding="utf-8")
        )
        qss = tmpl
        # longest keys first so '@accent-hover' isn't clobbered by '@accent'
        for key in sorted(tokens, key=len, reverse=True):
            qss = qss.replace(f"@{key}", tokens[key])
        self.app.setStyleSheet(qss)

        # QPalette for the native bits QSS doesn't reach
        pal = QPalette()
        roles = {
            QPalette.ColorRole.Window: tokens["bg"],
            QPalette.ColorRole.Base: tokens["bg"],
            QPalette.ColorRole.AlternateBase: tokens["surface"],
            QPalette.ColorRole.WindowText: tokens["text"],
            QPalette.ColorRole.Text: tokens["text"],
            QPalette.ColorRole.Button: tokens["surface"],
            QPalette.ColorRole.ButtonText: tokens["text"],
            QPalette.ColorRole.Highlight: tokens["accent"],
            QPalette.ColorRole.HighlightedText: tokens["on-accent"],
            QPalette.ColorRole.PlaceholderText: tokens["text-dim"],
            QPalette.ColorRole.ToolTipBase: tokens["surface"],
            QPalette.ColorRole.ToolTipText: tokens["text"],
        }
        for role, color in roles.items():
            pal.setColor(role, QColor(color))
        self.app.setPalette(pal)
        log.debug("theme applied: dark=%s accent=%s", dark, accent)
