# CrossDrop Privacy Policy

**Last updated:** November 2026 (language list and "Connect directly")

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
- **Camera** — when you open CrossDrop's camera, each photo or video you take
  is saved to your own gallery and sent straight to the devices you picked.
  The camera is only on while that screen is open.
- **Switch phones** — when you start a move, CrossDrop reads your photos,
  videos and contacts and sends them directly to the one device you chose, over
  the same encrypted local connection. On the receiving device they are added to
  its gallery and address book. Nothing is copied anywhere else, and the
  temporary copies made during the move are deleted as soon as each item is in
  place.
- **Sending a contact** — on Android, CrossDrop lists your contacts on its own
  screen so you can pick who to send; on iPhone and iPad the system's contact
  picker is used and CrossDrop only sees the people you pick. Only the contact
  cards you pick are sent, directly to the device you chose. A device that
  receives a card adds it to its address book when you tap "Add to contacts"
  (people already there are skipped); a computer saves it as a `.vcf` file in
  CrossDrop's inbox.
- **Accept once** — when you accept a live camera feed or a phone move, the two
  devices share a one-time secret so the following photos or batches don't ask
  again. It lives only in memory, ends when you close the viewer, finish the
  move or restart the app, and is never stored or sent anywhere else.

## What CrossDrop does not do

- No data collection, sharing, or sale — nothing leaves your device.
- No tracking, analytics, crash-reporting SDKs, or advertising.
- No account, sign-in, email, or personal identifiers required.
- No internet connectivity is used or needed.

## Permissions explained

| Permission | Why |
| --- | --- |
| Nearby Wi-Fi devices / Bluetooth (advertise, scan, connect) | Discover and reach peers on your network — declared `neverForLocation`. |
| Location (Android 12 and older only) | Required by the OS for legacy BLE discovery (Android 11 and older) and, on Android 12 and older, before an app may create the temporary private Wi-Fi link behind "Connect directly" (asked only when you use that feature, after an explanation). The app does not read or store your location. |
| Change Wi-Fi state (Android) | "Connect directly": creates a temporary private Wi-Fi link (no internet is shared) so a device with no shared Wi-Fi can join it by scanning a QR code. It stops when you stop it or after 30 minutes. |
| Notifications | Show transfer progress and completion, and offers that arrive while the app isn't open. |
| Photos / files (system pickers) | You choose exactly which files to send, each time. |
| Camera, microphone | Only while CrossDrop's camera screen is open: to take the photos and videos you send (the microphone records video sound). |
| Photo library (full access) | Only for "Switch phones", to move your whole gallery to your other device. |
| Add to photo library | To save photos and videos you receive in a phone move, and the shots you take in CrossDrop's camera. |
| Contacts (read and write) | For "Switch phones" (copy your address book to your other device, add contacts moved to this one) and for sending contacts: to list them so you can pick who to send (Android; iPhone uses the system picker instead), and to add contacts someone sent you when you tap "Add to contacts". Duplicates are skipped. |

## Contact

Questions or concerns: open an issue at
https://github.com/GranTurismo/crossdrop/issues
