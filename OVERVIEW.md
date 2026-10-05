# CrossDrop — Project Overview

Ad-free, privacy-first local file sharing between nearby devices. Think AirDrop
for everyone: two phones on the same Wi-Fi (or within Bluetooth range) see each
other on an ambient sonar screen, tap, and files stream directly between them —
encrypted end-to-end, no accounts, no servers, no internet.

Android-first (minSdk 29, targetSdk 36). The protocol core is deliberately
platform-neutral Kotlin/JVM so a shared Rust engine can later replace the
internals — and drive an iOS client — without touching the app.

## Design pillars

| Pillar | What it means in code |
| --- | --- |
| Ad-free / privacy-first | No cloud, no accounts, no analytics, `allowBackup="false"`. Identity is a rotating per-launch token — nothing long-lived to correlate. |
| Line-rate local speed | Single raw TCP socket, 256 KB data chunks, `tcpNoDelay`, minimal framing overhead. |
| <15 MB installed size | No DI framework (hand-rolled service locator), no DB (JSON in SharedPreferences), hand-drawn Canvas icons (no material-icons artifact). BouncyCastle is the one heavyweight dep — slated for replacement. |
| Ambient "sonar + liquid orb" UX | Custom `Canvas` rendering everywhere: sonar radar for discovery, a fluid-filled glass orb for progress. Dark-only deep-space theme. |

## Feature walkthrough

### Radar tab — discovery & send entry point

`ui/radar/RadarCanvas.kt` — the signature screen. A fully custom-animated
`Canvas`:

- **Sonar field** — 4 expanding ripple rings, a 60° rotating sweep wedge,
  concentric grid rings, crosshair lines.
- **Ambient background** — three drifting nebula blobs (violet/cyan/teal radial
  gradients) plus 90 hash-seeded twinkling stars.
- **Self node** — pulsing "heartbeat" ring at center, labeled with the device
  name (tap it to rename) and listen port.
- **Peer nodes** — glowing orbs with breathing halos and glass-pill name
  labels. Position:
  - *Angle* = stable hash of the peer's `instanceId` (nodes don't teleport
    between recompositions).
  - *Radius* = normalized BLE RSSI → estimated meters (0.3 m–12 m compressed
    logarithmically); NSD-only peers sit on a default ring.
  - *Dimmed* when the sighting is BLE-only with no reachable TCP endpoint.
  - Gentle per-peer bob animation; tap target ~140 px → send flow.
- **Peer strip** — up to 3 reachable peers as tappable pills at the bottom.

**Send flow:** tap a peer → haptic tick → bottom sheet offering
"Photos & videos" (Android photo picker, multi-select up to 10) or "Files"
(SAF `OpenMultipleDocuments`, any type) → `TransferCoordinator.sendTo`.

**"to anyone" pill** (top bar) — same chooser, but the result feeds the
zero-install QR share instead of a peer connection.

**Permissions:** on first launch requests `BLUETOOTH_SCAN/ADVERTISE/CONNECT`
(API 31+), `NEARBY_WIFI_DEVICES` + `POST_NOTIFICATIONS` (33+), or
`ACCESS_FINE_LOCATION` (≤30). A banner stays visible until granted; granting
triggers `restartDiscovery()`.

### Transfers tab

`ui/transfers/TransfersScreen.kt`:

- **Active card** — live session with peer name, current file, %, a thin
  teal progress bar, bytes done/total and instantaneous rate. On v2 delta
  transfers a second line reports locally-reused bytes ("Δ … already local").
- **History list** — persisted log (SharedPreferences JSON, capped at 100
  entries) of every terminal session: direction arrow, peer, file summary
  ("photo.jpg" or "12 files"), bytes, outcome (`done`/`failed`/`declined`/
  `cancelled`), smart timestamp (time today, date otherwise). Clear-all action.

### Inbox tab

`ui/inbox/InboxScreen.kt` + `storage/InboxStore.kt`:

