# CrossDrop: feature ideas

Ideas agreed as worth building, with what the code already gives us and what is missing.

- **Round 1** (noted 2026-11-16 against 0.4.0, all built 2026-11-17): send to several devices, Switch
  phones, instant camera, share from any app. Sections 1–4.
- **Round 2** (noted 2026-11-20 against 0.8.0): ideas meant to make people *love* the app, not just use it
  once. Sections 5–12, plus small delights and what we decided not to do. **5 and 6 are being built.**

Related: [CROSSDROP.md](CROSSDROP.md) (what exists today), [AGENTS.md](AGENTS.md) (architecture
and wire protocol).

### Why round 2 looks the way it does

Play Console (28 days to 2026-11-16): 75 installs but only 10 first opens and 11 monthly active
devices. Top countries: India, Egypt, Indonesia, Iran, where many people have mobile data and no home
Wi-Fi. The radar needs a shared Wi-Fi and it already *sees* phones over Bluetooth that it can't reach
(tapping such a phone did nothing). Our reading, not measured: the first experience often ends at an
empty radar. And nothing brings people back: a transfer is a one-off. So round 2 is ordered as
1) never a dead end, 2) a reason to come back weekly, 3) every use shows the app to new people.

Competitors move too: LocalSend 1.18 (Aug 2026) added "receive via link" (browser → device),
auto-accept from favourites and a CLI; Quick Share ↔ AirDrop works, but only on a short list of
flagships (Pixel 10, Galaxy S24–S26, a few Xiaomi/OnePlus/OPPO/HONOR/vivo).

| # | Idea | Why people would love it | Size | State |
| --- | --- | --- | --- | --- |
| 5 | Works anywhere (no Wi-Fi, no internet) | The empty radar disappears; first use works in a park | M | **building** |
| 6 | Back up my phone to my computer, then free up space | A weekly reason to open the app; no cloud, no subscription | L | **building** |
| 7 | Collect everyone's photos | Every use puts the app in front of 5–10 new people | M | idea |
| 8 | TVs on the radar | "Wow" moment; nothing to install on the TV | M | idea |
| 9 | Share apps, and pass CrossDrop on offline | The loop that grew SHAREit/Xender | M | idea (policy check first) |
| 10 | Shake to connect | Solves "which Galaxy is you?"; a 3-second demo clip | S | idea |
| 11 | Scan to computer (document → PDF) | Daily use for students and offices | S–M | idea |
| 12 | Appear in every Android's built-in share menu | Phones without the app can still send | L | idea (protocol risk) |

### Round 2 status (2026-11-20, uncommitted)

