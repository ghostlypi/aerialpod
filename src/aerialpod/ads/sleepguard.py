"""SleepGuard: get the detector off the GPU before the laptop suspends.

A CUDA job left running across suspend takes the NVIDIA driver down with it on
resume (a flood of NVRM assertions, then a hung machine). So while a detection
run is in flight we hold a logind *delay* inhibitor: closing the lid still
suspends, but logind first broadcasts PrepareForSleep(true) and waits — up to
InhibitDelayMaxSec — for us to kill the run and let go of the inhibitor.

It also passes on charger changes from UPower, so a detector waiting for AC
starts the moment the charger goes in instead of polling for it. Each line-power
device's Online is watched as well as the daemon's OnBattery: OnBattery follows
the battery's charging state, which can lag the cable by a while.

Same threading rule as the daemon's bus service: the asyncio loop has its own
thread, and crosses back to the detector only through Qt signals, which arrive
queued on the detector's thread.
"""

from __future__ import annotations

import asyncio
import logging
import os
import threading

from PySide6.QtCore import QObject, Signal

log = logging.getLogger(__name__)

LOGIND = "org.freedesktop.login1"
LOGIND_PATH = "/org/freedesktop/login1"
LOGIND_MANAGER = "org.freedesktop.login1.Manager"
UPOWER = "org.freedesktop.UPower"
UPOWER_PATH = "/org/freedesktop/UPower"
UPOWER_DEVICE = "org.freedesktop.UPower.Device"
LINE_POWER = 1  # UPower.Device Type
PROPERTIES = "org.freedesktop.DBus.Properties"


class SleepGuard(QObject):
    aboutToSleep = Signal()
    resumed = Signal()
    powerChanged = Signal()  # AC plugged in or out; re-read on_battery()

    def __init__(self, parent: QObject | None = None):
        super().__init__(parent)
        self._loop: asyncio.AbstractEventLoop | None = None
        self._bus = None
        self._fd: int | None = None
        self._wanted = False          # hold() called and not yet release()d
        self._line_power: set[str] = set()  # UPower paths of chargers
        self._thread = threading.Thread(target=self._run, name="sleep-guard", daemon=True)
        self._thread.start()

    # ------------------------------------------------------------ public (any thread)

    def hold(self) -> None:
        """Delay the next suspend until release()."""
        self._wanted = True
        self._call(self._inhibit)

    def release(self) -> None:
        self._wanted = False
        self._call(self._close)

    def stop(self) -> None:
        self.release()
        if self._loop is not None:
            self._loop.call_soon_threadsafe(self._loop.stop)

    # ------------------------------------------------------------ the loop's thread

    def _call(self, fn) -> None:
        if self._loop is not None and self._bus is not None:
            self._loop.call_soon_threadsafe(fn)

    def _run(self) -> None:
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        try:
            self._loop.run_until_complete(self._connect())
        except Exception as exc:  # no system bus (a container, a flatpak sandbox)
            log.warning("can't watch for suspend; ad detection may not survive "
                        "the lid closing (%s)", exc)
            return
        self._loop.run_forever()

    async def _connect(self) -> None:
        from dbus_fast import BusType, Message
        from dbus_fast.aio import MessageBus

        bus = await MessageBus(bus_type=BusType.SYSTEM, negotiate_unix_fd=True).connect()
        await bus.call(Message(
            destination="org.freedesktop.DBus", path="/org/freedesktop/DBus",
            interface="org.freedesktop.DBus", member="AddMatch", signature="s",
            body=[f"type='signal',sender='{LOGIND}',interface='{LOGIND_MANAGER}',"
                  "member='PrepareForSleep'"],
        ))
        await bus.call(Message(
            destination="org.freedesktop.DBus", path="/org/freedesktop/DBus",
            interface="org.freedesktop.DBus", member="AddMatch", signature="s",
            body=[f"type='signal',sender='{UPOWER}',path='{UPOWER_PATH}',"
                  f"interface='{PROPERTIES}',member='PropertiesChanged',arg0='{UPOWER}'"],
        ))
        try:
            await self._watch_line_power(bus)
        except Exception as exc:  # no UPower: the detector's slow recheck covers it
            log.info("can't watch the charger through UPower (%s)", exc)
        bus.add_message_handler(self._on_message)
        self._bus = bus
        if self._wanted:              # hold() came in before the bus was up
            self._inhibit()

    async def _watch_line_power(self, bus) -> None:
        """Match PropertiesChanged on the chargers only — the battery's own
        device updates every few seconds and would wake us for nothing."""
        from dbus_fast import Message

        reply = await bus.call(Message(
            destination=UPOWER, path=UPOWER_PATH, interface=UPOWER,
            member="EnumerateDevices",
        ))
        for path in reply.body[0] if reply.body else []:
            kind = await bus.call(Message(
                destination=UPOWER, path=path, interface=PROPERTIES, member="Get",
                signature="ss", body=[UPOWER_DEVICE, "Type"],
            ))
            if not kind.body or kind.body[0].value != LINE_POWER:
                continue
            await bus.call(Message(
                destination="org.freedesktop.DBus", path="/org/freedesktop/DBus",
                interface="org.freedesktop.DBus", member="AddMatch", signature="s",
                body=[f"type='signal',sender='{UPOWER}',path='{path}',"
                      f"interface='{PROPERTIES}',member='PropertiesChanged',"
                      f"arg0='{UPOWER_DEVICE}'"],
            ))
            self._line_power.add(path)

    def _on_message(self, msg) -> None:
        if msg.member == "PropertiesChanged" and msg.body:
            changed = msg.body[1] if len(msg.body) > 1 else {}
            if ((msg.path == UPOWER_PATH and msg.body[0] == UPOWER
                 and "OnBattery" in changed)
                    or (msg.path in self._line_power and "Online" in changed)):
                log.info("power source changed")
                self.powerChanged.emit()
            return
        if msg.member != "PrepareForSleep" or msg.interface != LOGIND_MANAGER:
            return
        if msg.body and msg.body[0]:
            log.info("system is about to sleep")
            self.aboutToSleep.emit()
        else:
            log.info("system resumed")
            self.resumed.emit()

    def _inhibit(self) -> None:
        if self._fd is None and self._bus is not None:
            asyncio.ensure_future(self._take())

    async def _take(self) -> None:
        from dbus_fast import Message, MessageType

        reply = await self._bus.call(Message(
            destination=LOGIND, path=LOGIND_PATH, interface=LOGIND_MANAGER,
            member="Inhibit", signature="ssss",
            body=["sleep", "AerialPod", "Stopping ad detection on the GPU", "delay"],
        ))
        if reply.message_type == MessageType.ERROR:
            log.warning("logind refused a sleep inhibitor: %s", reply.body)
            return
        fd = reply.unix_fds[reply.body[0]]
        if self._wanted and self._fd is None:
            self._fd = fd
        else:                         # released, or already held, while we waited
            os.close(fd)

    def _close(self) -> None:
        if self._fd is not None:
            os.close(self._fd)
            self._fd = None