- Received files land in `filesDir/inbox/` — a **sandboxed staging area**,
  deliberately separate from gallery/Downloads (storage-isolation pillar).
- Filenames sanitized (path traversal stripped, odd chars → `_`, ≤96 chars)
  and de-duplicated as `name (2).ext`.
- Badge count on the nav tab.
- Per-file **Open** (via `FileProvider` + `ACTION_VIEW`, granted-URI read) and
  **shred** buttons; **Wipe all** = single-pass zero-fill overwrite + delete on
  every file — the app's secure-delete primitive.
- Hidden siblings power protocol v2: `.staging/` holds hash-named received
  chunks (resume pool), `.idx/` keeps per-file chunk indexes (delta baselines) —
  see *Wire protocol v2* below. Both are cleared by Wipe all.

### Settings tab

`ui/settings/SettingsScreen.kt`, five sections:

- **Device** — visible name (editable, persisted; default = system device name
  → `Build.MODEL` fallback), rotating device id (first 12 hex chars), listen
  port.
- **Discovery** — independent toggles for Wi-Fi/mDNS and BLE layers (persisted
  in prefs, hot start/stop of the backends).
- **Storage** — inbox stats, wipe inbox, clear transfer history.
- **Local drive** — the vault host dashboard: URL, RW/PIN toggles, connected
  clients, traffic counters, browser QR, Kill drive (see *Local cloud drive*).
- **About** — version, cipher suite summary, privacy statement.
- **Developer** (debug builds only) — loopback self-test (sends a generated
  4 MiB file through the full encrypted stack to self) and "add peer by
  address" (hand-injects a radar peer — bridges the emulator mDNS gap).

### Transfer overlay — the liquid orb

`ui/transfer/` — slides up over the whole shell when a session is active:

- **`LiquidOrb`** — a glass sphere whose fluid level *is* the progress bar:
  - Fill height = bytes fraction (spring-animated).
  - **Turbulence** = live throughput (saturates ~60 MB/s) driving wave
    amplitude, bubble count, and an inner phosphorescent glow.
  - **Slosh** = `TiltSource` feeds low-pass-filtered accelerometer into the
    liquid plane — it counter-rotates against gravity; device jerk injects
    extra energy. Move the phone, the fluid moves.
  - **Completion** — surface crystallizes: rim flares phosphor + expanding
    halo, plus a `CONFIRM` haptic.
  - Live % rendered inside the glass.
- **Header** — direction glyph + SENDING/RECEIVING, peer name, and a
  **SAS chip**: `◈ verify 123456` — the 6-digit short-auth-string both sides
  derive independently, for human MITM verification.
- **Stats card** — current file, bytes done/total, live rate.
- Cancel button (in-flight) → sends CANCEL; Done button (terminal) dismisses.

### Share to anyone — zero-install receiver

`core/share/` + `ui/qrshare/` — solves the two-app problem: send files to a
device that does not have CrossDrop installed.

- Tap "to anyone" → pick files → the app binds an ephemeral
  **`HttpShareServer`** (hand-rolled, `ServerSocket` + thread pool — zero new
  dependencies, honoring the size pillar) and shows a full-screen **QR**.
- The QR encodes a **capability URL**:
  `http://<wifi-ip>:<port>/<128-bit-token>/` — the token is the secret;
  every route outside it 404s, so the surface is invisible.
- The receiver's browser loads a **self-contained dark web client**
  (`core/…/resources/share.html`, brand-matched): device name, per-file
  cards with Save links, a "Save all as .zip" button for multi-file shares
  (streamed uncompressed STORED zip — no descriptors, max extractor
  compatibility), and a keep-alive status poll.
- Routes: `/` HTML · `manifest.json` · `f/<i>` per file (GET/HEAD,
  `Content-Disposition`, `Content-Length`) · `all.zip` · `status.json`
  live counters.
