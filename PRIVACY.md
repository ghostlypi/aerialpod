# AerialPod Privacy Policy

**Effective date:** 27 August 2026
**Applies to:** the AerialPod Android application (`org.aerialpod.android`)

## The short version

AerialPod has no accounts, no servers, and no analytics. The developer receives
nothing about you or your listening — not your subscriptions, not your
positions, not crash reports, not a count of installs beyond whatever Google
Play reports to every developer.

Your library lives on your device. It leaves only in ways you switch on
yourself, to destinations you choose, and you can switch each one off again.

## Who operates AerialPod

AerialPod is an open-source podcast player. There is no AerialPod service, no
AerialPod account, and no AerialPod server. Nothing in this app sends data to
the developer, because there is nowhere for it to be sent.

## What stays on your device

All of it, by default:

- Your subscriptions, episodes, queue, and playback positions, in a local
  database.
- Downloaded episode audio and cached artwork.
- Your settings.
- If you sign in to a podcast-sync account (below), those credentials —
  encrypted with a key held in the device's hardware-backed keystore, which
  never leaves the device.
- If you pair with another of your devices (below), a pairing secret, stored
  the same way.

None of this is transmitted anywhere unless a section below says otherwise.

## What leaves your device, and to whom

There are exactly three destinations, and you control two of them completely.

### 1. Podcast hosts — unavoidable, because that is what a podcast player does

To fetch a feed, artwork, or audio, your device connects directly to whatever
server that podcast is published on. Those servers are operated by the podcast
publishers and their hosting providers, not by AerialPod.

Like any HTTP client, those requests reveal your IP address, the time of the
request, what you asked for, and an identifying string —
`AerialPod/0.1 (+https://github.com/aerialpod)`. AerialPod does not add any
identifier of its own, does not send your other subscriptions, and does not
report back what you played.

This traffic is between you and the publisher. Their privacy practices are
theirs; AerialPod has no visibility into them and no relationship with them.

### 2. A podcast-sync account — optional, and you choose the server

AerialPod can sync with a [gpodder.net](https://gpodder.net)-compatible
account. **This is off until you sign in.** The server address is a field you
can edit, so you may point it at gpodder.net, at your own self-hosted instance,
or at nothing at all.

If you do sign in, AerialPod sends that server:

- Your username and password, to authenticate.
- Your podcast subscriptions (feed URLs), so they match across your devices.
- Episode actions — played, downloaded, and playback positions with timestamps.
- A device identifier this app generates, and a device name taken from your
  phone's manufacturer and model (for example, "Google Pixel 8"), so the
  service can tell your devices apart in its own interface.

That server's operator decides what they do with it. If you use gpodder.net,
their privacy policy governs. If you self-host, you are the operator.

**To stop:** sign out in Settings. Nothing further is sent. Removing data
already on that server is done through that service, not through this app.

### 3. Your own other devices, over your local network — optional, and per device

AerialPod can sync your library directly with another device running AerialPod
on the same network, with no server in between and nothing traversing the
internet.

This is off until you deliberately pair. Pairing requires the same code entered
on both devices, and you add each device's address yourself — nothing is
discovered, adopted, or trusted automatically.

When on:

- Traffic goes **directly** between your two devices. It does not pass through
  the developer or any third party.
- Every frame is encrypted with AES-GCM under per-direction session keys
  derived from your pairing code. A device without that code cannot read the
  traffic or complete the handshake.
- What is exchanged is your queue, playback positions, played state, and
  per-podcast settings.
- The Android app **never listens for incoming connections.** It only dials out
  to addresses you added. It cannot be connected to.

**To stop:** remove the paired device, or reset the pairing code, in Settings.

## What AerialPod never does

- No analytics, telemetry, or usage measurement of any kind.
- No advertising, and no advertising identifiers.
- No crash or diagnostic reporting to the developer.
- No third-party trackers or SDKs. The app's dependencies are Android system
  libraries, a media player, an HTTP client, and an image loader — none of
  which are configured to report anywhere.
- No selling or sharing of personal information, because none is collected.
- No profile of you, anywhere.

## Permissions, and why each exists

| Permission | Why |
|---|---|
| Internet, network state | Fetch feeds and audio; notice when the network changes |
| Local network access | Connect to *your own* device on your LAN for direct sync. Used for nothing else — no scanning for other devices, no location |
| Foreground service (media playback) | Keep playing when the app is not in front |
| Notifications | Show the playback controls |

Android may describe local network access in terms of finding "nearby devices".
AerialPod uses it only to open a connection to an address you typed in yourself.

## Your controls

- **Delete everything:** uninstall the app, or use Android's *Clear storage*.
  This removes the library, downloads, credentials, and pairing secret from the
  device. Nothing survives elsewhere, because there is nowhere else.
- **Stop account sync:** sign out in Settings.
- **Stop device sync:** unpair in Settings.
- **Delete downloads:** remove them in the app; streaming continues to work.

## Children

AerialPod is not directed at children and collects nothing from anyone,
including children.

## Changes

If this policy changes, the updated version will be published at the same
address, with a new effective date. Material changes affecting what leaves your
device will be noted in the app's release notes.

## Contact

FILL-IN-CONTACT

---

*AerialPod is open source. If you would rather verify than take this on trust,
the network behaviour described above is in `lan/`, `gpodder/`, and `feeds/` in
the source.*
