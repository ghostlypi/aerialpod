"""AdDetector's scheduling, against a fake toolchain.

The real pipeline needs a GPU and gigabytes of weights; what is under test here
is everything around it — which episode runs next, holding downloads against
eviction, storing what comes back, and coping when a run fails.
"""

from __future__ import annotations

import json
import stat
import time

import pytest
from PySide6.QtCore import QCoreApplication, QObject, Signal

from aerialpod.ads import detector, spans
from aerialpod.db import repo

from .conftest import make_episode

RESULT = {
    "duration_ms": 60_000,
    "spans": [[10_000, 20_000, 0.9]],
    "anchors": [{"pre": [], "post": []}],
    "model": spans.model_tag(spans.DEFAULT_MODEL),
}


class FakeQueue(QObject):
    queueChanged = Signal()

    def __init__(self, ids):
        super().__init__()
        self.ids = ids

    def episodes(self):
        return [repo.episode_by_id(i) for i in self.ids]


class FakeDownloads(QObject):
    downloadFinished = Signal(int)
    downloadFailed = Signal(int, str)

    def __init__(self):
        super().__init__()
        self.hold: set[int] = set()
        self.fetched: list[int] = []
        self.policy_runs = 0

    def fetch(self, ep):
        self.fetched.append(ep.id)

    def apply_policy(self):
        self.policy_runs += 1


@pytest.fixture()
def toolchain(tmp_path, monkeypatch):
    """A venv whose python prints RESULT — or fails, if FAIL exists."""
    venv = tmp_path / "venv"
    (venv / "bin").mkdir(parents=True)
    script = venv / "bin/python"
    script.write_text(
        "#!/bin/sh\n"
        f'[ -e "{tmp_path}/FAIL" ] && {{ echo "boom" >&2; exit 1; }}\n'
        f'[ -e "{tmp_path}/SLOW" ] && exec sleep 30\n'
        "echo 'PROGRESS 0.500 detecting' >&2\n"
        f"echo '{json.dumps(RESULT)}'\n"
    )
    script.chmod(script.stat().st_mode | stat.S_IEXEC)
    monkeypatch.setenv("AERIALPOD_AI_VENV", str(venv))
    return tmp_path


def downloaded(podcast, n, tmp_path) -> int:
    eid = make_episode(podcast, n)
    path = tmp_path / f"ep{n}.mp3"
    path.write_bytes(b"\0" * 1000)
    repo.update_episode(eid, download_state="done", downloaded_path=str(path))
    return eid


def run_until(condition, timeout=10.0):
    deadline = time.monotonic() + timeout
    while not condition():
        assert time.monotonic() < deadline, "timed out"
        QCoreApplication.processEvents()
        time.sleep(0.01)


@pytest.fixture()
def make_detector(podcast, toolchain):
    repo.set_state("ads_on_battery", True)  # the test machine may be a laptop on battery
    made = []

    def make(ids):
        d = detector.AdDetector(FakeQueue(ids), FakeDownloads())
        d._debounce.setInterval(0)
        made.append(d)
        return d

    yield make
    for d in made:
        d.shutdown()


def test_works_through_the_queue_and_stores_what_it_finds(podcast, toolchain, make_detector):
    ids = [downloaded(podcast, n, toolchain) for n in (1, 2)]
    d = make_detector(ids)
    statuses = []
    d.statusChanged.connect(statuses.append)

    d.start()
    run_until(lambda: repo.analysed_episode_ids() == set(ids))

    row = repo.ad_segments(ids[0])
    assert row["spans"] == RESULT["spans"]
    assert row["anchors"] == RESULT["anchors"]
    assert row["model"] == RESULT["model"]
    run_until(lambda: "every queued episode" in (statuses[-1] if statuses else ""))
    assert any("50%" in s for s in statuses)  # progress from the subprocess
    assert d.downloads.hold == set()           # nothing kept past its analysis


