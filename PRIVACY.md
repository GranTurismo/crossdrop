# CrossDrop Privacy Policy

**Last updated:** November 2026 (scanning a code, "My devices" and backups to your computer)

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
- **Scanning a code** — "Scan a code" uses the camera only while that screen is
  open, to read another phone's code. CrossDrop then joins the private Wi-Fi link
  in the code (your phone asks you first) and connects to that phone. The link has
  no internet and ends when you leave it.
- **Shake to connect** — while the radar is on screen, CrossDrop reads the
  phone's motion sensor to notice a shake, then tells the devices already on your
  radar that this phone was just shaken. Nothing about the movement is stored or
  sent; turn it off in Settings.
- **Sharing apps (Android)** — when you tap "Apps", CrossDrop lists the apps
  shown in your launcher, on your phone only, so you can pick which to send. The
  other phone gets the app's installation files and nothing else: no data,
  accounts or purchases. Received apps wait in CrossDrop's private storage until
  you install each one through Android's own installer, which asks you first;
  whatever you don't install is deleted when you tap Done.
- **My devices (pairing)** — when you pair a phone with your computer, both
  people see the same 6-digit code and must agree. The two devices then keep a
  shared secret key in their own app storage so they can recognise each other
  later. It is never sent over the network or advertised, and a device that is
  not paired learns nothing from the check. Unpair at any time in Settings.
- **Backups to your computer** — a paired phone copies its photos and videos to
  that computer over your local network, encrypted like every transfer, and never
  anywhere else. The computer keeps them in a folder you can see and move. The
  phone remembers which photos the computer confirmed. **Free up space** deletes
  phone copies only after the computer has re-checked each file, and only after
  you confirm in Android's own dialog.
- **Accept once** — when you accept a live camera feed or a phone move, the two
  devices share a one-time secret so the following photos or batches don't ask
  again. It lives only in memory, ends when you close the viewer, finish the
  move or restart the app, and is never stored or sent anywhere else.

## What CrossDrop does not do

- No data collection, sharing, or sale — nothing leaves your local network, and nothing ever reaches us.
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
| Camera, microphone | Only while CrossDrop's camera screen is open: to take the photos and videos you send (the microphone records video sound). The camera is also used on the "Scan a code" screen, to read another phone's code. |
| Change network state (Android) | "Scan a code": join the other phone's private Wi-Fi link from inside the app. Android asks you before joining. |
| Hotspot configuration (iPhone) | "Scan a code": join the other phone's private Wi-Fi link. iOS asks you before joining. |
| Media location (Android) | So that photos you move or back up keep where they were taken. Without it, Android removes the location from the copy. CrossDrop doesn't read or store your location. |
| Photo library (full access) | For "Switch phones", to move your whole gallery to your other device, and for backups to your own paired computer. "Free up space" deletes photos only after you confirm in Android's dialog. |
| Add to photo library | To save photos and videos you receive in a phone move, and the shots you take in CrossDrop's camera. |
| Install apps (Android) | To install apps another Android phone sent you, through Android's installer, when you tap "Install". Android asks you to allow this once. CrossDrop never installs anything by itself. |
| Contacts (read and write) | For "Switch phones" (copy your address book to your other device, add contacts moved to this one) and for sending contacts: to list them so you can pick who to send (Android; iPhone uses the system picker instead), and to add contacts someone sent you when you tap "Add to contacts". Duplicates are skipped. |

## Contact

Questions or concerns: open an issue at
https://github.com/GranTurismo/crossdrop/issues
