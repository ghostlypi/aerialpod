"""Keep the mouse wheel from changing settings by accident, app-wide.

Qt lets the wheel step a dropdown through its entries just by hovering over
it, so scrolling down a page walks whatever dropdown passes under the pointer
to its last entry. In Settings that once switched the ad detector to its slow
CPU model, and every analysed episode was queued to run again.

So dropdowns never take the wheel, and number boxes take it only once they
have been clicked into. Either way the page under them scrolls instead.
"""

from __future__ import annotations

from PySide6.QtCore import QEvent, QObject, Qt
from PySide6.QtWidgets import (
    QAbstractScrollArea,
    QAbstractSpinBox,
    QApplication,
    QComboBox,
    QWidget,
)


def _guarded(widget: QWidget) -> QWidget | None:
    """The dropdown or number box this widget is, or is part of (an
    editable dropdown's line edit, a spin box's line edit)."""
    for w in (widget, widget.parentWidget()):
        if isinstance(w, QComboBox):
            return w
        if isinstance(w, QAbstractSpinBox):
            return None if w.hasFocus() else w
    return None


class WheelGuard(QObject):
    def eventFilter(self, obj, event) -> bool:  # Qt override
        if event.type() == QEvent.Type.Polish and isinstance(obj, (QComboBox, QAbstractSpinBox)):
            # Qt's default for both is WheelFocus: the wheel would focus a
            # number box and then step it. Click or Tab only.
            obj.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
            return False
        if event.type() != QEvent.Type.Wheel or not isinstance(obj, QWidget):
            return False
        box = _guarded(obj)
        if box is None:
            return False
        parent = box.parentWidget()
        while parent is not None and not isinstance(parent, QAbstractScrollArea):
            parent = parent.parentWidget()
        if parent is not None:
            QApplication.sendEvent(parent.verticalScrollBar(), event)
        return True


def install(app: QApplication) -> WheelGuard:
    guard = WheelGuard(app)
    app.installEventFilter(guard)
    return guard
