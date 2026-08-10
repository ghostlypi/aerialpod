# AerialPod

A desktop podcast player for Linux (Fedora/GNOME first) that syncs with
**gpodder.net** and plays nicely with **AntennaPod** on your phone.

- **Derived queue with manual override** — your queue is rebuilt from synced
  gpodder episode actions (in-progress episodes surface at the top), and your
  local pins/reordering/removals are respected across syncs.
- **Exact playback-position sync** both ways (pause on desktop, resume on phone).
- **Device-to-device sync** for the state gpodder.net can't carry — queue order,
  pins and removals — between your own machines, over a LAN *or a VPN*.
- **Only the next queue item is downloaded** (configurable); everything else streams.
- **In-app playback** (Qt6 Multimedia) with runtime audio-device switching —
  follow the system default or pin a device, no restart needed.
- **MPRIS2** integration: GNOME media keys and the shell media widget.
- AntennaPod-style customization: themes/accents (follows GNOME dark mode),
  per-podcast speed/skip/auto-queue settings, configurable home sections.
- **Daily shows can claim the top slot** — set a podcast's *New episodes* to
  *Add to top of queue* and each morning's episode lands directly under
  whatever is playing, instead of behind your backlog.

> Note: the gpodder protocol has no queue concept and AntennaPod never uploads
> its queue order, so the queue is *derived* from episode actions — see
> [AntennaPod#6036](https://github.com/AntennaPod/AntennaPod/issues/6036).

## Syncing your own machines (Settings → Device sync)

gpodder.net is the bridge to your phone, but it can only carry episode
actions. Everything the desktop knows about your *queue* — the order you
dragged episodes into, what you pinned, what you threw out — has nowhere to
live on the server, so a laptop and a desktop could never agree on it.

Device sync closes that gap directly. Pair two AerialPod installs once and
they find each other from then on, and exchange:

- queue order, pins, and removals (as decisions, not as a derived list);
- per-podcast settings — speed, skip intro/outro, auto-queue, custom title;
- playback position, pushed live — pause on the desktop, pick it up on the
  laptop seconds later rather than at the next 30-minute gpodder cycle.

Episode played/new state is deliberately *not* sent: gpodder.net already
syncs it, and having both paths write it would just be a race.

Conflicts resolve last-writer-wins per record, with ties broken on device id
so both machines always reach the same answer. Reordering on one machine
carries that order to the other; an episode queued independently on the second
machine survives the merge.

### Pairing

Each install generates its own key on first run and shows it as a code:

```
Settings → Device sync → This device's code → Show / Copy
```

On the second machine, paste that code into **Pair with a device** and press
Pair. Both ends now share one key; a third device pairs against the code shown
on either of them. The code is case-insensitive and the dashes are optional,
so it survives being read down the phone.

**New code** rolls the key over — useful if a code leaked, or to cut a machine
you no longer own out of the mesh. Every other device has to be paired again
with the new code.

Pairing is independent of gpodder.net: device sync works with no account
configured at all, and having the same account configured does not by itself
pair anything.

### Over WireGuard

Discovery is unicast only — no mDNS, no broadcast, since neither crosses a
WireGuard tunnel. Peers are found by, in order: ones seen before at their last
address, addresses you added by hand, then a bounded probe of the subnets this
machine is on (one port each, `/22` and smaller only). A tunnel that gives the
interface a normal `10.0.0.2/24` is therefore discovered automatically.

If your WireGuard config assigns a bare `/32` there is no subnet to probe: add
the peer's tunnel address by hand in Settings → Device sync. The address to
type is shown on the other machine in the same panel. Allow TCP port `47741`
between the peers.

### What's on the wire

The pairing code is 160 random bits, stretched with HKDF into the key both
ends authenticate with. Connecting runs a mutual HMAC challenge over fresh
nonces, client first — so merely connecting to the port yields nothing but a
random nonce. The session is then AES-GCM encrypted with per-direction counter
nonces, which makes frames non-replayable, including across sessions.

The handshake is necessarily plaintext, since no session key exists yet, and a
captured transcript is therefore a *verifier* — anyone who records one, or who
answers on a peer's address to collect one, can test candidate keys against it
offline without touching the network again. This is why the key is random
rather than derived from your gpodder.net password: there is no candidate list
to search, so the offline attack has nothing to chew on. (An earlier design
did derive it from the account password, which would have put that password
within reach of anyone who could observe a sync.)

What this still assumes: the pairing code itself is the whole secret. Anyone
who gets a look at it can sync with you, so treat it like a password — and if
one leaks, press **New code**. The keys live in the GNOME keyring, falling
back to a `0600` file where there is no Secret Service.

## The background service

Sync only helps if it happens. AerialPod runs its sync work in a small daemon
that starts with your session, so gpodder sync, feed refresh, downloads and the
peer mesh keep going with the window closed — and, importantly, the desktop
stays *reachable* as a peer instead of only while you happen to be looking at
it.

```sh
systemctl --user status aerialpod-daemon     # is it running?
journalctl --user -u aerialpod-daemon -f     # what is it doing?
systemctl --user restart aerialpod-daemon    # after changing something by hand
```

`install.sh` enables it for you. The window connects over D-Bus
(`org.aerialpod.Daemon`), and because the service is D-Bus activated, launching
AerialPod starts the daemon if it isn't already up.

Playback stays in the window: closing it stops the audio, as it always did.
Everything else moves. The window reads the database directly and sends
commands for anything that changes it, so the daemon is the only writer.

**If there is no daemon** — macOS, no session bus, service disabled, or you ran
`aerialpod --no-daemon` — the app runs those services inside the window
instead, exactly as it did before the split. Nothing is lost except continuity
while the window is closed.

## Install (Fedora / GNOME — any machine)

```sh
git clone <this repo> && cd aerialpod
./install.sh
```

That installs the app (via pipx if present, else an isolated venv in
`~/.local/opt/aerialpod`), a launcher entry, and the icon — AerialPod then
appears in the GNOME overview like any other app. Re-run `./install.sh` to
update; `./install.sh uninstall` removes it.

**Custom icon:** drop a square PNG (256×256 or 512×512) at
`data/icons/aerialpod.png` and re-run `./install.sh` — it takes priority
over the bundled SVG.

## Install (macOS — experimental)

```sh
pip3 install .
aerialpod
```

Everything works except the Linux-only integrations: no MPRIS (media keys /
system media widget) and credentials fall back to a `chmod 600` file instead
of a keyring. Qt is cross-platform, so the UI, playback, sync, and queue are
identical.

## Development

```sh
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/aerialpod -v            # run
.venv/bin/pytest                  # tests
```

Useful flags: `--dry-run-sync` logs gpodder uploads instead of POSTing them.

## Distribution roadmap

- **COPR RPM** (Fedora-native, can use the distro's `python3-pyside6`) — next step when the repo gets a public remote
- **Flathub** — the long-term channel for non-Fedora Linux users (needs AppStream metainfo + the flatpak manifest)