def test_fetches_and_holds_what_is_not_downloaded(podcast, toolchain, make_detector):
    eid = make_episode(podcast, 1)
    d = make_detector([eid])
    d.start()
    run_until(lambda: d.downloads.fetched == [eid])
    assert eid in d.downloads.hold

    path = toolchain / "late.mp3"
    path.write_bytes(b"\0")
    repo.update_episode(eid, download_state="done", downloaded_path=str(path))
    d.downloads.downloadFinished.emit(eid)
    run_until(lambda: eid in repo.analysed_episode_ids())


def test_holds_the_next_episode_even_when_already_downloaded(podcast, toolchain, make_detector):
    ids = [downloaded(podcast, n, toolchain) for n in (1, 2)]
    d = make_detector(ids)
    d.start()
    run_until(lambda: d._proc is not None)
    assert d.downloads.hold >= set(ids)


def test_a_failure_is_retried_later_not_forever_skipped(podcast, toolchain, make_detector,
                                                        monkeypatch):
    eid = downloaded(podcast, 1, toolchain)
    (toolchain / "FAIL").touch()
    d = make_detector([eid])
    d.start()
    run_until(lambda: eid in d._failed)
    assert eid not in repo.analysed_episode_ids()
    assert eid not in {ep.id for ep in d._pending()}

    (toolchain / "FAIL").unlink()
    monkeypatch.setattr(detector, "RETRY_FAILED_S", 0)
    d.schedule()
    run_until(lambda: eid in repo.analysed_episode_ids())


def test_off_without_a_toolchain(podcast, monkeypatch, tmp_path):
    monkeypatch.setenv("AERIALPOD_AI_VENV", str(tmp_path / "nowhere"))
    assert detector.toolchain_python() is None
    assert detector.enabled() is False


def test_a_failure_says_why_in_words(podcast, toolchain, make_detector):
    eid = downloaded(podcast, 1, toolchain)
    (toolchain / "FAIL").touch()
    d = make_detector([eid])
    statuses = []
    d.statusChanged.connect(statuses.append)
    d.start()
    run_until(lambda: eid in d._failed)
    assert detector.FAILURE_MARK in d._status
    assert "boom" in d._status and "tried again at" in d._status


def test_retry_runs_a_failed_episode_now(podcast, toolchain, make_detector):
    eid = downloaded(podcast, 1, toolchain)
    (toolchain / "FAIL").touch()
    d = make_detector([eid])
    d.start()
    run_until(lambda: eid in d._failed)
    (toolchain / "FAIL").unlink()
    d.retry_failed()
    run_until(lambda: eid in repo.analysed_episode_ids())
    assert detector.FAILURE_MARK not in d._status


def test_pausing_stops_the_run_without_failing_it(podcast, toolchain, make_detector):
    eid = downloaded(podcast, 1, toolchain)
    (toolchain / "SLOW").touch()
    d = make_detector([eid])
    d.start()
    run_until(lambda: d._proc is not None)
    repo.set_state("ads_paused", True)
    d.set_paused(True)
    assert d._proc is None and eid not in d._failed
    assert eid in d.downloads.hold          # kept on disk for the redo
    QCoreApplication.processEvents()
    assert "paused" in d._status

    (toolchain / "SLOW").unlink()
    repo.set_state("ads_paused", False)
    d.set_paused(False)
    run_until(lambda: eid in repo.analysed_episode_ids())


@pytest.mark.parametrize("stderr, expect", [
    ("RuntimeError: CUDA error: CUBLAS_STATUS_INTERNAL_ERROR when calling", "out of memory"),
    ("torch.OutOfMemoryError: CUDA out of memory. Tried to allocate", "out of memory"),
    ('File "/x/huggingface_hub/f.py", line 3\nValueError: bad shape', "ValueError: bad shape"),
    ("ModuleNotFoundError: No module named 'av'", "install.sh ai"),
])
def test_explain_failure(stderr, expect):
    assert expect in detector.explain_failure(stderr)
