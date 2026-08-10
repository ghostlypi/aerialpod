"""Pairing secrets and the codes people type to share them.

The secret store is forced onto its file fallback here — a test must never
write to the developer's real keyring.
"""

from __future__ import annotations

import pytest

from aerialpod import secretstore
from aerialpod.lan import crypto, pairing


@pytest.fixture(autouse=True)
def isolated_store(tmp_path, monkeypatch):
    monkeypatch.setenv("AERIALPOD_DATA_DIR", str(tmp_path))
    monkeypatch.setattr(secretstore, "_collection", lambda: None)


# ---------------------------------------------------------------- secrets


def test_a_secret_is_generated_on_first_use():
    value = pairing.secret()
    assert len(value) == pairing.SECRET_LEN


def test_the_secret_is_stable_across_calls():
    assert pairing.secret() == pairing.secret()


def test_the_secret_is_stored_with_owner_only_permissions(tmp_path):
    pairing.secret()
    path = tmp_path / "lan-pairing.json"
    assert path.exists()
    assert path.stat().st_mode & 0o077 == 0  # no group or world access


def test_reset_replaces_the_secret():
    before = pairing.secret()
    after = pairing.reset()
    assert after != before
    assert pairing.secret() == after


def test_a_corrupt_stored_secret_is_replaced(tmp_path):
    (tmp_path / "lan-pairing.json").write_text('{"secret": "not-hex"}')
    assert len(pairing.secret()) == pairing.SECRET_LEN


# ---------------------------------------------------------------- codes


def test_code_round_trips():
    value = pairing.secret()
    assert pairing.parse_code(pairing.format_code(value)) == value


def test_code_is_grouped_for_reading_aloud():
    code = pairing.pairing_code()
    groups = code.split("-")
    assert len(groups) == 8
    assert all(len(g) == 4 for g in groups)


def test_pairing_adopts_the_other_devices_secret():
    theirs = pairing.reset()          # stand in for the other machine
    code = pairing.format_code(theirs)
    pairing.reset()                   # this device has its own, different one
    assert pairing.secret() != theirs

    pairing.pair_with_code(code)
    assert pairing.secret() == theirs


def test_paired_devices_derive_the_same_channel_key():
    code = pairing.pairing_code()
    theirs = crypto.channel_key(pairing.parse_code(code))
    assert theirs == pairing.channel_key()


@pytest.mark.parametrize("mangle", [
    str.lower,
    lambda c: c.replace("-", ""),
    lambda c: c.replace("-", " "),
    lambda c: f"  {c}\n",
])
def test_codes_survive_being_retyped(mangle):
    """However someone transcribes it, it should still pair."""
    code = pairing.pairing_code()
    assert pairing.parse_code(mangle(code)) == pairing.secret()


def test_digits_that_cannot_be_digits_are_read_as_letters():
    """0/1/8 aren't in the base32 alphabet, so they're unambiguous misreads."""
    value = pairing.parse_code("OIBO-OIBO-OIBO-OIBO-OIBO-OIBO-OIBO-OIBO")
    assert value == pairing.parse_code("0180-0180-0180-0180-0180-0180-0180-0180")


@pytest.mark.parametrize("bad,message", [
    ("", "Enter the pairing code"),
    ("ABCD-EFGH", "characters"),
    ("A" * 40, "characters"),
])
def test_bad_codes_explain_themselves(bad, message):
    with pytest.raises(ValueError, match=message):
        pairing.parse_code(bad)


def test_pairing_with_a_bad_code_changes_nothing():
    before = pairing.secret()
    with pytest.raises(ValueError):
        pairing.pair_with_code("nonsense")
    assert pairing.secret() == before


# ---------------------------------------------------------------- key derivation


def test_channel_key_is_full_length_and_secret_specific():
    a = crypto.channel_key(b"\x01" * pairing.SECRET_LEN)
    b = crypto.channel_key(b"\x02" * pairing.SECRET_LEN)
    assert len(a) == crypto.KEY_LEN
    assert a != b
    assert a == crypto.channel_key(b"\x01" * pairing.SECRET_LEN)
