"""The two backends must agree, and the UI must not write to the database.

These are cheap structural checks, but they cover the failure modes that are
silent at runtime: a command that exists on one side and not the other becomes
a no-op, and a stray repo write in the window quietly reopens a second writer.
"""

from __future__ import annotations

import ast
import inspect
from pathlib import Path

import pytest

from aerialpod.ipc import protocol
from aerialpod.ipc.client import DaemonClient
from aerialpod.ipc.dbusservice import _Interface
from aerialpod.ipc.hub import COMMANDS, ServiceHub

UI_DIR = Path(__file__).resolve().parents[1] / "src" / "aerialpod" / "ui"

# Everything in repo that writes. The window may call none of them.
REPO_WRITERS = {
    "set_state", "set_podcast_setting", "update_episode", "upsert_podcast",
    "unsubscribe_podcast", "update_podcast_meta", "enqueue_action", "add_alias",
    "clear_outbox", "log_unmatched", "rewrite_feed_url", "add_manual_peer",
    "remove_manual_peer", "remember_peer", "forget_peer", "record_intent",
    "drop_intent", "prune_intents",
}


# ---------------------------------------------------------------- parity


def test_every_command_exists_on_the_hub():
    for name in COMMANDS:
        assert callable(getattr(ServiceHub, name, None)), f"hub is missing {name}"


def test_every_command_exists_on_the_client():
    for name in COMMANDS:
        assert callable(getattr(DaemonClient, name, None)), f"client is missing {name}"


def test_command_sets_match_exactly():
    assert set(COMMANDS) == set(protocol.COMMANDS)


def test_every_command_has_a_dbus_member():
    for name in COMMANDS:
        member, _ = protocol.COMMANDS[name]
        assert callable(getattr(_Interface, member, None)), f"no D-Bus method for {name}"


def test_signal_sets_match_exactly():
    assert set(protocol.SIGNALS) == set(DaemonClient.SIGNALS)


def test_every_signal_has_a_dbus_member():
    for member in protocol.SIGNALS.values():
        assert callable(getattr(_Interface, member, None)), f"no D-Bus signal {member}"


def test_client_and_hub_agree_on_argument_counts():
    """A command declared with the wrong arity fails only when someone uses it."""
    for name in COMMANDS:
        hub_params = inspect.signature(getattr(ServiceHub, name)).parameters
        required = [
            p for n, p in hub_params.items()
            if n != "self" and p.default is inspect.Parameter.empty
        ]
        _, kinds = protocol.COMMANDS[name]
        assert len(required) <= len(kinds) <= len(hub_params) - 1, (
            f"{name}: hub takes {len(hub_params) - 1} args, protocol declares {len(kinds)}"
        )


# ---------------------------------------------------------------- encoding


def test_json_arguments_survive_the_round_trip():
    for command, args in [
        ("set_state", ("home_sections", ["queue", "inbox"])),
        ("set_state", ("volume", 0.75)),
        ("set_state", ("lan_sync_enabled", True)),
        ("set_podcast_setting", (3, "playback_speed", None)),
    ]:
        assert protocol.decode_args(command, protocol.encode_args(command, args)) == args


def test_plain_arguments_pass_through_untouched():
    assert protocol.encode_args("queue_add", (7, True)) == [7, True]


def test_wrong_arity_is_rejected_before_it_reaches_the_bus():
    with pytest.raises(ValueError, match="takes 2 argument"):
        protocol.encode_args("queue_add", (7,))


def test_json_values_are_declared_as_strings_on_the_wire():
    assert protocol.dbus_signature("set_state") == "ss"
    assert protocol.dbus_signature("set_podcast_setting") == "uss"
    assert protocol.dbus_signature("report_position") == "uuub"


# ---------------------------------------------------------------- single writer


def _repo_calls(path: Path) -> set[str]:
    """Names called as repo.<name>(...) in one module."""
    tree = ast.parse(path.read_text())
    found = set()
    for node in ast.walk(tree):
        if (
            isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name)
            and node.func.value.id == "repo"
        ):
            found.add(node.func.attr)
    return found


@pytest.mark.parametrize("path", sorted(UI_DIR.glob("*.py")), ids=lambda p: p.name)
def test_the_window_never_writes_to_the_database(path):
    """Reads are direct and that's the point; writes belong to the daemon.

    Nothing enforces this at runtime — a stray write would just silently make
    the window a second writer — so it is enforced here instead.
    """
    offenders = _repo_calls(path) & REPO_WRITERS
    assert not offenders, (
        f"{path.name} writes to the database directly: {sorted(offenders)}. "
        "Route it through DaemonClient instead."
    )
