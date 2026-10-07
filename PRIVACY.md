# CrossDrop Privacy Policy

**Last updated:** October 2025

## The short version

CrossDrop collects **no personal data**. It has no accounts, no servers, no
analytics, no ads, and no third-party SDKs that communicate off your device.
Everything the app does happens entirely on your local network or on-device.

## What CrossDrop does on your device

- **Peer discovery** — broadcasts a rotating, random device token over your
  local Wi-Fi network (mDNS/NSD) and Bluetooth Low Energy so nearby devices
  can find each other. The token resets on every launch and identifies
  nothing about you.
- **File transfer** — sends the files you choose directly to the device you
  tap, over a direct local TCP connection. Files never pass through a server
  and never leave your local network.
- **Encryption** — every transfer is protected with end-to-end encryption
  (X25519 key exchange + ChaCha20-Poly1305), verified on both devices with a
  6-digit code.
- **On-device storage** — received files, a local transfer log, and your
  chosen display name are stored only on your device. You can delete them at
  any time from within the app (Inbox and Settings).

## What CrossDrop does not do

- No data collection, sharing, or sale — nothing leaves your device.
- No tracking, analytics, crash-reporting SDKs, or advertising.
- No account, sign-in, email, or personal identifiers required.
- No internet connectivity is used or needed.

## Permissions explained

| Permission | Why |
| --- | --- |
| Nearby Wi-Fi devices / Bluetooth (advertise, scan, connect) | Discover and reach peers on your network — declared `neverForLocation`. |
| Location (Android 11 and older only) | Required by the OS for legacy BLE discovery; the app does not read or store your location. |
| Notifications | Show transfer progress and completion. |
| Photos / files (system pickers) | You choose exactly which files to send, each time. |

## Contact

Questions or concerns: open an issue at
https://github.com/GranTurismo/crossdrop/issues
