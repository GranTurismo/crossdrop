# Shipping CrossDrop on iPhone, iPad and Mac (App Store)

One target, three platforms: iPhone + iPad natively, Mac via **Mac Catalyst**
(`supportedDestinations: [iOS, macCatalyst]` in `project.yml`). Same bundle id
(`com.granturismo.crossdrop`) ⇒ one App Store Connect record with *Universal Purchase*.
The existing `:desktop` Compose app (DMG) is unaffected and independent.

## One-time setup

1. `brew install xcodegen` (already installed on the dev Mac), then
   `cd ios && xcodegen generate` — the `.xcodeproj` and `Info.plist` are generated and gitignored.
2. Put your Apple Developer **Team ID** in `ios/project.yml` → `settings.base.DEVELOPMENT_TEAM`
   (or pick the team in Xcode → target → Signing; automatic signing is on). Regenerate.
3. App Store Connect → new app, bundle id `com.granturismo.crossdrop`, platforms iOS (+ macOS via Catalyst:
   add the macOS platform to the same record).
4. Bump `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in `project.yml` for every upload.

## Build & upload

Xcode ≥ 16, open `ios/CrossDrop.xcodeproj`:

- **iPhone + iPad:** destination *Any iOS Device (arm64)* → Product ▸ Archive → Distribute App ▸ App Store Connect.
- **Mac:** destination *My Mac (Mac Catalyst)* → Product ▸ Archive → Distribute App ▸ App Store Connect
  (Xcode signs with the sandbox entitlements in `CrossDrop/CrossDrop-Mac.entitlements`, hardened runtime
  on; App Store Connect handles notarisation).

CLI sanity check without signing (what CI can do): 

```sh
cd ios && xcodegen generate
xcodebuild -project CrossDrop.xcodeproj -scheme CrossDrop -configuration Release \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project CrossDrop.xcodeproj -scheme CrossDrop -configuration Release \
  -destination 'platform=macOS,variant=Mac Catalyst' CODE_SIGNING_ALLOWED=NO build
```

## App Store Connect answers

- **Privacy ("nutrition label"):** *Data Not Collected.* No tracking. Privacy manifest ships in the app
  (`CrossDrop/PrivacyInfo.xcprivacy`: UserDefaults CA92.1, file timestamps C617.1/3B52.1, system boot time
  35F9.1, disk space E174.1). Privacy policy URL: host `PRIVACY.md` (add a short iOS/Mac paragraph:
  Local Network + Photos permissions, no Bluetooth on Apple platforms).
- **Export compliance:** `ITSAppUsesNonExemptEncryption` is deliberately **not** set, so App Store Connect asks.
  The app uses standard algorithms via Apple CryptoKit (X25519, HKDF-SHA256, ChaCha20-Poly1305) for
  file-transfer confidentiality. Decide the self-classification yourself; once decided, add
  `ITSAppUsesNonExemptEncryption: true|false` to `project.yml` to stop the per-upload question.
- **Permissions strings** (in `project.yml`): Local Network (+ Bonjour services `_crossdrop._tcp`,
  `_crossdrop-vault._tcp`), Photo Library (picking media to send). Notifications are requested after the
  first finished transfer.
- **Review notes (paste):** "CrossDrop transfers files directly between devices on the same Wi-Fi — no
  accounts, no servers. The Local Network prompt is required to find nearby devices. A single device can be
  reviewed with *Send to anyone* (shows a QR/URL; open the URL in any browser on the same network) and
  *Settings ▸ Turn into drive* (WebDAV/browser drive). Device-to-device sending needs two devices running CrossDrop."
- **Age rating / category:** Utilities, 4+. No ads, no purchases, no user-generated content.
- **Screenshots:** iPhone 6.9" (1320×2868) and 6.5", iPad 13" (2064×2752), Mac (≥1280×800, 16:10).

## Platform notes

- **iPad:** all four orientations, multitasking allowed (no `UIRequiresFullScreen`).
- **Mac (Catalyst):** App Sandbox with `network.client`, `network.server` (Bonjour + inbound transfers) and
  `files.user-selected.read-write` (file/folder pickers, drag & drop). No Bluetooth, no accelerometer
  (the orb animates without tilt). Received files live in the app container (`Documents/Received`).
- **No BLE** on any Apple build — the radar uses Bonjour only (distance rings are decorative).
- Transfers pause when an iPhone/iPad app is suspended; the Mac app keeps running while its window is open.

## Never verified on hardware

Everything was exercised on the iOS/iPadOS simulators, Mac Catalyst on the dev Mac, `dns-sd`, and
Swift⇄Kotlin interop tests. Not yet run on a physical iPhone/iPad or against a real Android device:
the first-launch Local Network prompt flow, background suspension, Wi-Fi switching, Personal Hotspot
addressing. Do a TestFlight pass on real devices before submitting.
