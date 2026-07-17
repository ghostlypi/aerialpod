"""Thread-affinity regression tests.

PySide6 executes lambda/free-function slots in the EMITTING thread; only
bound methods of QObjects get queued to the receiver's thread. These tests
assert that worker results and sync signals are delivered on the main thread
— the bug class that segfaulted the app when sync updated the status bar.
"""

from __future__ import annotations

import time

from PySide6.QtCore import QThread

from aerialpod.workers import run_in_pool


def _wait_until(app, predicate, timeout_s=5.0) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        app.processEvents()
        if predicate():
            return True
        time.sleep(0.01)
    return False


def test_run_in_pool_callbacks_on_main_thread(qapp):
    main = QThread.currentThread()
    seen: dict = {}

    run_in_pool(lambda: 42, on_done=lambda r: seen.update(
        value=r, thread=QThread.currentThread()))
    assert _wait_until(qapp, lambda: "value" in seen)
    assert seen["value"] == 42
    assert seen["thread"] is main


def test_run_in_pool_error_on_main_thread(qapp):
    main = QThread.currentThread()
    seen: dict = {}

    def boom():
        raise ValueError("nope")

    run_in_pool(boom, on_error=lambda e: seen.update(
        exc=e, thread=QThread.currentThread()))
    assert _wait_until(qapp, lambda: "exc" in seen)
    assert isinstance(seen["exc"], ValueError)
    assert seen["thread"] is main


def test_sync_signals_delivered_on_main_thread(qapp, fresh_db, monkeypatch):
    """End-to-end: a real SyncService on a real QThread, mocked HTTP."""
    from PySide6.QtCore import QObject

    from aerialpod.gpodder import sync as sync_mod
    from aerialpod.gpodder.sync import start_sync_service

    monkeypatch.setattr(sync_mod.credentials, "load", lambda: ("user", "pw"))

    class FakeResponse:
        def __init__(self, data):
            self._data = data

        def json(self):
            return self._data

    class FakeClient:
        def __init__(self, *a, **k):
            self.username = "user"
            self.password = "pw"

        def login(self):
            pass

        def register_device(self, *a, **k):
            pass

        def get_episode_actions(self, since, aggregated=True):
            return {"actions": [], "timestamp": 111}

        def upload_episode_actions(self, actions):
            return {"timestamp": 111}

        def upload_subscription_changes(self, device, add, remove):
            return {"timestamp": 111, "update_urls": []}

        def get_subscription_changes(self, device, since):
            return {"add": [], "remove": [], "timestamp": 111}

        def _request(self, method, path, **k):
            return FakeResponse([])

    monkeypatch.setattr(sync_mod, "GpodderClient", FakeClient)

    class Recorder(QObject):
        def __init__(self):
            super().__init__()
            self.events: list[tuple[str, QThread]] = []

        def on_started(self):
            self.events.append(("started", QThread.currentThread()))

        def on_finished(self, msg):
            self.events.append(("finished", QThread.currentThread()))

        def on_failed(self, msg):
            self.events.append(("failed:" + msg, QThread.currentThread()))

    main = QThread.currentThread()
    rec = Recorder()
    service, thread = start_sync_service()
    try:
        service.syncStarted.connect(rec.on_started)
        service.syncFinished.connect(rec.on_finished)
        service.syncFailed.connect(rec.on_failed)

        from PySide6.QtCore import QMetaObject, Qt

        QMetaObject.invokeMethod(service, "sync_now", Qt.ConnectionType.QueuedConnection)
        assert _wait_until(
            qapp, lambda: any(e[0].startswith(("finished", "failed")) for e in rec.events)
        ), f"sync never completed: {rec.events}"

        kinds = [e[0] for e in rec.events]
        assert "started" in kinds
        assert "finished" in kinds, f"sync failed: {kinds}"
        # THE regression assertion: every slot ran on the main thread
        for kind, thr in rec.events:
            assert thr is main, f"slot {kind!r} ran on {thr}, not the main thread"
        # sync must have run on ITS OWN thread, not main
        assert service.thread() is not main
    finally:
        thread.quit()
        thread.wait(3000)
