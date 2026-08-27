# AerialPod mobile — build checklist

Android first, iOS to follow. The shared core is Kotlin Multiplatform; only the
UI, the player and the platform callbacks are written twice.

Design reference: [`docs/mobile-lan-sync.md`](../docs/mobile-lan-sync.md) — the
spec for the peer half. The rest of the app mirrors the desktop's `src/aerialpod`.

**Status:** the Android app is feature-complete against this checklist —
steps 1–5 and all of step 6, with no carry-overs left open. **287 tests green**, plus two integration suites
against a live desktop. **M1 and M2 are met on a device**, audio plays with a
media notification and a sleep timer, and downloads keep the queue head on disk.
What is left is **M3** (a pause on the phone reaching the desktop in seconds —
now just a matter of running it) and **iOS**.

---

## Ground rules

Two that have already paid for themselves and should hold for the rest of it:

- **Interop is pinned to the desktop, not to ourselves.** Anything that crosses
  the wire gets vectors generated from the Python implementation by
  `mobile/tools/gen_lan_vectors.py`. A Kotlin-only test agrees with itself and
  proves nothing — dropping one `@SerialName` broke three interop tests and
  none of the 25 merge tests.
- **The schema does not fork.** `*.sq` mirrors `db/migrations.py`, so every
  query ported from `repo.py` is a copy rather than a translation.

## Integration suites

Both are opt-in — they need a live daemon and a corpus, so they stay out of the
normal run. Both write nothing to the desktop except the one `lan_peers` row the
daemon records on a successful handshake.

```sh
# The peer protocol against a running aerialpod-daemon.
# Extract the pairing key to a 0600 file first (never echo it), and delete it after.
AERIALPOD_IT=1 AERIALPOD_IT_PORT=47722 AERIALPOD_IT_SECRET=/path/to/key \
  ./gradlew :shared:jvmTest --tests '*DesktopInteropIT*' --rerun-tasks

# The feed parser against a snapshot of the real subscriptions, compared with
# feedparser on identical bytes.
AERIALPOD_IT_FEEDS=/path/to/corpus \
  ./gradlew :shared:jvmTest --tests '*RealFeedsIT*' --rerun-tasks
```

---

## Done

### Foundation
- [x] KMP Gradle project — Kotlin 2.4.10, AGP 9.3.2, Gradle 9.7.1, compileSdk 37, minSdk 30
- [x] Targets: `androidTarget` + `jvm`, iOS declared behind a Mac-host check
- [x] `jvmShared` source set so Android and JVM share the JDK actuals
- [x] SQLDelight schema mirroring `migrations.py` through migration 3
- [x] Driver factories — Android (keystore-free, FK pragma in `onConfigure`) and JVM

### Step 1 — Peer transport
- [x] `Crypto.kt` — HKDF, HMAC proofs, `Sealer`; three `expect` primitives only
- [x] `PeerChannel.kt` — handshake + framing state machine, no socket knowledge
- [x] `Pairing.kt` — base32 codec and the desktop's forgiving parse
- [x] `PeerConnection.kt` — one outbound ktor-network socket, handshake deadline
- [x] `LanPeerService.kt` — the doc's trigger table, one function per row
- [x] Deliberate omissions documented: no listener, no sweep, no `_preferred()`,
      no `RETRY`/`RESYNC`/`POSITION_THROTTLE` timers

### Step 2 — Snapshot build/merge
- [x] `Snapshot.kt` — wire records, `@SerialName` matched to the Python keys
- [x] `SnapshotSync.kt` — build, merge, live position push, `replicatedVersion`
- [x] Resolution before the transaction (alias writes must not roll back)
- [x] Peer stamps preserved on merge (no re-stamping with our clock)
- [x] `finished` on the position record — "played" crossing the wire
- [x] `UrlMatching.kt` / `Matcher.kt` — tracker ladder, GUID-first resolution

### Verification
- [x] 12 crypto/protocol interop tests vs. Python-generated vectors
- [x] 25 merge tests mirroring `tests/test_lan_merge.py` case for case
- [x] 5 wire-format tests vs. a desktop-built snapshot
- [x] Mutation-checked: a wrong `@SerialName` fails interop, not merge
- [x] 35 queue tests mirroring `tests/test_queue_reconcile.py`
- [x] 6 tests on the merge/reconcile seam — a peer's order, exclusions and
      positions surviving the derived rebuild
