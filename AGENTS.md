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

## Wire protocol v1

`[u32 BE len][u8 type][payload]`; HELLO/HELLO_ACK cleartext JSON, everything
after is sealed per-record (ChaCha20-Poly1305, nonce = u64 counter).
Sequence: HELLO → HELLO_ACK → OFFER → ACCEPT/DECLINE → DATA* → DONE → DONE
(receiver echoes its SHA-256; sender compares). DATA payload = `[u32 fileIndex][bytes]`;
an empty DATA frame marks file start.

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

- Radar: "loopback self-test" (send 4 MiB to self) and "add peer by address"
  links; own port shown under device name.
- `adb -s A shell "cmd activity start -n com.crossdrop.debug/com.crossdrop.MainActivity -e dev_peer 10.0.2.17:<BPORT>"`
  injects a radar peer (tap it → file picker → real sendTo).
- `-e dev_send host:port` fires the self-transfer at that endpoint directly.
- `pm grant com.crossdrop.debug android.permission.{NEARBY_WIFI_DEVICES,
  BLUETOOTH_SCAN,BLUETOOTH_ADVERTISE,BLUETOOTH_CONNECT,POST_NOTIFICATIONS}`
  skips the runtime dialog.
- `am start` is unreliable on the Android 36.1 image — use `cmd activity start`.

## Known debt / next phases

- BouncyCastle is the bulk of APK size; swap for Conscrypt X25519 (API 31+)
  or a small curve25519 impl to approach the <15 MB target.
- Radar angle is hash-stable; compass-heading alignment not wired yet.
- Stream-play (loopback HTTP + ExoPlayer), acoustic handshake, multipath,
  delta sync (FastCDC/BLAKE3), ephemeral dropboxes, Wi-Fi Direct: not built.
- BLE sightings can't be tested on emulators — need two physical devices.
