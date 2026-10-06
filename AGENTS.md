# CrossDrop — agent notes

Cross-platform (Android-first) local Wi-Fi file sharing. Full spec lives in the
project brief; pillars: ad-free/privacy-first, line-rate local speed, <15 MB
installed size, ambient "sonar + liquid orb" UX.

## Layout

- `:core` — pure Kotlin/JVM, no Android deps. Wire protocol (frame codec +
  X25519/HKDF/ChaCha20-Poly1305 session crypto), `PeerDiscovery` interface,
  `TransferEngine` (send/receive over a single TCP socket). Designed so a
  Rust/UniFFI engine can replace internals later without touching the app.
- `:app` — Android shell (Kotlin 2.2 + Jetpack Compose, minSdk 29). NSD and
  BLE both implement `PeerDiscovery`; sightings merge by `instanceId`
  (rotating per-launch token) in `AppContainer.peers`.

## App structure

Four tabs behind a `NavigationBar` (a `NavigationRail` on ≥600dp widths), in
`ui/Root.kt`: **Radar** (discovery + tap-to-send + "to anyone" QR share),
**Transfers** (live session card + persisted history via
`storage/HistoryStore`), **Inbox** (full-screen received files),
**Settings** (identity, NSD/BLE toggles, storage, about, debug tools). The
transfer view is a full-screen overlay over the shell; inbound offers stay
modal sheets. History records each terminal session once —
direction is taken from the emitting call site (not `_session`, which can hold
the opposite leg of a loopback).

## "Send to anyone" (zero-install receiver)

Radar top bar → file picker → `QrShareManager` binds `core/share/
HttpShareServer` and shows `ui/qrshare/QrShareScreen` (full-screen QR overlay).
The QR encodes a capability URL `http://<wlan-ipv4>:<port>/<16-byte-hex-token>/`
serving a static `share.html` client, `manifest.json`, `f/<i>` downloads,
`all.zip` (STORED, no descriptors — java/util.zip-compatible), `status.json`.
QR is hand-rolled in `core/share/QrCode.kt` (byte mode, v1–10, RS ECC, best
mask) — verified by ZXing decode in test-scope tests (ZXing is NOT a shipped
dep). Shares record history as peerName "browser share" on stop. `LocalIp`
chooses wlan*/ap* IPv4 for the URL.

## Local cloud drive (LAN vault) — "Wireless Drive"

`core/vault/` turns a device into a browsable LAN drive — one `ServerSocket`
(prefers port 8080, falls back to ephemeral) demuxes surfaces by sniffing the
first 4 bytes: HTTP verbs route to `VaultHttp`; anything else routes to the
AEAD vault session (`VaultServer` — X25519→HKDF→ChaCha20-Poly1305 like
transfers, frames `0x10-0x1A`).

`VaultHttp` serves three surfaces on one socket:
- **Browser drive**: `/` embedded `vault.html`, `/api/info`, `/api/fs`,
  `/api/stat`, `/raw/<path>` with Range/206, `POST /upload` multipart.
- **Direct file GET/HEAD**: any non-API GET resolves through the fs jail —
  files serve bytes (Range→206), dirs serve the explorer page.
- **WebDAV**: `OPTIONS` (DAV: 1,2), `PROPFIND` depth 0/1 (depth infinity →
  403; multistatus XML), `PUT`, `MKCOL`, `DELETE`, `MOVE`, `COPY`,
  `LOCK`/`UNLOCK` stubs (Finder requires them). Verified end-to-end against
  real sockets in `core/.../VaultDavTest.kt`.

Auth: when a 4-digit PIN is set, DAV methods + direct GETs accept HTTP
**Digest** (MD5, realm `crossdrop` — what Finder/Windows redirector need) or
`X-Vault-Pin`/`?pin=`; JSON API routes take `X-Vault-Pin`/`?pin=` (401 JSON →
page shows PIN prompt). `/api/info` stays ungated so the page learns `pin` is
required. The AEAD vault session keeps its own `VAULT_AUTH` = HKDF(ECDH
secret, salt=PIN). Write verbs 403 when `writable=false` — read-only default.

`VaultFs` is an interface (jail contract: NFC, strip `/`, reject `\0`/`..`,
containment, `.staging/` uploads commit after BLAKE3). `FileVaultFs` = the
internal vault dir; `SafVaultFs` (app module) wraps a SAF-picked
`DocumentFile` tree so any user folder can be the drive root — Documents,
DCIM etc. (Android blocks sharing `Download` root + `Android/*` natively).

App: `VaultHostManager` (server + NSD advert `xdrop-vault-<16hex>` on
`_crossdrop-vault._tcp.`, TXT `id/name/mode/auth/ver`, dashboard state incl.
root label + drive URL, WiFiLock HIGH_PERF + partial WakeLock for screen-off
streaming), `VaultDiscovery` (vaults as peers — own beacon filtered by TXT
`id`), `VaultExplorerManager` (mount/browse/download-queue/upload/pin-retry).
Client side: `VaultClient` + `VaultMediaBridge` (127.0.0.1 HTTP→pull bridge so
players get Range streaming over the AEAD session).