- **5. Works anywhere — built on Android, desktop and iPhone** (iPhone scans and joins; it can't host). Connect directly asks *Show a code* /
  *Scan a code*; the code carries the host's address (`CD:` field); Android scans with CameraX + ZXing, joins with
  `WifiNetworkSpecifier` and ANNOUNCEs itself; Bluetooth-only radar nodes open the same choice; "Linked with …" row
  with Leave. Verified: JVM tests (code format, ZXing still reads the network, announce through the real hub),
  Android 16 emulator against a Mac-side peer (real hotspot code carries `CD:`; real join → announce → offer received;
  Leave); iPhone simulator with a Swift CLI host (announce both ways, "Linked with …" row, host leaving), Kotlin⇄Swift
  interop tests for ANNOUNCE in both directions. Not verified: two real phones, the real iPhone join
  (`NEHotspotConfiguration`) and camera scan, iPhone cameras with the extra field.
- **6. Backup to a computer — built for Android → desktop.** Pairing with the 6-digit code ("My devices"), automatic
  backup while CrossDrop is open, month folders, re-check before "Free up space", unpair on both sides; photos now
  keep their GPS location (also fixes Switch phones). Verified: JVM/desktop tests end to end (incl. a stranger using
  the computer's name, an edited file failing the re-check), Android emulator against a Mac-side computer with three
  throwaway photos (paired, backed up, filed by month, freed through Android's dialog). Not built: iPhone sender,
  Android background job, several computers.
- Wire protocol: ANNOUNCE / PAIR / PAIR_CHECK / BACKUP_VERIFY frames and pairing proofs (AGENTS.md → Wire protocol).
  Older builds ignore them silently.

---

## 5. Works anywhere: no shared Wi-Fi needed

Two phones with no Wi-Fi in common (a park, mobile data only, a guest network that hides devices)
connect in two taps: one shows a code, the other scans it **inside CrossDrop**, and they are on each
other's radar.

### How it should behave

- "Can't see the other device?" → **Connect directly** asks one question: *Show a code* (this phone
  makes the private link; Android only) or *Scan a code* (Android and iPhone).
- *Scan a code* opens the camera. Scanning the other phone's code joins its private link (Android asks
  once: "Connect to AndroidShare_1234?"; iPhone asks "Join network?") and the other phone appears on
  the radar within a couple of seconds. **Both** directions work: the phone that showed the code sees
  the scanner on its radar too, so either side can send.
- Scanning any ordinary Wi-Fi code (a router's sticker) joins that network the same way; then normal
  discovery takes over.
- Tapping a phone the radar only hears over Bluetooth (it is near but not on your Wi-Fi) no longer
  does nothing: a sheet says so and offers *Show a code* / *Scan their code*.
- The guest leaves the link with **Leave** on the radar's "Connected to …" row, or by closing
  CrossDrop; its phone then goes back to its usual Wi-Fi. Phone cameras without CrossDrop keep
  working exactly as before (join the network, then "Send to anyone").

### What exists today

- Android hosts a local-only hotspot (`net/DirectLink`) and shows a standard `WIFI:` QR
  (`core/connect/WifiQr`). Proven on the emulator only.
- The guest side relied on mDNS over the hotspot interface, which is **unproven** (NSD on the AP
  interface differs by Android version and maker) and is not needed if the guest knows the address.
- In-app camera code (CameraX / AVFoundation) exists; there is no QR *decoder* (ZXing is test-only).
- The radar already shows Bluetooth-only peers, but `RadarScreen.startSendFlow` returns on them.

### What has to change

- **The code carries the host's address.** The `WIFI:` text gets one extra field,
  `CD:<ipv4>/<port>/<instance id>;`, before the closing `;;`. Phone cameras ignore unknown fields
  (ZXing and Android's own parser match known keys only), so the zero-app path is unchanged; CrossDrop
  reads the field and connects straight to the host without mDNS. The host's address is the IPv4 of its
  hotspot interface, found after the hotspot starts (the QR is enriched when it appears).
- **ANNOUNCE (new frame 0x20).** The guest connects to the host, does the normal encrypted handshake
  and sends `Announce{port}`; the host answers with its own. The host adds the guest to its radar as an
  *announced peer* (`core/discovery/AnnouncedPeers`, probed for liveness like mDNS peers, capped, dropped
  when it stops re-announcing). The guest re-announces every 15 s while joined. Older builds fail the
  unknown frame silently (no session was created, so nothing shows).
- **Android guest:** `net/WifiJoin` requests the network with `WifiNetworkSpecifier` (local-only, no
  internet; the system shows its own confirmation), binds the process to it while joined, connects and
  announces, and releases it on Leave. In-app scanner = CameraX `ImageAnalysis` + ZXing core's QR
  reader (a shipped dependency now: about 0.3 MB after R8; checked against the 15 MB budget).
- **iPhone guest:** `NEHotspotConfiguration` (joinOnce) + AVFoundation's built-in QR detection, then
  the same connect + announce. Needs the Hotspot Configuration entitlement. iPhones can't host.
- **Desktop:** nothing to scan with; it accepts announcements (shared hub), so a Mac/PC that joins a
  phone's link by name is still reachable once it sends, and phones reach it through the code's address.

### Decisions (easy to change)

- Announcements are accepted on any network, not only on a hotspot: any device on the network could
  already advertise any name over mDNS, and this also rescues networks that block multicast.
- No auto-send on join; joining only makes the devices visible to each other.

### Not verified until real hardware

- Two real phones: guest joins the host's hotspot and both radars show each other (emulators can't
  join each other's hotspot; the connect + announce part is tested between emulators and with JVM
  tests by skipping the Wi-Fi step, `-e dev_join_skip_wifi`).
- iPhone camera with the extra `CD:` field (expected to ignore it; not checked on a device).
- Makers that drop the host's own Wi-Fi when the hotspot starts (Samsung, Xiaomi/Oppo).

---

## 6. Back up my phone to my computer, then free up space

The phone's photos and videos copy themselves to *your* computer whenever both are on the same
network, organised by month, at full quality and with their dates and locations. Once the computer
has confirmed every byte, the phone offers to free up space by deleting copies older than a month.
No cloud, no account, no subscription.

### How it should behave

- **Pair once ("My devices").** Settings → *Back up to a computer* → pick the computer on the radar.
  Both screens show the same 6-digit code; the computer asks "Pair with Pixel 9?". From then on the
  two recognise each other even though their network identities rotate on every launch.
- **Back up.** The first run sends everything; later runs only what is new. It starts by itself when
  CrossDrop is opened and the computer is on the radar (and from *Back up now*); progress shows on the
  backup screen and as a quiet row on the radar. The computer files them under
  `Pictures/CrossDrop Backup/<phone>/<year>/<month>/` with original dates, skipping what it already has.
- **Free up space.** The backup screen says "1 240 photos and videos (8.4 GB) older than 30 days are
  safely on Gogita's MacBook" → *Free up 8.4 GB*. CrossDrop first asks the computer to re-check each
  file (it must be on the network and every file must still hash to what was sent), then Android's own
  delete confirmation appears. Nothing is ever deleted that the computer didn't just re-confirm.
- **Unpair** from either side (Settings → My devices). An unpaired device can't back up to you and
  its backup offers are declined without a prompt.

### What exists today

- Switch phones already reads the whole library (`switchphones/SwitchSender`), sends it in batches of
  ≤100 items / 512 MB with a resume ledger, and the desktop files it (`DesktopSwitchReceiver`).
- v2/v3 transfers end with per-file BLAKE3 roots both sides compare, so a finished batch *is* proof
  that the computer holds exactly those bytes.
- Trust between devices is memory-only (`TrustStore`) by design; identities rotate per launch.

### What has to change

- **Pairing (`core/pairing/`).** A new sealed `PAIR` exchange after the normal handshake; the human
  check is the existing 6-digit code. Both sides derive the pairing key from the session —
  `HKDF(shared secret, salt = session binding, "crossdrop/v1/pair/<pair id>")` — so the key never
  crosses the network. Stored per device (`PairingStore`, JSON in app storage; never in cloud backups).
- **Recognising a paired device without a stable id.** Nothing new is advertised. The phone asks each
  device on the radar `PAIR_CHECK{nonce, proofs}`, one HMAC per pairing it holds, each bound to the
  session and the nonce; a device that holds one of the keys answers with its own proof over the same
  nonce. Strangers learn nothing linkable (a proof is random-looking and different every session) and
  a recorded answer can't be replayed (fresh nonce).
- **Backup offers.** `Offer.kind = backup`, `FileMeta.key` (the phone's stable item id) and
  `Offer.pairProof`; the computer accepts without a prompt only when the proof matches a pairing, and
  answers with `Accept.pairProof` over the transfer id, which the phone **checks before sending a byte**
  (a device that merely uses the computer's name gets nothing). Unpaired backup offers are declined
  silently.
- **Ledger (`core/backup/BackupLedger`).** Append-only file per pairing on the phone: item key, size,
  BLAKE3 root, when. Written only after a batch completes with matching roots.
- **Verify before delete (`BACKUP_VERIFY`, new frame).** Phone sends keys + roots + a nonce; the
  computer re-hashes each file from its backup index and answers with the keys it still holds intact,
  plus a proof over the nonce. Only those are offered for deletion
  (`MediaStore.createDeleteRequest`, Android 11+; the system asks the user).
- **Locations.** Android strips GPS from photos read without `ACCESS_MEDIA_LOCATION` +
  `MediaStore.setRequireOriginal`; both are added (this also fixes Switch phones, which lost
  locations until now).
- **Desktop receiver.** `backup/DesktopBackupReceiver`: staging per batch → month folders → index
  (`backup-<pair id>.json`: key → path, size, root) used for dedupe and for verification.

### Decisions (easy to change)

- Phone → computer only for now (the desktop app is the backup target; iPhone as a sender comes after
  Android, through PhotoKit like Switch phones).
- Photos and videos only (no WhatsApp/other folders yet). Default "free up" age: 30 days.
- Starts automatically only while CrossDrop is open (Android also gets a background job later: a
  `JobScheduler` job on unmetered networks that looks for the paired computer and sends what's new).

### Risks

- Deleting is the one destructive thing CrossDrop does. Guarded by: computer re-hash right before
  deleting, mutual pairing proofs bound to a fresh nonce, Android's own confirmation dialog, and a
  minimum age.
- Play's photo/video permission declaration already covers full library access (Switch phones); the
  backup use must be added to the declaration text.

---

## 7. Collect everyone's photos

At a wedding, trip or party, one person shows a code; everyone scans it — with CrossDrop or just the
phone's browser — adds their photos at full quality and leaves with the whole set. Every use shows
the app to 5–10 people who don't have it.

- **First step: "Receive from anyone"** (browser → this device). `HttpShareServer` is GET-only today;
  it needs a small upload page and a `POST` path (the Wireless Drive's `VaultHttp` already has
  multipart upload, Range and a jail to reuse). LocalSend shipped this in 1.18.
- Then: an album session = upload + download in the same page; duplicates dropped by BLAKE3; the host
  sees who added what; "Download all" as a ZIP (exists for shares).
- Open questions: cap per guest? moderation (host removes a photo)? how long the album lives.

## 8. TVs on the radar

Smart TVs appear on the radar; tap one, pick a video or photos, and it plays on the TV with nothing
installed there.

- Many Samsung, LG and Sony sets are DLNA/UPnP renderers: discover with SSDP, then tell the TV to play
  a URL our Wireless Drive server already serves with Range (`VaultHttp`). Needs an SSDP client, a SOAP
  `AVTransport` call and media-format checks (TVs refuse what they can't decode).
- Google TV / Fire TV sticks don't render DLNA reliably: a small CrossDrop TV app (Android TV,
  `LEANBACK_LAUNCHER`) receives like any device and opens the file. Also a store listing in the TV
  category, where "send files to TV" apps have large audiences.

## 9. Share apps, and pass CrossDrop on offline

Send an installed app to a friend's phone, and let any phone hand over CrossDrop itself without the
internet (the receiver scans a code, the browser downloads the APK from the sender).

- APK serving reuses the QR share (`HttpShareServer`) over the direct link (idea 5).
- Installing on the receiver needs `REQUEST_INSTALL_PACKAGES`, and listing apps needs
  `QUERY_ALL_PACKAGES`: both need a Play declaration and P2P sharing must be the app's core purpose.
  **Check the policy before building.** Split APKs (most Play apps) need bundling into one `.apks`
  and an installer session on the receiver.

## 10. Shake to connect

Two people shake their phones at the same moment; each radar highlights the other.

- Accelerometer peak → the BLE advert carries a short-lived "shook at t" flag; the other phone matches
  timestamps within ~1.5 s. Android only (iOS has no BLE layer). A delight and a demo clip, not a need.

## 11. Scan to computer

Point the phone at a page; a cropped, flattened PDF lands on the computer.

- Builds on the live camera's accept-once trust. Android: ML Kit document scanner (Play services,
  check size/privacy) or our own edge detection; iOS: VisionKit `VNDocumentCameraViewController`.

## 12. Appear in every Android's built-in share menu

Phones that never installed CrossDrop could send to a Mac, Linux PC or iPhone running CrossDrop
through Android's Quick Share.

- NearDrop / rquickshare show receiving works on the same Wi-Fi (Android wants a BLE broadcast before
  it shows a non-Google device). Large build (UKEY2 handshake, protobuf frames) and Google may change
  the protocol at any time. Desktop-first.

## Small delights (cheap, any time)

- A shareable transfer receipt card: "4.2 GB in 58 s · 0 MB of mobile data used".
- A lifetime counter in Settings: "moved without the internet: 38 GB".

## Decided against (for now)

- **Internet relay / cloud transfer**: costs money per byte and breaks "nothing leaves your network".
- **Chat**: a different product; keeps the app small and focused.

## Also noted

- Other GitHub projects are also called "CrossDrop" (PlutoHDDev/CrossDrop, built on NearDrop;
  medformatik/crossdrop), which hurts searchability.

---

# Round 1

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
| Send a contact (on its own) | Pick contacts in-app, or Share from Contacts; receive → "Add to contacts" | Drop/open a `.vcf` to send; receive → saved to the inbox | System contact picker, or Share Contact; receive → "Add to contacts" |

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

### Sending a contact on its own (added 2026-11-17)

Besides the whole address book in a move, one or a few contacts can be sent like anything else:
the send sheet has a **Contact** tile (Android: CrossDrop's own searchable, multi-select list,
needs contacts access; iPhone/iPad/Mac: the system contact picker, no permission). A contact
shared from the Contacts app (Android "Share", iOS "Share Contact") or any `.vcf` picked as a
file goes the same way.

- Wire: offer kind `contact`, one `.vcf` (vCard 3.0 with photo), plus a photo-less copy in
  `Offer.text` (≤ 64 KB) so the prompt can show names and numbers before accepting. Older
  receivers just get the `.vcf`. A `.vcf` over 8 MB is treated as a plain file (a backup).
- Receiver: "Add to contacts" asks for contacts access right then if needed; cards are added with
  the same duplicate check as Switch phones, and a small card says "Added Jane Doe" with **View**
  (opens the contact) or "already in your contacts". Without access the `.vcf` is kept in the
  Inbox and the card offers **Allow** (Settings once the OS stops asking). Desktops save the
  `.vcf` to the inbox ("Save").
- Verified on the emulator and simulator in all directions (Mac CLI ↔ Android, Android ↔ iPhone),
  including the photo, the duplicate skip, View, the no-access path on iPhone, and Android's
  share menu with a Contacts-provider vCard. Not verified: iOS "Share Contact" into the share
  extension (no tap tool), the Mac Catalyst picker.

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