- [x] `iso8601Utc` pinned to the desktop's `strftime` output
- [x] 14 matching tests mirroring `tests/test_matching.py` — the tracker ladder
- [x] 12 action tests mirroring `tests/test_sync_actions.py`
- [x] 15 sync-cycle tests against a mock gpodder.net (call order, cursors,
      retries, dry run)
- [x] Mutation-checked: a shared device id and a leaky dry run both fail
- [x] 21 XML tests — one per thing a real feed does that breaks a strict parser
- [x] 14 feed-parser tests, dates cross-checked against Python
- [x] 11 fetcher tests (conditional GET, first fetch, aliasing, COALESCE)
- [x] 8 OPML tests including an export→import round trip
- [x] Mutation-checked: dropping an unknown entity, and popping a stray end tag
      blindly, both fail
- [x] **Live interop against a running `aerialpod-daemon`** — handshake, ident,
      and a real snapshot (326 positions, 17 intents, 1 setting) all resolved and
      merged, nothing unresolvable, idempotent on re-merge
- [x] **Parser vs. feedparser on 11 real subscriptions** — 3830/3830 episodes,
      same set and same order, every publication date read, 15 MiB in 52 ms

---

**Why `finished` exists.** A position cannot say an episode is done. One
abandoned twenty minutes into an hour and then marked played is, to a receiver
that sees only the number, indistinguishable from one still in progress — so it
lands back in that device's queue. Measured against a real library: a phone
showed 10 queue items against the desktop's 3, and every extra one had a
replicated position and a "N min left" that the desktop knew was finished.

Three parts, because the first two were each inert on their own:

1. **The flag.** `finished` rides on the position record, defaulted so it is
   wire-compatible both ways. **Only `true` is acted on** — a peer that predates
   the field omits it, and reading that absence as "unplayed" would wipe played
   state across the mesh.
2. **A stamp to carry it.** A record is only applied when it is newer than what
   the receiver holds, and marking played never moved `position_updated_at`. So
   the news existed but could never be delivered. Marking played now bumps it.
3. **Played with no position.** `_build_positions` filtered on
   `position_secs > 0`, so an episode marked played *without listening* was not
   in the snapshot at all. Now included, and the receiver treats the state as
   the whole message when there is no position to resume from.

Verified by merging the real desktop's snapshot into a copy of a real phone
database: **12 queue items became 3, matching the desktop exactly.**

## Step 3 — Repository + queue reconcile

Done. `AerialPodCore` is the composition root where the queue meets the mesh in
both directions — a local edit debounces a snapshot out, and a peer's merge
reconciles the derived queue back in.

- [x] Port `repo.DEFAULTS` and the typed `app_state` accessors
- [x] Per-key settings write (read-modify-write; `upsertAllSettings` overwrites
      every column, which is right for a merge and wrong for a local edit)
- [x] `effectiveAutoAdd`, `effectiveQueuePosition`, `effectiveSpeed`, `displayTitle`
- [x] `isFinished()` — including the AntennaPod `total <= 0` guard
- [x] Queue user ops: `add`, `remove`, `toggle`, `move`, `pin`, `releaseToAuto`
- [x] `markPlayedAndAdvance`, `markUnplayed` (both enqueue gpodder actions)
- [x] `reconcile()` — removal pass, head block, floaters, front/back insertion
- [x] A row the user placed does not float — 4 tests, both mutations caught
- [x] Every user op records intent (a pin that skips this never reaches a peer)
- [x] Wire intent changes to `LanPeerService.onQueueEdited()`
- [x] Tests mirroring `tests/test_queue_reconcile.py`

**A manual reorder used to be undone by the next reconcile.** Reported from
real use as "reordering on the phone does not reach the desktop" — but the
intents replicated perfectly; the receiving reconcile put the queue back. An
in-progress episode floats to the top, and the episode a user most wants to move
is usually the one they are partway through, so moving it down was impossible on
either device.

The code contradicted its own comment, which already said *"**Unpinned**
in-queue rows float too"* while checking nothing. `move()` pins the row it
moves, and `add()` pins too — so honouring the flag means **anything the user
placed stays put**, while auto-added episodes still float. That is a semantic
choice, not only a fix, and it is the one the comment always described.

