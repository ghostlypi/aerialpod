"""Full description / show notes for a podcast or an episode, links clickable."""

from __future__ import annotations

import html
import re

from PySide6.QtWidgets import (
    QDialog,
    QDialogButtonBox,
    QLabel,
    QTextBrowser,
    QVBoxLayout,
)

_HTML_TAG = re.compile(r"<[a-zA-Z/][^>]*>")
# Trailing punctuation is left off so "see https://example.com." links the
# site, not the full stop.
_BARE_URL = re.compile(r"""https?://[^\s<>"]+[^\s<>".,;:!?)\]'”’]""")


def notes_html(text: str | None) -> str:
    """Feed text as HTML QTextBrowser can render.

    Plenty of feeds send plain text with newlines and bare URLs rather than
    HTML. That keeps its line breaks and gets its URLs turned into links;
    real HTML is passed through as-is.
    """
    text = (text or "").strip()
    if not text or _HTML_TAG.search(text):
        return text
    linked = _BARE_URL.sub(
        lambda m: f'<a href="{m.group(0)}">{m.group(0)}</a>', html.escape(text, quote=False)
    )
    return linked.replace("\n", "<br>")


class NotesDialog(QDialog):
    """Read-only notes. Links open in the browser; remote images never load."""

    def __init__(self, title: str, notes: str | None, *, subtitle: str = "",
                 links: list[tuple[str, str]] = (), parent=None):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.resize(560, 520)

        lay = QVBoxLayout(self)
        heading = QLabel(title)
        heading.setObjectName("PageTitle")
        heading.setWordWrap(True)
        lay.addWidget(heading)
        if subtitle:
            sub = QLabel(subtitle)
            sub.setObjectName("EpisodeMeta")
            sub.setWordWrap(True)
            lay.addWidget(sub)

        link_bits = [
            f'<a href="{html.escape(url)}">{html.escape(label)}</a>'
            for label, url in links if url and url.startswith(("http://", "https://"))
        ]
        if link_bits:
            row = QLabel("  ·  ".join(link_bits))
            row.setOpenExternalLinks(True)
            lay.addWidget(row)

        body = QTextBrowser()
        body.setObjectName("NotesBody")
        body.setOpenExternalLinks(True)
        body.setHtml(notes_html(notes) or "<i>No description in the feed.</i>")
        lay.addWidget(body, 1)

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)
        buttons.rejected.connect(self.reject)
        lay.addWidget(buttons)
