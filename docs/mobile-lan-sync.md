# Device sync on Android and iOS

The mobile apps join the existing peer mesh as **dial-out-only peers**. They
speak the same wire protocol as the desktop, byte for byte, and require no
change to `lan/crypto.py`, `lan/protocol.py` or the snapshot format in
`lan/state.py`.

## Why a phone never listens

`Channel`'s role — client or server — decides only who speaks first during the
handshake. Once `_establish()` runs, both ends hold a `Sealer` with keys for
both directions, `PeerLink.messageReceived` fires regardless of role, and
`_on_link_ready` sends a snapshot from *both* sides.

So one outbound TCP connection carries the full conversation: the phone pushes
its positions and queue intents up it, and receives the desktop's down it. A
listening socket on the phone would buy nothing.

That also removes the two things that make LAN sync hostile on mobile:

- **No subnet sweep.** `discovery.sweep()` knocks on every host in the local
  /22. On iOS that reads as port-scanning and burns battery; on both platforms
  it is unnecessary, because the desktop is a stable, remembered address.
- **No inbound reachability requirement.** Nothing has to survive the app being
  suspended in order for the *next* session to work.

The desktop keeps listening exactly as it does today. It is the rendezvous
point; two phones sync through it rather than to each other.

## Sync triggers

The desktop pushes a position every 5 seconds while playing
(`LanScheduler.POSITION_THROTTLE_MS`). Mobile does not: it syncs on **transport
events and connection events only**, which is both what the handoff actually
needs and what the radio can afford.

| Event | Action |
|---|---|
| Network path becomes syncable | Dial remembered peers |
| App foregrounded on a syncable path | Dial remembered peers |
| Playback starts and no link is up | Dial remembered peers |
| Link established | Snapshot exchange — automatic in `_on_link_ready` |
| Play / pause / seek / stop / episode change | `flush_now()` — immediate position push |
| Queue edit (add, remove, reorder, pin) | `push_snapshot_soon()` — 2 s debounce |
| Playback tick (~1 Hz) | **nothing** — the desktop's 5 s heartbeat is dropped |
| Dial fails | Exponential backoff, reset on path change or foreground |
| Backgrounded and not playing | Close the link |

On the desktop these fire from `hub.py`: `final=True` in the position handler
calls `flush_now()` (`hub.py:282`), and `queue.intentChanged` is wired to
`push_snapshot_soon()` (`hub.py:117`). Mobile wires the same two call sites and
simply omits the `note_position()` heartbeat between them.

**No periodic resync.** The desktop re-broadcasts every 5 minutes
(`RESYNC_INTERVAL_MS`) as a safety net. Mobile does not need one: a dropped or
reordered frame fails `Sealer.open()` with `InvalidTag`, which is fatal to the
channel by design, so the connection dies and the next dial begins with a full
snapshot exchange. Reconnection *is* the safety net.

## Network changes: dial on any of them

Any path change triggers a dial attempt. There is deliberately no metered- or
WiFi-only gate, because the gate would buy nothing:

- On cellular without a tunnel the dial **fails outright** — carriers do not
  route RFC1918, so a connect to `192.168.1.x` dies immediately. The cost is one
  TCP SYN; no snapshot is ever exchanged.
- The only way a peer address *is* reachable over cellular is a WireGuard
  tunnel — a documented feature, and a case where the user wants the sync.

A metered check would therefore have added a settings toggle and two
platform-specific capability APIs in exchange for breaking the tunnel case.

Two guards remain:

- **Debounce path callbacks by ~2 s.** One WiFi association fires several in a
  row (associate, DHCP, route install); coalesce them into a single dial.
- **Exponential backoff on repeated failure**, reset on foreground or path
  change. `_connect_to()` already skips peers with an established link, so a
  flapping network cannot stack duplicate connections.

Do not try to identify the *home* network: reading the SSID requires location
permission on both platforms, which is a bad trade. Dial the remembered
addresses on every path change and let failure be cheap.

Since the subnet sweep is gone, "a syncable device is detected" simply means a
remembered address answered the dial and completed the handshake.

## Snapshot size

Measured on a synthetic 10,000-episode library (50 podcasts, 1,000 in-progress
positions, 300 live intents):

    snapshot JSON        365 KiB
    connect exchange     730 KiB   (both ends send one)
    position push        261 bytes

The README's "a few kilobytes per sync" holds for a small library, not a large
one. Two consequences:

- A **connect** is cheap enough to do on every network change, but not cheap
  enough to do on a timer — which is why mobile drops `RESYNC_INTERVAL_MS`.
- **Live position pushes are the cheap path** at 261 bytes. Transport-event
  syncing costs essentially nothing; it is the snapshot exchange that has
  weight, and that happens once per connection.

## What the mobile peer implements

Keep, from `lan/service.py`:

- `_connect_known_peers()` — dial remembered peers, and any manually added
  address (the WireGuard `/32` case).
- The ident exchange, and `repo.remember_peer()` on success.
- `LanScheduler.SNAPSHOT_DEBOUNCE_MS = 2000`. Tuned; do not re-derive.
- `plain_address()` — the `::ffff:` normalisation still applies.

Omit: `QTcpServer` and `_on_incoming`, `discover_now()` / `_sweep_worker()`,
`_preferred()` (no simultaneous dial is possible when only one side dials),
`RESYNC_INTERVAL_MS`, `RETRY_INTERVAL_MS` (replaced by path callbacks), and
`POSITION_THROTTLE_MS` (no heartbeat).

Add, per platform: the path monitor above, and a re-dial on foreground.

## Process lifetime

What works depends on whether the OS is keeping the app alive:

| Phone state | Alive? | LAN sync |
|---|---|---|
| Playing audio, screen off | Yes — `UIBackgroundModes: audio` / foreground media service | Pause/stop pushes land live |
| App in foreground | Yes | Full sync |
| Closed, not playing | No | Reconnects on next open; gpodder covers the gap |

The third row is not a regression: `reconcile()` derives the queue from gpodder
episode actions whether or not a peer was ever reached, so a phone that has not
synced over LAN in days still opens to a correct queue. LAN sync raises the
resolution — exact position, queue order, pins — it is not load-bearing for
correctness.

## Permissions

- **iOS** — `NSLocalNetworkUsageDescription` is required to connect to a LAN
  address. With the sweep gone this is a single prompt with an honest
  justification ("AerialPod connects to your computer to sync your queue").
  No multicast entitlement is needed, since there is no Bonjour or broadcast.
  **Fire the first dial from the pairing screen**, not from an automatic
  connect at first launch, so the prompt arrives with visible context.
- **Android** — no permission beyond `INTERNET`. A foreground service with
  `mediaPlayback` type is needed for background audio, which is also what keeps
  the peer link alive while playing.
