# CrossDrop

Ad-free, privacy-first local file sharing between nearby devices. Android first;
the protocol core (`:core`) is platform-neutral Kotlin designed for later
replacement by a shared Rust engine (iOS parity).

## What works today

- **Four-tab app shell** — Radar · Transfers · Inbox · Settings (nav rail on
  wide screens), all local-only, no accounts, no cloud.
- **Sonar radar UI** — ambient nebula + starfield canvas; discovered peers
  orbit as pulsing nodes with glass labels, tap one to send files.
- **Discovery, two transports** — mDNS/NSD on the local LAN plus BLE 5.0 beacon
  (service UUID `cd0d5e55-…`, rotating instance token), merged into one peer
  map. Each layer can be toggled in Settings.
- **Encrypted transfer** — per-session X25519 ECDH → HKDF → ChaCha20-Poly1305
  record layer, with a 6-digit short-auth-string shown on both devices.
- **Delta sync & resume (protocol v2)** — FastCDC content-defined chunks +
  pure-Kotlin BLAKE3. Re-sending a modified file ships only the changed
  ~64 KB chunks; a dropped transfer resumes from a hash-verified staging
  pool; an identical re-send transfers zero bytes ("Δ already local").
- **Liquid orb progress** — fluid level = bytes transferred, live percentage in
  the glass, wave turbulence tracks throughput, accelerometer sloshes the
  surface, completion crystallizes.
- **Send to anyone (no app needed)** — an embedded, token-guarded HTTP
  server + in-app QR encoder serves a dark web client; the receiver scans
  with any camera and downloads in their browser. Multi-file shares stream
  as a single uncompressed zip.
- **Local cloud drive** — flip "Host LAN vault" in Settings and the phone
  becomes a browsable drive: CrossDrop peers mount it over the encrypted
  socket (optional 4-digit PIN), and any browser on the LAN gets a
  zero-install file explorer at the same port — Range-enabled streaming,
  uploads, strict path jail. Vaults orbit the radar as violet beacons.
- **Media-aware send flow** — tapping a peer offers "Photos & videos"
  (Android photo picker: gallery, albums, Google Photos where supported)
  or "Files" (SAF documents picker), multi-select on both.
- **Transfer history** — persisted log of past sends/receives with outcome.
- **Inbox** — sandboxed staging for received files with zero-fill shredding.

## Build

Android Studio (2025.3+) — open the repo root, or:

```sh
./gradlew :app:assembleDebug
```

Run on two devices on the same Wi-Fi (BLE proximity needs physical devices;
emulator↔emulator NSD works on one host). Tap a peer node → pick files → the
orb does the rest.

## Repo map

| Path | Purpose |
| --- | --- |
| `core/src/main/kotlin/com/crossdrop/core/crypto/` | X25519 session, record AEAD |
| `core/.../protocol/` | frame codec + JSON control messages |
| `core/.../transfer/TransferEngine.kt` | v1/v2 send/receive state machines |
| `core/.../delta/` | `Blake3`, `FastCdc`, `FileDeltaStore` — v2 chunk sync |
| `app/.../discovery/` | `NsdDiscovery`, `BleDiscovery`, `VaultDiscovery` |
| `app/.../net/` | `TransferServer` (listener), `TransferCoordinator` (UI state hub), `QrShareManager` + `LocalIp` |
| `core/.../share/` | `QrCode` (hand-rolled encoder), `HttpShareServer`, `share.html` web client |
| `core/.../vault/` | LAN vault: `VaultServer`/`VaultClient` (AEAD frames), `VaultHttp` + `vault.html` (browser drive), `VaultFs` (jail) |
| `app/.../ui/Root.kt` | app shell: tabs, nav bar/rail, overlays |
| `app/.../ui/radar/` | sonar canvas + screen |
| `app/.../ui/transfer/` | `LiquidOrb` + transfer screen |
| `app/.../ui/transfers/`, `ui/inbox/`, `ui/settings/` | tab screens |
| `app/.../storage/` | `InboxStore` (staging + shred), `HistoryStore` (transfer log) |
| `promo/` | Self-contained animated promo — open `index.html`, plays like a ~55s video loop |

## License

CrossDrop is released under the [Apache License 2.0](LICENSE).
