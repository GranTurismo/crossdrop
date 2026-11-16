# CrossDrop: feature ideas

Ideas agreed as worth building, with what the code already gives us and what is missing.
Noted 2026-11-16 against release 0.4.0; all four were built on 2026-11-17 (see Status).

Related: [CROSSDROP.md](CROSSDROP.md) (what exists today), [AGENTS.md](AGENTS.md) (architecture
and wire protocol).

---

## Status (2026-11-17)

Built on every platform, on branch `fix/api-compat-and-pin-throttle`, not released. Everything is
free (no paywall). Protocol additions are optional fields, so older versions keep working and
simply see ordinary files.

| | Android | macOS / Windows (desktop) | iPhone / iPad / Mac (iOS app) |
| --- | --- | --- | --- |
| Send to several devices | Send sheet "Also send to" chips; share sheet ticks | Pick devices for dropped / opened files | Send sheet chips; share extension ticks |
| Share from any app | Share target + Direct Share shortcuts | Drag onto window, Finder "Open With" / Dock (Mac), Explorer "Send to" (Windows MSI) | Share extension (`ios/CrossDropShare`) |
| Text and links | Send + receive (Copy / Open) | Send + receive | Send + receive |
| Live camera | In-app camera (CameraX) + viewer | Viewer only (no camera) | In-app camera (AVFoundation) + viewer |
| Switch phones | Send + receive (gallery, contacts) | Receive into `Pictures/CrossDrop/<phone> <date>/` | Send + receive (Photos, Contacts) |

How it fits together:

- `app/.../net/TransferHub.kt` (shared verbatim with desktop) and its Swift twin
  `ios/CrossDrop/Services/TransferCoordinator.swift` run many sessions at once, grouped per send,
  with per-device retry, and route offers by `Offer.kind` (`text`, `camera`, `switch`).
- "Accept once": the receiver returns a `TrustGrant` in its ACCEPT; later offers prove they hold it
  with HMAC-SHA256(token, session binding || transferId). Memory only, per kind, lapses after 30 min
  idle, and each transfer id is accepted once (`TrustStore` in both cores).
- Switch phones sends batches of 100 items / 512 MB (Android) or 50 items (iOS); finished batches go
  in a ledger, so a dropped connection resumes with the next one.

Verified:

- Kotlin core, desktop and Swift package tests (new: offer kinds, trust proofs incl. a cross-language
  known answer, vCard round trips, desktop multi-send and text, photo orientation).
- Android emulator against a Mac-side test peer (`crossdrop-cli` with the new `--kind/--each/--grant`
  flags): share sheet text and photo to two devices; receiving a link (Copy), a live camera feed
  (prompt once, then shots 2–3 arrive unprompted), a switch move (gallery with original dates,
  contacts added, re-send skips duplicates); sending a switch move (48 photos) and camera shots
  (trust reused); offer notification while backgrounded.
- iOS simulator: link prompt, live viewer, switch receive (Photos with original dates, Contacts,
  duplicates skipped), send to two devices at once, contacts export.
- Desktop: live-camera prompt shown in the running app.