- **QR rendered in-app** by `QrCode` — a hand-rolled ISO 18004 encoder
  (byte mode, versions 1–10, Reed-Solomon ECC, automatic best-mask
  selection), drawn straight into a Compose `Canvas`. Verified by decoding
  generated matrices with ZXing in JVM unit tests (test-scope dep only —
  nothing ships).
- The overlay shows live state: *waiting for a scan → receiver connected →
  N of M downloads · bytes → delivered*; keeps the screen on while open;
  `TransferService` posts a share notification so the server survives
  backgrounding. Stopping writes a history entry ("browser share").
- `LocalIp` picks the interface for the URL: `wlan*`/`ap*` IPv4 first, then
  site-local fallbacks (hotspot/tether).

### Local cloud drive — LAN vault

`core/vault/` + `net/Vault*Manager` + `ui/vault/` — turns the device into an
ad-hoc LAN storage daemon: nearby peers browse, pull (with seeking), and
upload files without a prior send request.

**One port, two surfaces.** `VaultServer` binds `ServerSocket(0)` and sniffs
the first 4 bytes of each connection: `GET `/`POST`/`HEAD` route to the
embedded HTTP drive, everything else to the AEAD binary session — browsers
and CrossDrop clients share a single advertised port.

- **Discovery** — hosts register `_crossdrop-vault._tcp.` as
  `xdrop-vault-<16-hex>` (fresh id per run; TXT `id`,`name`,`mode=ro|rw`,
  `auth=open|pin`,`ver=1`). `VaultDiscovery` surfaces them as radar **orbital
  beacons**: violet core, segmented rotating ring, `RW`/`RO` badge; the host's
  own beacon is filtered by TXT id.
- **Native protocol** — same handshake as transfers (X25519→HKDF→
  ChaCha20-Poly1305), then frame types `0x10–0x1A`: `VAULT_LS/DIR`,
  `STAT/ATTR`, `PULL` (offset+length streaming, 256 KB `VAULT_DATA` chunks),
  `PUSH_INIT/PUSH_ACK` + data + `VAULT_DONE` (BLAKE3-verified), `VAULT_ERR`,
  `VAULT_AUTH`. PIN mode: `auth=pin` advertises, client sends
  `HKDF(ECDHsecret, salt=PIN, "crossdrop/v1/vault-auth")` right after key
  agreement; the host displays the code, a mismatch closes the socket.
- **Storage jail** (`VaultFs`) — NFC normalization, `\0`/`..` rejection,
  canonical containment under `filesDir/vault`; uploads stage in
  `.staging/<tid>.part` and move atomically only after the full BLAKE3 digest
  matches. Free space is checked before `PUSH_ACK`.
- **Browser drive** (`VaultHttp` + `resources/vault.html`) — `GET /` serves a
  self-contained dark explorer (breadcrumb nav, per-item download, upload
  dropzone); `GET /api/fs?path=` returns JSON listings; `GET /raw/<path>`
  streams with full `Range` support (`206`/`Content-Range`/`416`), so media
  seeks work in the browser player; `POST /upload` accepts multipart into
  the same staged pipeline (blocked in `ro` mode).
- **In-app explorer** — `VaultExplorerManager` + `VaultExplorerScreen`:
  breadcrumb navigation, multi-select batch downloads into the inbox with a
  live job strip, `↑ upload` (SAF picker → `PUSH`), PIN gate UI when
  `auth=pin`, reconnect-on-stale-socket. Media entries stream in place:
  `VaultMediaBridge` binds `127.0.0.1:0` and translates HTTP `GET /raw?path=`
  (incl. `Range:`) into `VaultClient.pull` calls — players get seeking over
  the encrypted session without downloading the file.
- **Host dashboard** — Settings → Local drive: host toggle + live URL,
  read-write and PIN-lock toggles (restart the vault), shown PIN, connected
  clients, cumulative traffic, browser QR card, and **Kill drive** (drops
  listener + clients, unregisters NSD, purges staging).

### Inbound offers

`ui/IncomingOfferSheet.kt` — modal bottom sheet (one at a time; a new offer
rejects the pending one as "superseded"). Aurora-ringed peer avatar, file list
(first 4 + "+N more"), total size, Accept/Decline. 45-second no-response
timeout auto-rejects.