## Step 4 — gpodder.net

Done. **Decision settled: the phone registers its own device id.** The
subscription endpoint returns the diff *for a device*, so a feed added under a
shared id would be recorded against that device and the desktop asking the same
id would never be told — the server already believes it has it. Two independent
clients on one id lose each other's subscription changes. Episode actions are
account-wide and unaffected either way.

- [x] Ktor client: login, device register, subscription diff, episode actions
- [x] Port `sync.py`: pull → match → apply → push outbox → record timestamps
- [x] Outbox and `unmatched_actions` logging
- [x] Credential storage (`GpodderCredentialStore`, alongside `SecretStore`)
- [x] Handle `update_urls` feed rewrites
- [x] Dry-run mode equivalent to `--dry-run-sync`
- [x] Tests mirroring `tests/test_sync_actions.py` and `tests/test_matching.py`
- [x] **First sync on an empty device** — 4 tests, both mutations caught
- [x] Own gpodder device id, plus best-effort `/api/2/sync-devices` linking

**A bug the first real sign-in found.** The cycle pulled episode actions
*before* subscriptions, and the episodes those actions name do not exist until
the feeds are fetched — which happens after the cycle, from
`subscriptionsAdded`. So signing in on a new device threw away the account's
entire listening history on the one sync meant to bring it over, advanced the
cursor past it, and never asked again. The phone then believed nothing had been
played and queued one episode per subscription.

Rare on the desktop, which grows its library over time; the ordinary case on a
phone. Fixed by pulling subscriptions first and **holding the action cursor
back** while the library is still arriving, so the next sync — triggered once
the feeds land — re-pulls the same window against a library that exists.

**The desktop has the same ordering** (`gpodder/sync.py`, `sync_now`) and the
same latent loss on a first sign-in. Not changed here: it is a shared-behaviour
decision, and an established install never hits it.

## Step 5 — Feeds

Done. **Decision settled: a hand-rolled tolerant parser**, no dependency. The
constraint was never spec compliance — it is that the desktop parses with
feedparser, which recovers from almost anything. A stricter parser on the phone
does not produce an error; it produces *fewer episodes* from the same feed,
which reads as sync being broken.

- [x] Hand-rolled tolerant XML reader (`Xml.kt`) — bare `&`, CDATA, BOM,
      stray/unclosed tags, namespace prefixes, every attribute quoting style
- [x] Conditional fetch with `etag` / `http_last_modified`
- [x] RSS + Atom parse to the episode columns
- [x] Episode upsert keyed on `(podcast_id, guid)`, URL as the fallback identity
- [x] Changed enclosure URLs aliased so gpodder actions keep resolving
- [x] First-fetch back catalogue archived except the newest episode
- [x] New episodes reach the queue through `reconcile()`, honouring
      `auto_queue_position`
- [x] OPML import/export, round-tripping
- [x] Feed dates pinned to feedparser's readings (RFC 822 and RFC 3339)

## Step 6 — Android app

Nothing in `androidApp/` exists yet — the module is declared in
`settings.gradle.kts` but has no build script or sources (harmless; Gradle
lists an empty module and it contributes nothing).

Five independently pickable parts. **6.1 has to come first** — everything else
needs the module and the wiring to exist. After that the order is free, with one
exception worth knowing: **6.1 + 6.4 is the smallest thing that reaches M1 and
M2**, because those are what let the peer service actually be started and fed
path and lifecycle events. Media3 (6.3) is only needed for M3.

### 6.1 — Shell — done
- [x] `androidApp` module: build script, manifest, application class, and the
      desktop's own icon (`data/icons/aerialpod.png`) as the adaptive icon
