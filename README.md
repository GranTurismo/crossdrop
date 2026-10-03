# CrossDrop

Ad-free, privacy-first local file sharing between nearby devices. Android first;
the protocol core (`:core`) is platform-neutral Kotlin designed for later
replacement by a shared Rust engine (iOS parity).

## What works today

- **Sonar radar UI** — ambient ripple canvas; discovered peers orbit as nodes,
  BLE-derived RSSI controls radial distance, tap to connect.
- **Discovery, two transports** — mDNS/NSD on the local LAN plus BLE 5.0 beacon
  (service UUID `cd0d5e55-…`, rotating instance token), merged into one peer map.
- **Encrypted transfer** — per-session X25519 ECDH → HKDF → ChaCha20-Poly1305
  record layer, with a 6-digit short-auth-string shown on both devices.
- **Liquid orb progress** — fluid level = bytes transferred, wave turbulence
  tracks throughput, accelerometer sloshes the surface, completion crystallizes.
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
| `core/.../transfer/TransferEngine.kt` | send/receive state machines |
| `app/.../discovery/` | `NsdDiscovery`, `BleDiscovery` |
| `app/.../net/` | `TransferServer` (listener), `TransferCoordinator` (UI state hub) |
| `app/.../ui/radar/` | sonar canvas + screen |
| `app/.../ui/transfer/` | `LiquidOrb` + transfer screen |
