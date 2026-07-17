# AerialPod

A desktop podcast player for Linux (Fedora/GNOME first) that syncs with
**gpodder.net** and plays nicely with **AntennaPod** on your phone.

- **Derived queue with manual override** — your queue is rebuilt from synced
  gpodder episode actions (in-progress episodes surface at the top), and your
  local pins/reordering/removals are respected across syncs.
- **Exact playback-position sync** both ways (pause on desktop, resume on phone).
- **Only the next queue item is downloaded** (configurable); everything else streams.
- **In-app playback** (Qt6 Multimedia) with runtime audio-device switching —
  follow the system default or pin a device, no restart needed.
- **MPRIS2** integration: GNOME media keys and the shell media widget.
- AntennaPod-style customization: themes/accents (follows GNOME dark mode),
  per-podcast speed/skip/auto-queue settings, configurable home sections.

> Note: the gpodder protocol has no queue concept and AntennaPod never uploads
> its queue order, so the queue is *derived* from episode actions — see
> [AntennaPod#6036](https://github.com/AntennaPod/AntennaPod/issues/6036).

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
