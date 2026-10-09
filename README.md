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
- **AI ad removal** — a machine with a GPU finds the ads in your queue with a
  local model, and every paired device skips them, phone included. Nothing
  leaves your own devices. See [below](#ai-ad-removal).
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
type is shown on the other machine in the same panel. Allow TCP port `47722`
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

## AI ad removal

One machine with a GPU finds the ads in your queued episodes, and every
paired device, phone included, skips them. Playback jumps past an ad as it
reaches one, with a few seconds to press **Undo** and hear it after all.
Seeking back into an ad you already passed plays it.

```sh
./install.sh ai                         # the detector's toolchain, in its own venv
systemctl --user restart aerialpod-daemon
```

That's it. The daemon works through the whole queue, one episode at a time,
and Settings → Ad removal shows how far it has got. A queued episode that
isn't downloaded is fetched for analysis and evicted again afterwards. On a
laptop, it waits for AC power unless you tell it otherwise.

**How it finds them.** Whisper transcribes the episode on the GPU, with a
time for every word. Then [SemIf](https://github.com/TheoLeeCJ/SemIf) asks
open models one question per stretch of transcript: is this line an ad that
interrupts the show? SemIf reads the answer straight off the model's logits in
a single forward pass, without generating text, so the timestamps come from
the transcript and are never parsed out of a model's prose.

It asks twice. Qwen3.5-9B scans the whole episode, ten-odd seconds at a time.
Then Qwen3.8-27B, a much stronger model, goes back over every stretch the
scan had any suspicion of, a sentence or two at a time, and decides where
each ad really starts and ends. On 19 hand-labelled ad breaks across eight
episodes, that took the share of ad speech skipped from 90% (the single small
model this replaced) to 98%, and halved the show wrongly cut, from 134 s to
64 s. No breaks were missed.

It's slow by design, since it runs while you sleep. On an RTX 5070 Laptop
(8 GB), a two-hour episode takes about 15 minutes. The 27B is a 16.5 GB model,
so it runs partly on the GPU and partly in system RAM; it wants about 20 GB of
free memory. The first run downloads about 40 GB of model weights from Hugging
Face. Machines without a CUDA GPU fall back to a single small model on the
CPU, which is much slower and less accurate. **Model** in Settings → Ad
removal switches to the faster single-model scan.

It's tuned to skip too little rather than too much. Host-read sponsor
segments and inserted commercials are caught. A TV-promo spot that is all
trailer audio and no call to action sometimes isn't. If it gets an episode
wrong, right-click it in the queue: **No ads here** clears it on every
device, and **Find ads again** reruns it.

**Getting it to your phone.** Most podcast hosts splice ads in when you
download, so a copy of an episode fetched at another time has different ads,
of different lengths. So the machine that analysed an episode keeps that exact
file while the episode is queued. When your phone downloads an episode, it
takes that copy from the computer over the paired, encrypted sync connection,
checks its SHA-256, and only goes to the podcast's server if the computer
can't be reached. An episode the phone had already downloaded from the server
is swapped for the analysed copy, as long as you haven't started it yet.

When the phone does end up with a different copy, the ads are still found in
it. The show's own audio is byte-for-byte the same in every copy, just
shifted, so with each ad the detector records a few short runs of the show's
bytes from either side of it, and the phone finds those in its own file and
moves each ad's edges to match. A stream has no file to search, so ads in a
stream are only skipped if it is the same length as the analysed copy.

Results travel over [device sync](#syncing-your-own-machines-settings--device-sync)
like everything else. Nothing is sent to anyone but your own devices, and the
transcript never leaves the machine that did the analysis. Keeping analysed
episodes costs that machine some disk: about 100–170 MB per queued episode,
freed as you play them. The
Flatpak can't run the detector (its sandbox can't reach the toolchain), but it
skips ads that another device found.

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

## Install (Flatpak — x86_64 and ARM, e.g. Steam Frame)

```sh
packaging/flatpak/build.sh --bundle                 # this machine's arch
packaging/flatpak/build.sh --arch aarch64 --bundle  # ARM, needs qemu-user-static
flatpak install --user aerialpod-aarch64.flatpak    # on the target device
```

The **Flatpak** workflow in GitHub Actions builds both bundles natively on
tag pushes. The bundle pulls the KDE runtime and PySide6 from Flathub when
installed. Data lives in `~/.var/app/io.github.ghostlypi.AerialPod/`, separate
from a native install.

Inside the sandbox the daemon is `io.github.ghostlypi.AerialPod.Daemon` (flatpak
only lets an app own names under its ID). It is D-Bus activated like the native
one, and asks the Background portal to autostart it with the session in place of
the systemd unit. On the very first install the session bus may not know about
the new service until you log out and back in. Until then the window runs sync
in-process, as with `--no-daemon`.

After changing `pyproject.toml` dependencies, regenerate the per-arch wheel
list with `packaging/flatpak/gen-python-deps.py` and commit
`python3-deps.json`.

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
- **Flathub** — the long-term channel for non-Fedora Linux users (manifest and metainfo are in `packaging/flatpak/`; submission pending)