### Foreground service

`service/TransferService.kt` — `LifecycleService` with `dataSync` FGS type,
started when a send begins or an offer is accepted. Shows an ongoing
notification ("Sending to X — 42% · 18.3 MB/s") so sockets survive the app
being backgrounded; self-stops when the session clears. Zero cost while idle.

## Architecture

```
┌──────────────────────────── :app (Android, Compose) ───────────────────────┐
│  CrossDropApp → AppContainer (service locator, process-scope)              │
│                                                                            │
│  discovery/            net/                 storage/        service/       │
│   NsdDiscovery   ──┐    TransferServer      InboxStore      TransferService│
│   BleDiscovery     ├─→   TransferCoordinator HistoryStore                 │
│   VaultDiscovery   ├     QrShareManager                                   │
│   ManualDiscovery ─┘     VaultHostManager · VaultExplorerManager ·        │
│                          VaultMediaBridge                                 │
│                              ↓                                            │
│              :core TransferEngine        :core vault server/client        │
│                                                                            │
│  ui/ — Root (tabs+overlay) · radar · transfer · transfers · inbox ·       │
│        settings · vault explorer · theme · hand-drawn Canvas icons        │
└────────────────────────────────────────────────────────────────────────────┘
┌──────────────────────────── :core (pure Kotlin/JVM) ───────────────────────┐
│  CrossDrop (constants) · Peer/Endpoint · PeerDiscovery (interface)         │
│  protocol/  Frame codec + kotlinx.serialization JSON messages              │
│  crypto/    SessionIdentity/Crypto (X25519+HKDF) · RecordCipher (AEAD)    │
│  transfer/  TransferEngine — send()/receive() over one Socket              │
│  delta/     FastCdc · Blake3 · FileDeltaStore — v2 chunk sync              │
│  vault/     VaultServer · VaultClient · VaultHttp · VaultFs · vault.html   │
└────────────────────────────────────────────────────────────────────────────┘
```

### Dependency injection

No framework — `AppContainer` is a hand-rolled service locator created in
`CrossDropApp.onCreate`. Owns: `instanceId` (8 random bytes → hex, fresh each
launch), device name (prefs), `SessionIdentity`, `TransferEngine`,
`InboxStore`, `HistoryStore`, `TransferServer`, `TransferCoordinator`, the
three discovery backends, and the merged `peers` flow.

### Peer discovery — two transports, one peer map

`PeerDiscovery` interface emits `StateFlow<Map<instanceId, Peer>>`.
`AppContainer.peers` combines all backends, merging sightings of the same
`instanceId` into a single `Peer` (union of `sources`, freshest `lastSeenMs`,
first non-null endpoint/RSSI).

- **`NsdDiscovery`** — registers `_crossdrop._tcp.` via `NsdManager` on the
  listener's ephemeral port, TXT records `id` + `name` (48 chars). Continuously
  discovers + resolves peers; API 34+ uses `ServiceInfoCallback` (multi-address
  resolution), older uses `resolveService`. Holds a `MulticastLock`. Self
  filtered by instanceId. Service-lost removes the peer.
- **`BleDiscovery`** — non-connectable BLE beacon: advertises 128-bit service
  UUID `cd0d5e55-…` with the instance token as service data; scans for the same
  at `LOW_LATENCY`. Sightings carry **RSSI but no endpoint** — they enrich the
  radar with proximity and merge with NSD sightings of the same instanceId.
  Sightings not re-heard within 8 s expire.
