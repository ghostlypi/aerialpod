"""run_in_pool: run a blocking function in QThreadPool, deliver the result
(or exception) back on the MAIN thread.

PySide6 pitfall this module exists to handle: connecting a plain callable or
lambda to a signal executes it in the EMITTING thread. To actually hop
threads, the connection target must be a bound method of a QObject that lives
on the destination thread (AutoConnection then queues it). The _Emitter is
created on the main thread and receives its own signals via bound-method
slots, so the user callbacks always run on the main thread.
"""

from __future__ import annotations

import logging
import traceback
from collections.abc import Callable

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Signal, Slot

log = logging.getLogger(__name__)


class _Emitter(QObject):
    """Created on the main thread; its bound-method slots run there."""

    done = Signal(object)
    error = Signal(object)

    def __init__(self, on_done: Callable | None, on_error: Callable | None):
        super().__init__()
        self.on_done = on_done
        self.on_error = on_error
        # bound-method slots → queued delivery to the main thread
        self.done.connect(self._handle_done)
        self.error.connect(self._handle_error)

    @Slot(object)
    def _handle_done(self, result) -> None:
        try:
            if self.on_done is not None:
                self.on_done(result)
        finally:
            _live.discard(self)

    @Slot(object)
    def _handle_error(self, exc) -> None:
        try:
            if self.on_error is not None:
                self.on_error(exc)
            else:
                log.error("unhandled worker error: %r", exc)
        finally:
            _live.discard(self)


class _Job(QRunnable):
    def __init__(self, fn: Callable, emitter: _Emitter):
        super().__init__()
        self.fn = fn
        self.emitter = emitter

    def run(self) -> None:
        try:
            result = self.fn()
        except Exception as exc:  # noqa: BLE001 — marshaled to on_error
            log.debug("worker failed: %s", traceback.format_exc())
            self._emit(self.emitter.error, exc)
        else:
            self._emit(self.emitter.done, result)

    def _emit(self, signal, payload) -> None:
        try:
            signal.emit(payload)
        except RuntimeError:  # emitter torn down during app shutdown
            log.debug("worker result dropped: app shutting down")


# Keep emitters alive until their job's result has been delivered.
_live: set[_Emitter] = set()


def run_in_pool(
    fn: Callable,
    on_done: Callable | None = None,
    on_error: Callable | None = None,
) -> None:
    """Run fn() in the global thread pool; call on_done(result) / on_error(exc)
    on the main thread. MUST be called from the main thread."""
    emitter = _Emitter(on_done, on_error)
    _live.add(emitter)
    QThreadPool.globalInstance().start(_Job(fn, emitter))
