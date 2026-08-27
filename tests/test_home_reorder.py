"""Dragging a queue row on the Home page.

The Home page shows the head of the queue, so a drag there has to mean exactly
what the same drag means on the Queue page. The arithmetic is the whole risk:
QueueManager.move() inserts into the list with the dragged row already removed,
so a downward drag loses one place to that removal and an upward one does not.
Get it wrong and every upward drag looks perfect while every downward drag
lands one short — which reads as "reordering is flaky", not as a bug.
"""

from __future__ import annotations

import pytest

from aerialpod.ui.episode_list import resolved_drop_index


def reorder(order: list[str], here: int, target: int) -> list[str]:
    """Apply a drop the way QueueManager.move() would, for comparison."""
    index = resolved_drop_index(here, target, len(order))
    rest = [x for i, x in enumerate(order) if i != here]
    rest.insert(index, order[here])
    return rest


ABCDE = ["a", "b", "c", "d", "e"]


def test_dragging_the_first_row_to_the_end():
    assert reorder(ABCDE, here=0, target=5) == ["b", "c", "d", "e", "a"]


def test_dragging_the_last_row_to_the_front():
    assert reorder(ABCDE, here=4, target=0) == ["e", "a", "b", "c", "d"]


def test_dragging_down_one_place():
    # target=2 means "insert above index 2" while 'a' still occupies index 0.
    assert reorder(ABCDE, here=0, target=2) == ["b", "a", "c", "d", "e"]


def test_dragging_up_one_place():
    assert reorder(ABCDE, here=2, target=1) == ["a", "c", "b", "d", "e"]


def test_a_drop_onto_its_own_position_changes_nothing():
    assert resolved_drop_index(2, 2, 5) == 2
    assert resolved_drop_index(2, 3, 5) == 2


@pytest.mark.parametrize("here", range(5))
def test_every_row_can_reach_every_position(here):
    reached = {resolved_drop_index(here, target, 5) for target in range(6)}
    assert reached == {0, 1, 2, 3, 4}, f"row {here} cannot reach every slot"


@pytest.mark.parametrize("here", range(5))
@pytest.mark.parametrize("target", range(6))
def test_the_result_is_always_a_permutation(here, target):
    # A wrong index silently duplicates or drops an episode rather than
    # raising, and the queue is what the user would have to repair by hand.
    assert sorted(reorder(ABCDE, here, target)) == sorted(ABCDE)


def test_index_is_never_out_of_range():
    for count in (1, 2, 5):
        for here in range(count):
            for target in range(count + 2):
                assert 0 <= resolved_drop_index(here, target, count) < count


def test_single_row_list_stays_put():
    assert reorder(["only"], here=0, target=1) == ["only"]


# --------------------------------------------------------------- the widget


def _list_with_rows(count: int):
    """A reorderable list whose rows are widgets, as the real one has."""
    from PySide6.QtCore import QSize
    from PySide6.QtWidgets import (
        QHBoxLayout, QLabel, QListWidgetItem, QPushButton, QWidget,
    )

    from aerialpod.ui.episode_list import EpisodeListWidget

    started: list[bool] = []

    class Probe(EpisodeListWidget):
        def startDrag(self, actions):  # noqa: N802 (Qt naming)
            started.append(True)  # record instead of entering a real drag loop

    lst = Probe()
    lst.set_reorderable(True)
    lst.resize(400, 300)
    rows = []
    for n in range(count):
        row = QWidget()
        lay = QHBoxLayout(row)
        lay.addWidget(QLabel(f"Episode {n}"))
        lay.addWidget(QPushButton("Play"))
        row.episode_id = 100 + n
        item = QListWidgetItem()
        item.setSizeHint(QSize(0, 72))
        lst.addItem(item)
        lst.setItemWidget(item, row)
        rows.append(row)
    return lst, rows, started


def test_reorderable_turns_on_dragging_and_back_off(qapp):
    from aerialpod.ui.episode_list import EpisodeListWidget

    lst = EpisodeListWidget()
    assert not lst.dragEnabled()
    lst.set_reorderable(True)
    assert lst.dragEnabled() and lst.acceptDrops()
    lst.set_reorderable(False)
    assert not lst.dragEnabled()


def test_a_press_on_the_row_widget_still_starts_a_drag(qapp):
    """The row is a widget installed over the item, so it sees the press first.

    If it swallowed it the list would never begin a drag and the feature would
    be silently dead — nothing would error, dragging simply would not do
    anything. That is the whole risk of reordering a list built this way.
    """
    from PySide6.QtCore import QPoint, Qt
    from PySide6.QtTest import QTest

    lst, rows, started = _list_with_rows(3)
    lst.show()
    qapp.processEvents()

    target = rows[1]
    QTest.mousePress(
        target, Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier,
        QPoint(20, target.height() // 2),
    )
    qapp.processEvents()
    QTest.mouseMove(lst.viewport(), lst.visualItemRect(lst.item(1)).center() + QPoint(0, 100))
    qapp.processEvents()

    assert started, "a press on the row widget never reached the list"
    assert lst.currentRow() == 1, "the dragged row must be the one pressed"


def test_the_show_more_row_is_not_reorderable(qapp):
    """The sentinel row is a button, not an episode; it must not count."""
    from PySide6.QtCore import QSize
    from PySide6.QtWidgets import QListWidgetItem, QPushButton

    lst, _rows, _started = _list_with_rows(2)
    more = QListWidgetItem()
    more.setData(32, "more")
    more.setSizeHint(QSize(0, 44))
    lst.addItem(more)
    lst.setItemWidget(more, QPushButton("Show more"))

    assert lst.count() == 3
    assert lst._episode_rows() == [0, 1], "the sentinel must be excluded"
    assert lst._episode_id_at(2) is None