- **`ManualDiscovery`** — debug-only; `injectPeer()` places a peer by
  `host:port` (used by the adb dev hooks on emulators where multicast mDNS
  can't cross the virtual Wi-Fi).

### Transfer pipeline

- **`TransferServer`** — always-on `ServerSocket(0)`; the bound ephemeral port
  is pushed into NSD's `advertisedPort` and shown in UI. Each accepted socket →
  `coordinator.onInboundSocket`.
- **`TransferCoordinator`** — the app's single source of truth for "what's
  happening now". UI never touches sockets: it observes `session` /
  `incomingOffer` StateFlows and calls intents (`sendTo`, `respondToOffer`,
  `cancelSession`, `dismissSession`). Reduces `TransferEvent`s into
  `SessionState` (direction, peer, sas, offer, bytes, rate, status) and records
  each terminal session once into `HistoryStore` — direction taken from the
  call site (a loopback socket can hold the opposite leg in `_session`).
- **Sender side** resolves SAF `Uri`s into `OutgoingFile` (name + size from
  `OpenableColumns`, mime from `ContentResolver`, fresh `InputStream` factory).
- **Receiver side**: each offer pops a `CompletableDeferred` behind the modal
  sheet; accepting wires `inbox.sinkFor(meta)` as the per-file output factory.

## Wire protocol v1

One TCP connection per transfer. Frame = `[u32 BE bodyLen][u8 type][payload]`,
4 MiB max frame. `HELLO`/`HELLO_ACK` are cleartext JSON; **every frame after is
AEAD-sealed** (`seal(type || payload)`).

```
Sender (initiator)                     Receiver (responder)
──────────────────                     ─────────────────────
HELLO        ────────────────────────→   {version, deviceName, X25519 pub, instanceId}
             ←────────────────────────  HELLO_ACK  (same fields)
              ── X25519 ECDH → HKDF → send/recv keys + 6-digit SAS ──
OFFER        ────────────────────────→   {transferId, [FileMeta{name,size,mime}]}
             ←────────────────────────  ACCEPT {transferId} | DECLINE {reason}
DATA*        ────────────────────────→   [u32 fileIndex][bytes…]
DONE         ────────────────────────→   {transferId, sha256(all payload bytes)}
             ←────────────────────────  DONE echo — receiver's own SHA-256
              sender compares; mismatch ⇒ integrity failure
```

- **File multiplexing:** each `DATA` payload opens with a 4-byte file index.
  An *empty* DATA body (index only) marks a new file's start — this also makes
  zero-byte files materialize.
- **Chunking:** 256 KB per DATA frame; `MAX_FRAME` = 4 MiB.
- **Timeouts:** 15 s on control frames, 60 s on data (receiver), infinite while
  streaming (sender).
- **End-to-end integrity:** both sides SHA-256 the raw payload stream; the
  receiver echoes its digest in the final DONE and the sender compares.
  Tampering or truncation fails loudly, not silently.
- **Cancellation:** either side can send `CANCEL` at any point; coroutine
  cancellation on the sending side writes a best-effort CANCEL before closing.
- **Version tolerance:** `PROTOCOL_VERSION = 2` pinned in HELLO; JSON uses
  `ignoreUnknownKeys` so newer fields don't break older peers. v1↔v2 peers
  negotiate down to the monolithic path (see next section).

### Cryptographic session

`crypto/SessionCrypto.kt` + `RecordCipher.kt` (BouncyCastle lightweight API):

- Fresh **X25519** ephemeral keypair per app launch (`SessionIdentity`) —
  forward secrecy, no persistent identity key.
- Shared secret → **HKDF-SHA256**, salted with `localPub‖peerPub` (ordered by
  initiator role) so keys bind to this exact pairing.
- Two directional keys via info strings `crossdrop/v1/session/i2r` and
  `…/r2i`; role-swapped per side.
- **SAS** = HKDF output mod 10⁶ formatted as 6 digits — the human-verifiable
  anti-MITM check (acoustic OOB handshake is planned to automate this).
- **`RecordCipher`** — TLS-record-style AEAD: every frame body is sealed with
  **ChaCha20-Poly1305** (128-bit tag) under nonce = `0x00000000 ‖ u64 BE
  counter`, one monotonic counter per direction. Reordered, dropped, replayed
  or tampered records all fail authentication.

## Wire protocol v2 — chunked delta transfer

v2 (`PROTOCOL_VERSION = 2`) adds content-defined-chunk delta sync and
interruption-proof resumption on top of the same handshake and crypto. The
receiver opts in via `ACCEPT { delta: true }`; if either side is v1, the
transfer transparently falls back to the monolithic `DATA` path.

```
per file:
  CHUNK_MAP  ────────────────────────→  FastCDC/BLAKE3 manifest (multi-frame)
               ←────────────────────  CHUNK_REQ {missing chunk indices}
  CHUNK*     ────────────────────────→  ONLY the changed/missing chunks
DONE         ────────────────────────→  {transferId, roots:[blake3 per file]}
               ←────────────────────  DONE echo — receiver's own roots
```

- **`delta/FastCdc.kt`** — 32-bit gear rolling hash over a deterministic
  SplitMix64-seeded table; cut points are content-defined, so edits localize:
  ~95%+ of boundaries survive mid-file inserts. min 16 KiB / avg 64 KiB /
  max 256 KiB, emitted as a lazy Sequence.
- **`delta/Blake3.kt`** — pure-Kotlin BLAKE3 (hash mode), streaming chunk
  stack — verified against the official `test_vectors.json` (11 input sizes
  incl. multi-chunk and 100 KiB cases).
- **Manifest negotiation** — the sender chunks each file twice (pass 1:
  hashes; pass 2: streams only requested chunks — `OutgoingFile.open()` is
  re-invoked, so SAF sources are re-read cheaply). `CHUNK_MAP` splits into
  ≤16,384-entry parts to stay under `MAX_FRAME`.
- **Receiver reuse sources** (`delta/FileDeltaStore`, wired as
  `InboxStore.delta`):
  - `.staging/<blake3>.bin` — every received chunk is hash-verified then
    stored hash-named. A killed transfer leaves its chunks pooled, so a
    re-offer of the same file resumes: only the missing chunks cross the wire.
  - `.idx/<file>.idx` — after assembly the manifest + file root persist as a
    sidecar. Re-sharing an already-received filename deltas against the index
    (mtime+size validated); identical files complete with **zero bytes** sent.
- **Assembly** — chunks are spliced into a `<name>.part` temp (pool files
  copied, baseline regions read via `RandomAccessFile`) then moved over the
  target — the baseline handle closes first since target may *be* the
  baseline. A fully-identical manifest skips writing entirely.
- **Integrity** — every chunk is BLAKE3-verified on receipt (inside the AEAD
  record layer, so chunks are individually authenticated); DONE carries
  per-file BLAKE3 roots verified both ways.
- **Progress** — `bytesDone` counts sent+skipped (orb fills instantly for the
  reused share); `bytesSkipped` surfaces as the "Δ … already local" line.

## Tech stack

| Layer | Choice |
| --- | --- |
| Language | Kotlin 2.2.21 (JVM 17 bytecode) |
| UI | Jetpack Compose (BOM 2025.11.00), Material 3, dark-only theme, hand-drawn Canvas icons |
| Async | kotlinx-coroutines 1.10.2 (`Flow`-based event streams, `StateFlow` UI state) |
| Serialization | kotlinx.serialization JSON 1.9.0 |
| Crypto | BouncyCastle `bcprov-jdk18on` 1.81 (largest dep — see roadmap) |
| DI / DB / network libs | none — service locator, SharedPreferences, raw `java.net.Socket` |
| Build | AGP 8.13.2, Gradle 8.13, compileSdk/targetSdk 36, minSdk 29 |
| Test | JUnit 4 + coroutines-test on `:core` |

## Project structure

```
crossdrop/
├── core/src/main/kotlin/com/crossdrop/core/
│   ├── CrossDrop.kt            # NSD service type, BLE UUID, token size
│   ├── Peer.kt                 # Peer/Endpoint models, RSSI→meters
│   ├── crypto/
│   │   ├── SessionCrypto.kt    # X25519 identity, HKDF session + SAS
│   │   └── RecordCipher.kt     # ChaCha20-Poly1305 record AEAD
│   ├── share/
│   │   ├── QrCode.kt           # hand-rolled QR encoder (byte mode, RS ECC)
│   │   ├── HttpShareServer.kt  # token-guarded micro HTTP + zip streaming
│   │   └── (res) share.html    # self-contained dark web client
│   ├── delta/
│   │   ├── Blake3.kt           # pure-Kotlin BLAKE3 hasher (test-vector verified)
│   │   ├── FastCdc.kt          # content-defined chunker (gear rolling hash)
│   │   ├── Chunks.kt           # ChunkEntry/Baseline/DeltaReceiver + wire codec
│   │   └── FileDeltaStore.kt   # staging pool + .idx sidecars + baselines
│   ├── protocol/
│   │   ├── Frame.kt            # frame codec, FrameType enum, FrameIO
│   │   └── Messages.kt         # Hello/Offer/Accept/… + vault JSON messages
│   ├── vault/
│   │   ├── VaultFs.kt          # storage jail: NFC/containment/staged commits
│   │   ├── VaultMessages.kt    # LS/STAT/PULL/PUSH/AUTH payloads
│   │   ├── VaultServer.kt      # one port: HTTP demux + AEAD vault sessions
│   │   ├── VaultHttp.kt        # zero-install web drive (api/fs, raw+Range, upload)
│   │   ├── VaultClient.kt      # blocking opLock-serialized client
│   │   └── (res) vault.html    # embedded browser explorer
│   ├── discovery/PeerDiscovery.kt   # discovery backend interface
│   └── transfer/TransferEngine.kt   # v1/v2 send()/receive() state machines
├── core/src/test/…             # CryptoTest, TransferEngineTest (loopback),
│                               # Blake3Test, DeltaTransferTest, QrCodeTest,
│                               # HttpShareServerTest, VaultTest
├── app/src/main/java/com/crossdrop/
│   ├── CrossDropApp.kt         # Application → AppContainer
│   ├── MainActivity.kt         # Compose host + dev intent hooks
│   ├── di/AppContainer.kt      # service locator, peer merging, toggles
│   ├── discovery/              # NsdDiscovery · BleDiscovery · VaultDiscovery
│   │                           # (+VaultAdvertiser) · ManualDiscovery
│   ├── net/                    # TransferServer · TransferCoordinator ·
│   │                           # QrShareManager · LocalIp · OutgoingFiles ·
│   │                           # VaultHostManager · VaultExplorerManager ·
│   │                           # VaultMediaBridge
│   ├── storage/                # InboxStore (stage+shred) · HistoryStore
│   ├── service/TransferService.kt   # dataSync FGS + progress notification
│   ├── sensor/TiltSource.kt    # accelerometer → orb slosh input
│   └── ui/
│       ├── Root.kt             # shell: 4 tabs, nav bar/rail, overlays
│       ├── radar/              # RadarScreen + RadarCanvas (sonar)
│       ├── transfer/           # TransferScreen + LiquidOrb
│       ├── transfers/          # live card + history
│       ├── qrshare/            # QR share overlay (zero-install receiver)
│       ├── vault/              # VaultExplorerScreen — mounted drive UI
│       ├── inbox/              # received files
│       ├── settings/           # identity/discovery/storage/about/dev
│       ├── theme/Theme.kt      # deep-space palette + M3 scheme
│       ├── UiIcons.kt          # hand-drawn Canvas icons
│       ├── IncomingOfferSheet.kt    # accept/decline modal
│       ├── DeviceNameDialog.kt      # rename + add-peer dialogs
│       └── FileTypeTile.kt     # extension-tinted file glyph
└── app/src/main/res/           # launcher icon, strings, themes, file_paths
```

## Build & test

```sh
./gradlew :core:test            # JVM unit tests — crypto roundtrip, tamper
                                # detection, multi-file loopback transfer,
                                # decline path, empty file, cancel propagation
./gradlew :app:assembleDebug    # debug APK (applicationId …debug)
./gradlew :app:assembleRelease  # unsigned, minified + resource-shrunk
                                # — the real <15 MB size check
```

Requires JDK 17–21 (embedded Kotlin DSL can't run on JDK 25 yet) and an
Android SDK (`ANDROID_HOME` or `local.properties`).

## Debug tooling (debug builds only)

- **Loopback self-test** — Settings → Developer sends 4 MiB through the full
  handshake + encrypted path to this device.
- **Add peer by address** — hand-place a radar peer; also via adb:
  `cmd activity start -n com.crossdrop.debug/com.crossdrop.MainActivity -e dev_peer <host>:<port>`
- **Direct self-send** — `-e dev_send <host>:<port>` fires a transfer at an
  arbitrary endpoint without touching discovery.
- **QR share** — `-e dev_share 1` starts a "send to anyone" session with a
  generated 4 MiB file; the URL (token included) is logged via logcat.
  On emulators, `adb forward tcp:<port> tcp:<port>` then curl the endpoints.
- **Vault hooks** — `-e dev_vault rw|ro|pin` hosts a seeded vault;
  `-e dev_vault_mount host[:port]` mounts it in the native explorer (port 0 ⇒
  loopback to own vault); `-e dev_vault_pin <code>` supplies the PIN (debug
  builds also log `vault pin=`); `-e dev_vault_peer host[:port]` injects a
  radar beacon (real self-beacons are filtered by design).
- Emulator note: two emulators on one virtual Wi-Fi (`-wifi-server-port` /
  `-wifi-client-port`) share unicast TCP but *not* multicast mDNS — use manual
  peers or two physical devices; BLE needs physical hardware too.

## Security & privacy model

- **Nothing leaves the LAN.** One TCP socket between two devices; no
  third-party endpoints, no analytics, no crash reporting.
- **Rotating identity** — `instanceId` and the X25519 keypair are regenerated
  every launch; NSD service name and BLE service data carry the same ephemeral
  token.
- **Encrypted by default** — everything after the key exchange is AEAD-sealed;
  the SAS chip gives users a human-verifiable authenticity check today.
- **Human-in-the-loop receive** — nothing writes to disk without an explicit
  Accept tap (45 s timeout ⇒ auto-decline).
- **Storage isolation** — received files stay in the app sandbox, never in
  shared media collections; filename sanitization blocks path traversal.
- **Secure delete** — inbox wipe is a zero-fill overwrite before unlink.
- **Vault jail** — the hosted drive is confined to `filesDir/vault` by
  canonical-path containment; uploads stage outside the visible tree and
  commit only after checksum verification. Vault auth is an optional
  HKDF-bound 4-digit PIN over the AEAD session.
- `android:allowBackup="false"`.

## Known limitations & roadmap

- **APK size:** BouncyCastle dominates — swap for Conscrypt X25519 (API 31+)
  or a compact curve25519 impl to approach the <15 MB target.
- **Radar angle** is hash-stable, not compass-aligned yet; RSSI→distance is a
  rough log model.
- **SAS is manual** — acoustic OOB handshake planned to automate verification.
- **Vault caveats:** two-device mDNS discovery is untested (emulator multicast
  limitation); the explorer's upload path is covered by core tests + HTTP
  smoke but not a real SAF pick on-device; vault PIN has no retry rate-limit;
  browser surface is plaintext HTTP by design (link secrecy, not transport
  security — same trust model as the QR share).
- **Not built yet:** stream-play preview during *transfers* (the vault's
  `VaultMediaBridge` proves the loopback pattern), multipath (Wi-Fi +
  BLE/control), Wi-Fi Direct transport, folder transfer,
  hotspot-mode share when devices aren't on a shared LAN (QR path currently
  needs one network), PIN option on the web share.
- **BLE untested** on emulators — needs two physical devices.
- Single concurrent send session; inbound sockets run concurrently but one
  offer sheet is shown at a time.