Not verified (needs real hardware or a tap the tools here can't make):

- iOS photo-library export for "Switch phones" (the simulator wants a "full access" tap); the iOS
  in-app camera (no camera in the simulator); the iOS share extension end to end.
- Explorer "Send to" and Finder "Open With" (need an installed build); desktop live viewer with
  real phone photos.
- Large moves (tens of GB) and resuming one on real phones.

Decisions taken on the open questions below (easy to change):

- Every camera use is a live session (levels 1 and 2 merged): the first shot asks, the rest don't.
  Shots are always saved to the sender's own gallery as well.
- Shared links never open by themselves; the receiver taps Copy or Open.
- Android's share sheet stays up with per-device progress and closes itself when everything is
  delivered. The iOS extension must stay open until done (an extension can't hand off to its app).
- A receiver can take offers from several senders at once; one prompt shows at a time.
- No cap on how many devices one send goes to yet.

Known limits:

- The iOS share extension can't read the name chosen in the app (no shared App Group), so
  receivers see the system name ("iPhone").
- The Windows "Send to" entry isn't added for the Microsoft Store (MSIX) build, whose file writes
  are virtualised.
- Android release APK grew from 8.4 MB to 10.6 MB (CameraX), still under the 15 MB target.
- Contact notes don't move from iPhone (Apple restricts reading them).

---

## 1. Send to several devices at once

Pick more than one device on the radar and send the same files to all of them in one go.

### How it should behave

- Radar gets a multi-select: long-press a device (or a "Select" button), tap the others, then
  **Send to 3 devices**. Choosing files first and then tapping several devices should work too.
- Every receiver gets the normal offer and accepts or declines on their own.
- The sender sees one transfer screen with a row per device: waiting, accepted, declined,
  progress, done, failed. Each row shows that pair's own 6-digit verification code.
- When some devices fail or decline, **Retry** resends to those only.
- History records one entry per recipient.

### What exists today

- `TransferEngine.send(endpoint, files)` is one flow per connection, so the core can already run
  several sends in parallel. No wire-protocol change is needed and receivers on older versions
  work unchanged.
- "Send to anyone" (QR) already serves many receivers at once, but only into a browser.

### What has to change

- All three coordinators hold a single session. `TransferCoordinator.sendTo` cancels the running
  one before starting (`sessionJob?.cancel()`, one `_session`). Android, desktop and iOS each
  need a list of sessions instead.
- Transfer screen, minimised card and history need the per-device rows.
- Foreground service, wake lock and `SleepGuard` must stay held until the last session ends.
- On a first-time send each connection chunks and hashes the files itself (v3 push), so CPU work
  multiplies by the number of receivers. Hash once and share the chunk map between sessions.
- Wi-Fi airtime is shared, so each receiver gets a fraction of the speed. Cap the group (8?).

### Open questions

- Should a receiver be able to receive from two senders at once? Today a newer offer replaces an
  older pending one.
- Free, or part of a paid tier above a small group size?

---

## 2. Switch phones

Move everything from an old phone to a new one in a single run, including Android to iPhone and
iPhone to Android. Candidate for the paid unlock (free up to a size cap, one-time purchase above).

### What moves

| Data | Android | iOS | Notes |
| --- | --- | --- | --- |
| Photos and videos | MediaStore | PhotoKit | Originals, with dates. Live Photos become photo + video on Android. |
| Contacts | ContactsContract | Contacts framework | Carried as vCard. See below. |
| Files | Document picker / shared storage | Files the user picks | iOS has no "all files" access. |

Cannot move, on any app: messages, installed apps and their data, WhatsApp chats. iOS forbids it,
and on Android reading SMS needs the default-SMS-app role.

### Contacts between iOS and Android

Possible in both directions. vCard is the common format.

- **Android export**: `READ_CONTACTS`, then the contacts provider's vCard URI gives a `.vcf` for
  all contacts.
- **Android import**: either `WRITE_CONTACTS` and a batch insert, or hand the `.vcf` to the
  system Contacts app, which needs no permission but adds a confirmation step.
- **iOS export**: `CNContactStore` plus `CNContactVCardSerialization`
  (`NSContactsUsageDescription`).
- **iOS import**: parse with the same serializer and save through `CNSaveRequest`.

Limits to design around:

- iOS 18 and later let the user grant only selected contacts. A full move needs "Full Access".
- iOS leaves contact photos out of its vCard output, so they have to be added by hand. The notes
  field needs an entitlement Apple must approve; plan to lose notes going from iPhone.
- Groups, ringtones and custom labels do not carry over cleanly.
- Imported contacts land in one account (the phone's default). Let the user pick on Android.
- Duplicates: match on name plus phone or email before inserting, because the new phone often
  already has some contacts from an account.

### What exists today

- Resume and delta sync: an interrupted run continues instead of restarting, which is the main
  advantage over the official tools on large libraries.
- Contacts and media can travel as ordinary files over the existing protocol.

### What has to change

- A "Switch phones" flow on both sides: pick the other phone, choose what to move, one accept,
  one progress screen, a summary at the end.
- The receiver must import by type (gallery, contacts) instead of dropping everything in the
  Inbox. That needs a marker on the offer; older versions would simply receive files.
- A per-run ledger of finished items, so a restart skips what is already imported. The existing
  resume data lives beside Inbox files and is lost once a file moves into the gallery.
- New permissions. Android has none of these today: all-photos access (`READ_MEDIA_IMAGES`,
  `READ_MEDIA_VIDEO`) and contacts. iOS declares only local-network use: add photo library,
  photo add and contacts.
- iPhone has to stay open with the screen on for the whole run (transfers pause when suspended).
  Photos kept in iCloud in "optimised" form must be downloaded before they can be sent.
- In-app purchase on each store, plus updates to `PRIVACY.md` and the store privacy forms.

### Risks to check before building

- Google Play restricts all-photos access to apps whose core purpose needs it and asks for a
  declaration. A migration feature should qualify, but confirm before relying on it. The photo
  picker is not a substitute: it limits how many items one selection can hold.
- Google Play's data-safety form: device-to-device transfers that are end-to-end encrypted
  should not count as "collected" data. Confirm the wording when filling the form.
- Apple review will ask why the app wants full photo and contact access; the purpose strings
  must say "to move them to your other device".

---

## 3. Instant camera

Take a photo or video inside CrossDrop and have it land on the other device straight away,
without going through the gallery.

### How it should behave

Two levels, built in this order:

1. **Shoot and send.** The send sheet gets **Camera** next to Photos and Files. Take a photo or
   record a video, and it goes to the chosen device through the normal accept.
2. **Live session.** The receiver accepts once ("keep accepting from this device"), then every
   shot arrives the moment it is taken. A computer shows the latest shot large, so the phone
   works as its camera. Covers Android to Mac and iPhone to Windows, which Apple's own version
   does not.

- Video is sent when recording stops. It is not a live stream.
- With idea 1, one shot goes to every selected device.
- Shots are also saved to the phone's own gallery by default, so a declined or failed transfer
  never loses a photo.

### What exists today

- Captured photos and videos are ordinary files, so the transfer itself needs nothing new.
- Neither app uses the camera yet. Android declares no camera permission; iOS declares only
  local-network use.

### What has to change

- **Level 1, Android**: the system camera (`TakePicture` / `CaptureVideo`). No new permission
  and no growth in app size.
- **Level 1, iOS**: the system camera picker, plus camera and microphone purpose strings, plus
  photo-add permission for the gallery copy.
- **Level 2**: an in-app camera with a shutter that sends each shot (CameraX on Android, which
  adds to the app size; AVFoundation on iOS).
- **Level 2**: a safe "accept once". A device's ID changes on every launch and is advertised on
  the network, so it cannot be the thing that is trusted. Derive a secret from the first accepted
  transfer and have later connections prove they know it. That is an optional addition to the
  handshake; older versions ignore it and keep asking each time.
- **Level 2**: the session ends when either app closes or after a period without shots, and the
  receiver can stop it at any time.
- Desktop: a viewer that shows the newest received shot.

### Open questions

- Is level 2 free, or part of the paid tier?
- Should level 2 also offer document scanning (photo of a page arrives as a PDF)?

---

## 4. Share from any app

CrossDrop appears in the system share menu. Select a photo, video or file in any app, press
Share, pick CrossDrop, tap a device. The goal is the fewest possible taps and no detour through
the CrossDrop app itself.

### How it should behave

- Share opens a small device picker over the current app, already scanning. Tap a device and the
  transfer starts; the picker closes and progress continues in a notification.
- On Android, recently used devices appear directly in the system share menu, so sending to
  "your laptop" is a single tap after Share.
- A device used last time is shown first.
- Links and text can be shared too, and the receiver gets **Open** and **Copy** instead of a
  saved file. This gives "send this link to my laptop" without anything running in the
  background.
- With idea 1, several devices can be picked in the same sheet.

### What exists today

- **Android**: nothing. The manifest has only the launcher entry, so CrossDrop is absent from
  the share menu.
- **iOS**: files only. "Open in CrossDrop" from the Files app puts them on the Radar as a pending
  card until a device is tapped. There is no share extension, so the Photos app does not list
  CrossDrop.
- **Desktop**: nothing. `main()` ignores arguments, and a second launch only raises the window.

### What has to change

- **Android**: `ACTION_SEND` and `ACTION_SEND_MULTIPLE` filters for any type, handled by a light
  translucent activity that shows the picker as a bottom sheet. Read access to the shared items
  is temporary, so it has to be handed to the transfer service for the length of the transfer.
- **Android, one-tap targets**: sharing shortcuts for recent devices. Device IDs rotate on every
  launch, so a shortcut can only match by device name; when that device is not found within a
  couple of seconds, fall back to the picker.
- **iOS**: a Share Extension target in `project.yml` for images, videos, files, links and text.
  Send from inside the extension (mini radar, transfer runs there, sheet stays up until done)
  instead of handing off to the app, because an extension has no supported way to open its app.
  The engine streams, which suits the extension's tight memory limit. Check that the extension
  inherits the app's local-network permission.
- **Mac (App Store build)**: gets the same share extension through Catalyst.
- **Mac (DMG build)**: "Open With CrossDrop" and dropping files on the Dock icon, via document
  types and an open-files handler. A Finder Share-menu entry needs a native extension, which the
  Compose desktop app cannot easily ship.
- **Windows**: a "Send to" entry and an Explorer right-click item that pass file paths as
  arguments. `SingleInstance` must forward those paths to the running copy; today it accepts only
  the word "show". Treat the paths as untrusted input: they pre-fill the pending card and never
  send on their own.
- **Text and links**: a small new item kind in the offer, so the receiver can show Open and Copy.
  Older versions would receive a text file.
- Discovery starts cold when the app was not running, so the picker shows devices as they are
  found (about a second or two on a healthy network).

### Open questions

- After the transfer starts, return to the original app automatically or stay on a progress
  sheet?
- Does a shared link open by itself on a trusted device, or always wait for a tap?
