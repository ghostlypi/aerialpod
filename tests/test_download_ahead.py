"""Download-ahead: the same choices and arithmetic as the phone's
DownloadPolicy.resolve (mobile/shared/.../downloads/DownloadPolicy.kt)."""

from __future__ import annotations

import pytest

from aerialpod.core import downloads
from aerialpod.db import repo


@pytest.mark.parametrize("setting, queue, expect", [
    ("0", 10, 0),
    ("1", 10, 1),
    ("5", 3, 5),           # more than the queue just means all of it
    ("1/4", 3, 1),         # a share rounds up: never nothing for a non-empty queue
    ("1/3", 11, 4),
    ("1/2", 10, 5),
    ("all", 7, 7),
    ("ALL ", 7, 7),
    ("all", 0, 0),
    ("1/0", 10, 0),
    ("nonsense", 10, 1),
    ("a/b", 10, 1),
])
def test_resolve_matches_the_phone(setting, queue, expect):
    assert downloads.resolve_download_ahead(setting, queue) == expect


def test_falls_back_to_the_integer_setting(fresh_db):
    repo.set_state("download_ahead_n", 3)
    assert downloads.download_ahead() == "3"
    repo.set_state("download_ahead", "1/2")
    assert downloads.download_ahead() == "1/2"


def test_labels():
    assert [downloads.download_ahead_label(c) for c in downloads.DOWNLOAD_AHEAD_CHOICES] == [
        "Off", "1", "5", "1/4", "1/3", "1/2", "Whole queue"]
