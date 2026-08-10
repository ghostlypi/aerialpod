"""The bus contract, in one place so both ends agree by construction.

Commands are one-way. Only Version() returns anything, and it exists so the
window can refuse to run against a database schema newer than it understands.

Argument types are ordinary D-Bus types except for settings values, which can
be a string, number, boolean, null or list depending on the key. Those travel
as JSON in a string ('j' below) rather than as a variant — a variant would mean
picking a signature at every call site for no gain, since the value is going
into a JSON column anyway.
"""

from __future__ import annotations

import json

BUS_NAME = "org.aerialpod.Daemon"
OBJECT_PATH = "/org/aerialpod/Daemon"
INTERFACE = "org.aerialpod.Daemon1"

# command name -> (D-Bus member, argument kinds)
#   u=uint32  i=int32  b=boolean  s=string  j=JSON-encoded value
COMMANDS: dict[str, tuple[str, str]] = {
    "sync_now": ("SyncNow", ""),
    "refresh_all": ("RefreshAll", ""),
    "refresh_one": ("RefreshOne", "u"),
    "subscribe": ("Subscribe", "s"),
    "unsubscribe": ("Unsubscribe", "u"),
    "import_opml": ("ImportOpml", "s"),
    "set_account": ("SetAccount", "ss"),
    "forget_account": ("ForgetAccount", ""),
    "queue_add": ("QueueAdd", "ub"),
    "queue_remove": ("QueueRemove", "ub"),
    "queue_toggle": ("QueueToggle", "u"),
    "queue_move": ("QueueMove", "ui"),
    "queue_pin": ("QueuePin", "u"),
    "queue_release_to_auto": ("QueueReleaseToAuto", "u"),
    "mark_played": ("MarkPlayed", "u"),
    "mark_unplayed": ("MarkUnplayed", "u"),
    "reconcile": ("Reconcile", ""),
    "report_position": ("ReportPosition", "uuub"),
    "set_playing": ("SetPlaying", "u"),
    "set_podcast_setting": ("SetPodcastSetting", "usj"),
    "set_state": ("SetState", "sj"),
    "lan_pair": ("LanPair", "s"),
    "lan_new_code": ("LanNewCode", ""),
    "lan_add_peer": ("LanAddPeer", "su"),
    "lan_remove_peer": ("LanRemovePeer", "su"),
    "lan_discover": ("LanDiscover", ""),
}

# Qt signal name -> D-Bus member. The daemon emits these; the window re-emits
# them on its client, so a page connects to the same name either way.
SIGNALS: dict[str, str] = {
    "queueChanged": "QueueChanged",
    "syncStarted": "SyncStarted",
    "syncFinished": "SyncFinished",
    "syncFailed": "SyncFailed",
    "subscriptionsChanged": "SubscriptionsChanged",
    "refreshStarted": "RefreshStarted",
    "podcastRefreshed": "PodcastRefreshed",
    "refreshFinished": "RefreshFinished",
    "refreshError": "RefreshError",
    "peersChanged": "PeersChanged",
    "lanStatus": "LanStatus",
    "pairingChanged": "PairingChanged",
    "stateMerged": "StateMerged",
    "downloadStarted": "DownloadStarted",
    "downloadFinished": "DownloadFinished",
    "downloadFailed": "DownloadFailed",
}


def encode_args(command: str, args: tuple) -> list:
    """Python call arguments → D-Bus argument list."""
    _, kinds = COMMANDS[command]
    if len(args) != len(kinds):
        raise ValueError(f"{command} takes {len(kinds)} argument(s), got {len(args)}")
    return [
        json.dumps(value) if kind == "j" else value
        for kind, value in zip(kinds, args, strict=True)
    ]


def decode_args(command: str, args: list) -> tuple:
    """D-Bus argument list → Python call arguments."""
    _, kinds = COMMANDS[command]
    return tuple(
        json.loads(value) if kind == "j" else value
        for kind, value in zip(kinds, args, strict=True)
    )


def dbus_signature(command: str) -> str:
    """What the member's arguments look like on the wire ('j' rides in a string)."""
    _, kinds = COMMANDS[command]
    return kinds.replace("j", "s")
