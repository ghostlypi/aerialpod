"""Start with the session when running as a flatpak.

A native install gets this from the systemd user unit install.sh writes. A
flatpak can't install units, so instead the daemon asks the Background portal
to autostart it. The desktop asks the user once and remembers the answer;
asking again on every start is a no-op that keeps the command line current.

If the user says no, or there is no portal, nothing breaks: D-Bus activation
still starts the daemon whenever the window opens, it just won't be syncing
before then.
"""

from __future__ import annotations

import asyncio
import logging
import os
import threading

from dbus_fast import Message, MessageType, Variant
from dbus_fast.aio import MessageBus

log = logging.getLogger(__name__)

RESPONSE_TIMEOUT = 300.0  # the user may leave the dialog up for a while


async def _request_background(command: list[str]) -> None:
    bus = await MessageBus().connect()
    try:
        # The portal answers on a Request object whose path we can predict,
        # so subscribe before asking — otherwise a fast reply is missed.
        token = f"aerialpod_{os.getpid()}"
        sender = bus.unique_name.lstrip(":").replace(".", "_")
        handle = f"/org/freedesktop/portal/desktop/request/{sender}/{token}"
        answer: asyncio.Future = asyncio.get_running_loop().create_future()

        def on_message(msg: Message) -> None:
            if (msg.message_type == MessageType.SIGNAL and msg.path == handle
                    and msg.member == "Response" and not answer.done()):
                answer.set_result(msg.body)

        bus.add_message_handler(on_message)
        await bus.call(Message(
            destination="org.freedesktop.DBus", path="/org/freedesktop/DBus",
            interface="org.freedesktop.DBus", member="AddMatch", signature="s",
            body=[f"type='signal',interface='org.freedesktop.portal.Request',"
                  f"member='Response',path='{handle}'"]))

        reply = await bus.call(Message(
            destination="org.freedesktop.portal.Desktop",
            path="/org/freedesktop/portal/desktop",
            interface="org.freedesktop.portal.Background",
            member="RequestBackground", signature="sa{sv}",
            body=["", {
                "handle_token": Variant("s", token),
                "reason": Variant("s", "Keep podcasts synced while the window is closed"),
                "autostart": Variant("b", True),
                "commandline": Variant("as", command),
            }]))
        if reply.message_type == MessageType.ERROR:
            log.info("background portal unavailable: %s", reply.body)
            return

        code, results = await asyncio.wait_for(answer, RESPONSE_TIMEOUT)
        if code == 0 and results.get("autostart", Variant("b", False)).value:
            log.info("autostart granted by the background portal")
        else:
            log.info("autostart not granted (response %d) — the daemon will start "
                     "when the window opens instead", code)
    finally:
        bus.disconnect()


def request_autostart(command: list[str]) -> None:
    """Ask for autostart in the background; returns immediately.

    Only meaningful inside a flatpak — natively, systemd already does this.
    """
    if not os.environ.get("FLATPAK_ID"):
        return

    def run() -> None:
        try:
            asyncio.run(_request_background(command))
        except Exception as exc:  # noqa: BLE001 — autostart is a nicety, never fatal
            log.info("could not request autostart: %s", exc)

    threading.Thread(target=run, name="background-portal", daemon=True).start()