- [x] Permissions: `INTERNET`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`
      (plus `ACCESS_NETWORK_STATE`, which 6.4's `NetworkCallback` needs, and the
      base `FOREGROUND_SERVICE` that the typed one requires)
- [x] DI/wiring: database, repo, sync, peer service as process singletons
- [x] Compose navigation skeleton + theming (accent, dark mode)

`AppGraph` is a hand-written service locator, not a DI container: `AerialPodCore`
already assembles the core, so the app supplies only the four things it cannot
know — the database file, the HTTP engine, where secrets live, and the device
caption. Everything is `lazy`; constructing the graph touches no disk.

**What is deliberately not started:** `AerialPodCore.start()`. The peer service
runs off path and lifecycle callbacks that nothing yet delivers, so a mesh
started here would connect once and never reconnect — worse than one that is
honestly off. 6.4 starts it.

One carry-over from this step, **since closed by 6.4**: the secret and
credential stores were app-private preferences rather than keystore-wrapped.
Both are now sealed by a key that cannot leave the hardware, and each migrates
the plaintext this step wrote before deleting it — with 11 tests and all 5
mutations caught, because a migration that goes wrong presents as a device that
quietly unpairs or an account that quietly signs out, and the user has nothing
to report.

The remaining note from this step:
- `allowBackup="false"`, with a matching `data_extraction_rules.xml`. The
  pairing secret and `lan_device_id` must not be cloned onto a second device —
  the mesh's last-writer-wins tie-break assumes that id is unique, so a restore
  producing two phones with one identity would misbehave quietly. The library
  itself rebuilds from gpodder or a peer, which is M4's claim anyway.

### 6.1a — Toolchain: move to AGP 9 — done
- [x] Gradle 8.14.5 → 9.7.1, AGP 8.13.2 → 9.3.2
- [x] Unpinned: `compose-bom` 2026.08.00, `navigation` 2.10.0, compileSdk and
      targetSdk 37

Four things had to change, none of them cosmetic:

- **The Kotlin Android plugin is gone.** AGP 9 has built-in Kotlin and refuses
  to start next to `org.jetbrains.kotlin.android`. `src/main/kotlin` is picked
  up on its own now, so the `srcDir` calls went too.
- **`com.android.library` will not sit next to Kotlin Multiplatform.** The
  shared module moved to `com.android.kotlin.multiplatform.library`, and its
  Android configuration moved from a top-level `android { }` block into
  `kotlin { android { } }`. Target and task names changed with it —
  `compileDebugKotlinAndroid` is now `compileAndroidMain`.
- **`withAndroidTarget()` in the hierarchy template no longer matches**, since
  it only recognises the old `androidTarget()`. The `jvmShared` group is now
  selected on platform type (`jvm` or `androidJvm`), which holds either way.
- **`kotlin("test")` came from the Kotlin plugin**, so the app's test dependency
  is now an explicit `kotlin-test-junit`.

Two things worth knowing afterwards:

- **Lint actually works now.** Under AGP 8.13 it bundled Kotlin 2.2, could not
  read this project's 2.4 metadata, emitted 14 `e:` lines and analysed the
  Kotlin sources incompletely. That is gone, and lint is at **zero issues**.
- **The build no longer needs the NDK.** AGP 9 wants to run every `.so` through
  `llvm-strip`, including ones that arrive prebuilt inside somebody else's AAR
  (`androidx.graphics:graphics-path`). This project has no native code, so
  `jniLibs.keepDebugSymbols += "**/*.so"` keeps them as shipped. That also
  sidesteps the partially-unpacked NDK on this machine, whose `bin/` has
  `llvm-objcopy` but not the `llvm-strip` hardlink of it.

### 6.2 — Playback — done
- [x] Media3 `MediaSessionService` + ExoPlayer, `mediaPlayback` foreground type
- [x] Position writes on tick; `onTransportEvent` on play, pause, seek and change
- [x] Per-podcast speed and skip intro/outro
- [x] Skip forward/back lengths, on the same `app_state` keys the desktop uses,
      with the player's icons following the setting rather than a fixed 30/10 —
      9 tests, because the setting crosses three pieces that each choose their
      own encoding, and a disagreement between them looks from the sofa exactly
      like a chip that ignored the tap. All four mutations caught
- [x] Sleep timer (minutes, or end of episode)
- [x] Notification and lock-screen controls
- [x] `reportPosition` ported from `hub.report_position`, with 6 tests

The service exists so playback survives backgrounding — which is also what keeps
the peer link alive, and why `onBackgrounded(isPlaying = true)` leaves it open.
`PlaybackCoordinator` holds everything that has to happen *around* the player,
in the service rather than the UI, because all of it must keep working with the
screen off.

The `final` flag is the whole design, kept from the desktop: every report
persists, only a final one enqueues a gpodder action. Mobile additionally drops
the desktop's per-tick nudge to peers — the trigger table lists a playback tick
as **nothing** — so the mesh only hears about playback on transport events.

Two things worth knowing:

- **ExoPlayer and `MediaController` are thread-confined.** Every read of
  `currentPosition` and every command has to be on the thread the player was
  built on; only the database work moves off it. Getting this wrong throws at
  runtime, not at compile time.
- **Speed is written through to the podcast**, not held in the player. Setting
  it from the player screen is a per-podcast setting on the desktop too, and it
  replicates — verified on the device as
  `('Morning Somewhere', 2.0, <this device's id>)`.

Verified on a device against a real feed: `state=PLAYING`, position and total
persisted every 5 s with **no** outbox action, then one `('play', 45, 45, 1275)`
on pause.

### 6.3 — Screens — done
- [x] Home (configurable sections, with the queue section reorderable too),
      Queue (drag reorder), Inbox
- [x] Subscriptions, Podcast detail, Episode detail
- [x] Settings — gpodder account, and **Device sync**: pairing code, code entry,
      manual peer address, live peer list from `LanPeerService.peers`
- [x] Add a feed by URL, and OPML import/export through the storage picker
- [x] 8 formatter tests — show notes, durations, remaining time, dates

Every screen reads from `Library`, a flow-based read model over the same
queries. SQLDelight notifies on the tables a query touches, so a queue edit, a
feed refresh, a gpodder pull **or a peer's snapshot landing** repaints whatever
is on screen with nothing joining the two by hand.

The queue's drag reorder is optimistic: the visible order changes as an item
crosses a neighbour, but `queue.move()` is called once, on drop. Per-crossing
would record an intent, push a debounced snapshot to every peer and reconcile
the whole queue every few pixels of finger movement.

**A real bug this found.** Subscribing to a podcast left the queue empty.
`fetchAndStore` reports **0** new episodes on a first fetch — the back catalogue
is archived rather than counted — so `refreshFeeds`' `if (fresh > 0) reconcile()`
guard never fired, and the one episode left `new` never reached the queue.
Nothing errored; 3830 episodes stored correctly and the queue simply stayed
empty. The desktop reconciles unconditionally (`ipc/hub.py`,
`_on_refresh_finished`); the core does now too, with five regression tests that
all fail if the guard comes back.

### 6.4 — Platform wiring — done
- [x] `ConnectivityManager.NetworkCallback` → `onPathChanged()`
- [x] `ProcessLifecycleOwner` → `onForegrounded()` / `onBackgrounded(isPlaying)`
- [x] Playback start → `onPlaybackStarted()` — the seam is built and tested;
      nothing calls it until 6.2 has a player
- [x] Keystore-backed `SecretStore` **and** credential store, migrating the
      plaintext values an earlier build wrote and then deleting them
- [x] First dial fired from the pairing screen, not at launch
- [x] 7 trigger tests, all 3 mutations caught

`NetworkMonitor` reports only changes that could alter reachability — a new
default network, validation flipping true, or link addresses changing (DHCP
completing is what actually makes a LAN address reachable). Forwarding every
`onCapabilitiesChanged` would fire on things like signal strength and hold the
service's 2 s debounce window permanently open.

There is **no metered or WiFi-only gate**, per the doc: on cellular the dial
fails outright because carriers do not route RFC1918, so it costs one TCP SYN,
while a WireGuard tunnel is exactly the case where the sync is wanted.

`PlaybackSignals` is the seam 6.2 plugs into. The rules it encodes are easy to
break quietly — dial on the *edge* into playing, push a position on either edge,
and do nothing on a tick — so they sit behind an interface and are tested
directly. A player reporting state on a timer would otherwise dial once a second
and nothing on screen would look wrong.

**Android 17 blocks local-network connections.** Local Network Protection
means `INTERNET` no longer covers reaching an address on the LAN — which is all
device sync does. Without `ACCESS_LOCAL_NETWORK` the platform drops the
connection *before it leaves the phone*: no error, nothing in the peer's log,
and the only evidence anywhere is one line of `appops`:

    ACCESS_LOCAL_NETWORK: ignore
    ACCESS_LOCAL_NETWORK: allow; rejectTime=+49s ago

It cost hours because everything else looked right — the port was reachable
from the phone's shell, the firewall was open, the pairing code matched — and
an emulator on API 36 works, so it only appears on real hardware. Requested
from the Device sync screen, for the same reason the spec already gives for the
iOS local-network prompt: the prompt should arrive while the user is pairing.

**And a diagnosis made worse by a bad message.** A connect timeout throws
`TimeoutCancellationException`, which *is* a `CancellationException` — so the
rethrow branch caught it, nothing reported the failure, and the cleanup path
labelled it a refused handshake. A connection the OS had blocked was reported
as "check that both devices show the same code", which sent the user to verify
a code that was correct all along. Caught separately now.

**The periodic sync did not exist.** `AerialPodCore` said the periodic
schedule was "the platform's job (WorkManager on Android)", and nothing ever
did it — the only WorkManager use was `DownloadWorker`, a one-time request. So
the app synced *only* on events it happened to be awake for: a queue edit, a
final position report, the manual button. A phone left alone never picked up a
new episode, and nothing anywhere reported that, because every sync that did
run worked perfectly.

Now an hourly `SyncWorker`, matching the desktop's `REFRESH_INTERVAL_MS`, with
the interval a shared constant so neither platform can pick its own. The pass
itself is `AerialPodCore.backgroundSync()` rather than worker code, because iOS
needs the identical pass from `BGAppRefreshTask`.

The one decision worth the tests: **not-configured is not a failure.**
`syncNow()` throws when there is no gpodder account, so the obvious worker
reports `retry` and puts every phone that never signed in into an endless
exponential backoff, waking the radio for a request that cannot succeed until
someone types a password. A dead feed is not a retry either — one unreachable
feed is ordinary and the next pass is an hour away. Only a sync that genuinely
failed asks to be woken for. 7 tests, all five mutations caught.

Verified on the emulator rather than assumed: the job registers with
`earliest=+59m48s`, and the worker body runs to `Worker result SUCCESS`.

- [x] Hourly background sync — `SyncWorker` + `BackgroundSync.schedule`
- [x] The LAN mesh stays foreground-scoped; a worker dialling peers would fight
      `PeerLifecycle` for a service the user cannot see running

### 6.5 — Downloads — done
- [x] Download-ahead for the queue head, everything else streams — a fixed
      count (0, 1, 5) or a **share of the queue** (1/4, 1/3, 1/2, whole queue),
      recomputed as the queue changes and rounded up so a non-empty queue always
      keeps at least one
- [x] `keep_download` and eviction, with 18 policy tests and all 6 mutations caught
- [x] Download state as a badge on the artwork in every episode list — a
      tick-arrow when downloaded, an arrow while transferring, a pin when kept
      regardless of the policy. A badge rather than a glyph in the meta line:
      "is this on my phone?" is asked while scanning, and an answer you have to
      hunt for is no answer.

The **policy** is in the shared core and the **transfer** is not: the rules are
shared with iOS and the file handling is not, and the interesting failure here
is a policy that deletes something it should have kept. `DownloadPolicy.plan()`
is a pure function of the queue, the setting and what is in flight.

WorkManager rather than a coroutine on the app's scope, because the whole point
of downloading ahead is that the episode is ready *later* — queued before
leaving the house, wanted on a train with no signal. A transfer tied to the
app's process would stop the moment the user swiped it away, which is exactly
when they thought it was safe to.

**Never `client.get()` for a media file.** Ktor's `get()` reads the whole body
into memory before returning — survivable for a feed, fatal for an episode. A
four-hour show is a few hundred megabytes against a heap of a couple, so it
throws `OutOfMemoryError` on an OkHttp thread, which kills the process; and
because WorkManager retries, it presents as the app repeatedly stopping rather
than as anything to do with downloads. `prepareGet { execute { } }` streams, and
the per-request timeout has to be lifted too — the client's 60 s is right for an
API call and wrong for something that legitimately takes minutes. Measured
after: 1.4 GB written with the Java heap flat at 22–28 MB.

**A dead transfer inside the wanted window is stranded.** A crash leaves the
queue head at `downloading`: not `none`, so a fetch-only-none policy skips it;
still wanted, so eviction skips it too. It would never download again. Picking
it up is safe because the `.part` file resumes.

Two more the transfer has to get right, both from `core/downloads.py`:

- **A server that ignores `Range` answers 200 with the whole body.** Appending
  that to a `.part` file produces a corrupt file that still looks complete, so
  a 200 in response to a range request restarts from scratch.
- **An interrupted transfer leaves a row claiming `downloading`** with nothing
  carrying it. The policy has to be able to evict that, or the episode is stuck
  forever — neither downloaded nor re-fetchable.

The fractions are a deliberate mobile-only extension. The desktop's
`download_ahead_n` is an integer and stays one — a fraction written into that
key would crash its `int()` — so the choice lives in `download_ahead` beside it
and falls back to it, which keeps an upgraded install on whatever it was set to.

Verified on a device: 30 MB fetched to app-scoped external storage with a
`download` action queued for gpodder; switching the setting off deleted it; a
**pinned** episode survived the same switch with the file untouched; and `1/4`
of an 11-episode queue resolved to exactly 3.

---

## Verification milestones

Worth calling out separately — these are what say "it actually works", as
opposed to "the tests pass".

M1 and M2 were run against a **throwaway daemon** — its own `AERIALPOD_DATA_DIR`,
its own port and its own pairing secret, on a private D-Bus session — so that
proving them could not touch the real library. Worth reusing for M3:

```sh
# A real desktop peer with an isolated database, on a bus with no service
# activation (otherwise a keyring lookup blocks its event loop for 120 s and it
# silently stops accepting connections).
AERIALPOD_DATA_DIR=/tmp/peer PYTHONPATH=src \
  dbus-run-session --config-file=nobus.conf -- python3 -m aerialpod.daemon -v
```

- [x] **M1 — handshake on a real LAN.** The phone dialled a real
      `aerialpod-daemon`, completed the handshake and exchanged snapshots. The
      daemon recorded it as `Google sdk_gphone16k_x86_64`.
- [x] **M2 — queue agrees, both directions.** A drag reorder made on the phone
      merged into the desktop (11 intents) and reproduced its order exactly;
      a reorder made on the desktop then landed on the phone on the next dial.
      The path callback was proven live too — airplane mode off re-dialled with
      no user interaction.
- [ ] **M3 — live position** (needs 6.2 as well). Pause on the phone, desktop
      picks it up in seconds rather than at the next gpodder cycle.
- [ ] **M4 — cold open** (needs 6.1). Phone with no LAN contact for days still
      opens to a correct queue via gpodder (`reconcile()` derives it either way).

## iOS

- [ ] `iosMain` actuals: crypto (CryptoKit), time, SQLDelight native driver,
      keychain `SecretStore`
- [ ] `NSPathMonitor` → `onPathChanged()`
- [ ] `NSLocalNetworkUsageDescription` + first dial from the pairing screen
- [ ] SwiftUI app + AVPlayer with `UIBackgroundModes: audio`

---

## Open questions

- **minSdk 30.** Chosen because `repo.py` uses `ON CONFLICT … DO UPDATE`
  throughout and Android only ships SQLite 3.24+ at API 30. Lowering it means
  rewriting every upsert or bundling SQLite.
- **`Ident.port = 0`.** A phone never listens, so the desktop remembers us and
  periodically dials an address nothing is bound to — one TCP SYN per retry.
  Confirmed in the live run: the daemon stored our peer row as `127.0.0.1:47722`,
  its own port, because zero reads as "no opinion". Harmless, but a "don't dial
  me" signal would need a protocol field the desktop does not yet know.
- **Default LAN port is now 47722** across the desktop and the mobile core. Note
  that ports differ per install in practice — the peer already in this mesh is on
  47782 — so the ident's port field is what actually matters, not the default.
- **Snapshot size on a large library.** 365 KiB per exchange is fine on connect
  and is why there is no periodic resync; worth re-measuring once real
  libraries are syncing.
- **Settings replication is typed, not key-driven.** The desktop builds that
  section from `repo.SETTING_KEYS`, so a new setting replicates for free.
  `SettingsRecord` cannot — adding a per-podcast setting means adding a field
  there too, or it never leaves the phone.