UI: Settings → "Turn into drive" opens `ui/drive/DriveHostScreen` — start
card (vault vs SAF folder, guests-edit toggle, passcode toggle) → live card
("● Drive is on", shared-folder summary, QR, copyable address + passcode,
"Stop Sharing Drive") → `DriveGuideScreen` (Mac Finder ⌘K / Windows network
location / browser-Safari tabs + troubleshooting — no iOS Files SMB tab:
unrooted Android can't serve SMB2/3). Inbound vaults still mount via the
radar beacon → `VaultExplorerScreen`.

## Wire protocol

`[u32 BE len][u8 type][payload]`; HELLO/HELLO_ACK cleartext JSON, everything
after is sealed per-record (ChaCha20-Poly1305, nonce = u64 counter).
Sequence: HELLO → HELLO_ACK → OFFER → ACCEPT/DECLINE → payload → DONE → DONE.

**v1 payload** (fallback, any peer): DATA* frames = `[u32 fileIndex][bytes]`;
empty DATA marks file start; DONE echoes whole-transfer SHA-256 both ways.

**v2 payload** (delta sync + resume, `core/delta/`): per file, sender emits
CHUNK_MAP (FastCDC ~64 KiB content-defined chunks, BLAKE3-keyed) → receiver
replies CHUNK_REQ with only the missing indices (deduped by hash — identical
chunks cross the wire once) → sender streams those CHUNK frames only, seeking
straight to each chunk's recorded offset. Received chunks hash-verify into
`.staging/<blake3>.bin` (survives disconnects → free resume); pooled chunks are
re-hashed before reuse and baseline slices verified during assembly — corrupt
local state drops itself and re-requests rather than looping on integrity
failures. Assembled files get `.idx/<name>.idx` sidecars so re-shares delta
against an existing inbox file — identical content = 0 bytes sent. DONE echoes
per-file BLAKE3 roots. Negotiation: `Accept { delta }` + version clamp
(`Hello.version` absent ⇒ v1); v1 peers fall back automatically. Assembly is
temp+rename since target may be the baseline itself.

## Build / verify

```sh
./gradlew :core:test            # JVM unit tests (crypto + loopback transfer)
./gradlew :app:assembleDebug    # debug APK
./gradlew :app:assembleRelease  # unsigned, minified — real size check
```

Requires Android SDK at `~/Library/Android/sdk` (local.properties). Java 21 OK.

## Emulator testing

Two emulators can share one virtual Wi-Fi subnet — unicast TCP crosses,
multicast mDNS does **not** (netsim limitation — discovery needs real HW):

```sh
emulator -avd Pixel_9_Pro   -wifi-server-port 51230   # A: wlan0 = 10.0.2.16
emulator -avd Pixel_9_Pro_B -wifi-client-port 51230   # B: wlan0 = 10.0.2.17
```

Each device's listen port: `adb -s <serial> shell "logcat -d | grep 'listening on'"`.

Debug-only hooks (`com.crossdrop.debug` builds):

- Settings → Developer: "loopback self-test" (send 4 MiB to self) and
  "add peer by address"; own port shown under the device name + in Settings.
- `adb -s A shell "cmd activity start -n com.crossdrop.debug/com.crossdrop.MainActivity -e dev_peer 10.0.2.17:<BPORT>"`
  injects a radar peer (tap it → file picker → real sendTo). Note: `onNewIntent`
  delivery via `cmd activity start` is flaky on the Android 36.1 image —
  force-stop the app first so the extras land in `onCreate`.
- `-e dev_send host:port` fires the self-transfer at that endpoint; plain
  `host` (e.g. `127.0.0.1`) loops back to the app's own listener. Loopback
  sends auto-accept their own offer sheet (debug-only `autoAcceptOnce`).
  Sending the same self-test twice exercises the v2 delta path — the second
  run shows "Δ … already local" and ships ~0 bytes.
- `-e dev_share 1` starts a QR share of a generated 4 MiB file; logcat prints
  the full URL. On emulators use `adb forward tcp:<port> tcp:<port>` then curl.
- `-e dev_vault rw|ro|pin` hosts a seeded vault (`files/vault` with docs/,
  media/, readme.md); `vault pin=<code>` is logged in debug builds. The drive
  prefers port 8080 — `adb forward tcp:8080 tcp:8080` then curl it:
  `curl -i -X OPTIONS http://127.0.0.1:8080/` (DAV: 1,2), `-X PROPFIND -H
  "Depth: 1"` (multistatus), `--digest -u drive:<pin>` for `auth=pin`
  (ungated: `/api/info` only; wrong/absent creds → 401), write verbs 403 in
  `ro` mode. SAF root: Settings → Turn into drive → "Pick a folder" (system
  picker; `Download`/`Android/*` are OS-blocked, `Documents` works — PUT
  lands real files in `/sdcard/Documents/`).
- `-e dev_vault_mount host[:port]` opens the native explorer (port omitted/0 ⇒
  loopback to our own vault); `-e dev_vault_pin <code>` supplies the PIN.
- `-e dev_vault_peer host[:port]` injects a vault beacon onto the radar —
  real self-beacons are filtered, so this is how you test beacon→sheet→mount
  on a single emulator.
- `pm grant com.crossdrop.debug android.permission.{NEARBY_WIFI_DEVICES,
  BLUETOOTH_SCAN,BLUETOOTH_ADVERTISE,BLUETOOTH_CONNECT,POST_NOTIFICATIONS}`
  skips the runtime dialog.
- `am start` is unreliable on the Android 36.1 image — use `cmd activity start`.

## Known debt / next phases

- BouncyCastle is the bulk of APK size; swap for Conscrypt X25519 (API 31+)
  or a small curve25519 impl to approach the <15 MB target.
- Radar angle is hash-stable; compass-heading alignment not wired yet.
- Vault: two-device NSD discovery unverified (emulator multicast); explorer
  upload path exercised only via core tests + HTTP; vault PIN has no retry
  rate-limit.
- Stream-play during *transfers* (not vaults), acoustic handshake, multipath,
  Wi-Fi Direct: not built.
- BLE sightings can't be tested on emulators — need two physical devices.
