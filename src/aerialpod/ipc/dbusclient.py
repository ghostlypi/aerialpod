"""The window's side of the bus.

probe_daemon() is a short synchronous round trip used once at startup, before
any Qt object exists: it answers "is there a service, and what schema is the
database on" and, as a side effect, lets D-Bus activation start the daemon so
migrations are finished before this process opens the database.

DBusBackend is the long-lived connection — an asyncio loop in a QThread, same
shape as the daemon side. Commands go out fire-and-forget; signals come back as
Qt signals on the client, which Qt queues onto the main thread because the
client lives there.
"""

from __future__ import annotations

import asyncio
import logging
import threading

from dbus_fast.aio import MessageBus
from PySide6.QtCore import QObject, QThread

from .protocol import BUS_NAME, COMMANDS, INTERFACE, OBJECT_PATH, SIGNALS, encode_args

log = logging.getLogger(__name__)

PROBE_TIMEOUT = 8.0


async def _fetch_version() -> tuple[str, int] | None:
    bus = await MessageBus().connect()
    try:
        introspection = await bus.introspect(BUS_NAME, OBJECT_PATH)
        proxy = bus.get_proxy_object(BUS_NAME, OBJECT_PATH, introspection)
        iface = proxy.get_interface(INTERFACE)
        version, schema = await iface.call_version()
        return str(version), int(schema)
    finally:
        bus.disconnect()


def probe_daemon(timeout: float = PROBE_TIMEOUT) -> tuple[str, int] | None:
    """(version, schema) if the service answered, else None.

    Every failure is the same answer — no bus, not installed, activation
    refused, too slow — because the caller's response to all of them is to run
    the services in this process instead.
    """
    async def run():
        return await asyncio.wait_for(_fetch_version(), timeout)

    try:
        return asyncio.run(run())
    except Exception as exc:  # noqa: BLE001
        log.debug("no AerialPod service on the bus: %s", exc)
        return None


class DBusBackend:
    name = "d-bus"

    def __init__(self):
        self.client = None
        self.loop: asyncio.AbstractEventLoop | None = None
        self._iface = None
        self._bus = None
        self._ready = threading.Event()
        self._relay: QObject | None = None
        self.thread = QThread()
        self.thread.setObjectName("dbus-client")
        self.thread.run = self._run  # type: ignore[method-assign]

    def attach(self, client) -> None:
        self.client = client

    def start(self) -> None:
        self.thread.start()

    def shutdown(self) -> None:
        if self.loop is not None:
            self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.quit()
        self.thread.wait(2000)

    threads_running = False  # the daemon owns the blocking work now

    # ------------------------------------------------------------ loop

    def _run(self) -> None:
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self._serve())
        except RuntimeError:
            log.debug("D-Bus client loop stopped (shutdown)")
        except Exception:  # noqa: BLE001
            log.exception("lost the connection to the AerialPod service")
            self._notify_availability(False)

    async def _serve(self) -> None:
        self._bus = bus = await MessageBus().connect()
        introspection = await bus.introspect(BUS_NAME, OBJECT_PATH)
        proxy = bus.get_proxy_object(BUS_NAME, OBJECT_PATH, introspection)
        self._iface = proxy.get_interface(INTERFACE)

        for qt_name, member in SIGNALS.items():
            handler = self._make_handler(qt_name)
            getattr(self._iface, f"on_{_snake(member)}")(handler)

        self._ready.set()
        self._notify_availability(True)
        await bus.wait_for_disconnect()
        self._notify_availability(False)

    def _make_handler(self, qt_name: str):
        def handler(*args):
            signal = getattr(self.client, qt_name, None)
            if signal is None:
                return
            try:
                signal.emit(*_adapt(qt_name, args))
            except Exception:  # noqa: BLE001 — a bad payload must not kill the loop
                log.exception("failed to deliver %s", qt_name)

        return handler

    def _notify_availability(self, available: bool) -> None:
        if self.client is not None:
            self.client.availabilityChanged.emit(available)

    # ------------------------------------------------------------ commands

    def send(self, name: str, args: tuple) -> None:
        member, _ = COMMANDS[name]
        payload = encode_args(name, args)

        def dispatch():
            if self._iface is None:
                log.debug("dropping %s — not connected yet", name)
                return
            call = getattr(self._iface, f"call_{_snake(member)}")
            asyncio.ensure_future(_guarded(call(*payload), name))

        if self.loop is not None:
            self.loop.call_soon_threadsafe(dispatch)


async def _guarded(awaitable, name: str) -> None:
    try:
        await awaitable
    except Exception as exc:  # noqa: BLE001
        log.warning("command %s failed: %s", name, exc)


def _snake(member: str) -> str:
    """QueueAdd -> queue_add, the accessor names dbus-fast generates."""
    out = []
    for i, ch in enumerate(member):
        if ch.isupper() and i:
            out.append("_")
        out.append(ch.lower())
    return "".join(out)


def _adapt(qt_name: str, args: tuple) -> tuple:
    """Bus payload → what the Qt signal expects."""
    if qt_name == "subscriptionsChanged":
        return ([int(v) for v in args[0]],)
    if qt_name == "peersChanged":
        return ([dict(peer) for peer in args[0]],)
    if qt_name == "stateMerged":
        return ({str(k): int(v) for k, v in dict(args[0]).items()},)
    return args
