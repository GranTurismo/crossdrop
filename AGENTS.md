# CrossDrop — agent notes

Cross-platform (Android-first) local Wi-Fi file sharing. Full spec lives in the
project brief; pillars: ad-free/privacy-first, line-rate local speed, <15 MB
installed size, ambient "Sonar" UX (radar, glass, one mint accent).

## Layout

- `:core` — pure Kotlin/JVM, no Android deps. Wire protocol (frame codec +
  X25519/HKDF/ChaCha20-Poly1305 session crypto), `PeerDiscovery` interface,
  `TransferEngine` (send/receive over a single TCP socket). Designed so a
  Rust/UniFFI engine can replace internals later without touching the app.
- `:app` — Android shell (Kotlin 2.2 + Jetpack Compose, minSdk 29). NSD and
  BLE both implement `PeerDiscovery`; sightings merge by `instanceId`
  (rotating per-launch token) in `AppContainer.peers`.

- `:desktop` — macOS + Windows + Linux app (Compose Multiplatform Desktop on `:core`). Same wire
  protocol, same QR share, same Wireless Drive (HTTP/WebDAV/AEAD), same radar UI.
  See "Desktop (macOS)" below.

- `ios/` — iPhone/iPad app (SwiftUI, iOS 16+). `ios/CrossDropCore` is a SwiftPM
  port of `:core` (wire-compatible — interop-tested against the JVM engine);
  `ios/CrossDrop` is the shell. See "iOS" below.

## App structure

Four tabs behind a floating glass pill nav (`FloatingNav` in `ui/Root.kt`; the
active tab expands into an accent capsule): **Radar** (discovery + tap-to-send
sheet + "Send to anyone" card + drive rows), **Transfers** (live session card +
persisted history via `storage/HistoryStore`, grouped Today/Earlier),
**Inbox** (storage breakdown, type filters, received files), **Settings**
(identity, NSD/BLE toggles, "Turn into drive", storage, about, debug tools).
The transfer view is a full-screen overlay that can be minimised to the
Transfers tab (its card reopens it; a minimised session that ends is dismissed).
Inbound offers are a full-screen layer on top with an auto-decline countdown
(`TransferCoordinator.OFFER_TIMEOUT_MS`); system Back declines. History records
each terminal session once — direction is taken from the emitting call site
(not `_session`, which can hold the opposite leg of a loopback).

## Where received files go (the inbox)

Received files are ordinary files in the user's **Downloads**, in a `CrossDrop` folder, on every platform
that allows it. Bookkeeping (resume pool `.staging/`, delta indexes `.idx/`) never sits beside them:
`FileDeltaStore(inboxDir, stateDir)` keeps it in `stateDir` (dot-folders aren't hidden on Windows).

- **Desktop**: `AppDirs.inbox` = `<Downloads>/CrossDrop` (`AppDirs.downloadsDir`: `~/Downloads`; on Windows the
  `User Shell Folders` registry value, because OneDrive/other-drive redirection is common), falling back to the
  old `support/inbox` only if Downloads isn't writable. State is `AppDirs.inboxState`. `InboxStore.migrateFrom`
  moves the pre-Downloads inbox across once at launch. `-Dcrossdrop.inbox=<dir>` overrides it for dev/tests.
  macOS asks once for Downloads access (TCC) on first write.
- **Android** (scoped storage: the engine can't write `Download/` with plain files): a receive is assembled in the
  private `filesDir/inbox` (plus resume pool/indexes), then `InboxStore.publish(file)` moves it into
  `Download/CrossDrop/` through MediaStore (no permission; the app owns what it publishes and can list, open, shred
  and delete it; MediaStore numbers name clashes). `ReceivePort.received(files)` is the hub hook (plain-files
  receives only; no-op on desktop). `list()` merges MediaStore rows with anything still in the private dir (a failed
  publish, a card kept for contacts, live camera shots) so nothing vanishes. `migrateToDownloads()` publishes the
  old private inbox once (pref `downloads_migrated`). Because a published file is no longer a local baseline, a
  re-send of the same name transfers in full (no delta). Files from a previous install aren't ours to list.
- **iOS**: iPhone/iPad apps can't write the system Downloads folder, so it stays `Documents/Received` (the Files
  app lists it: `UIFileSharingEnabled`). **Mac Catalyst** uses the real `~/Downloads/CrossDrop` (sandbox entitlement
  `com.apple.security.files.downloads.read-write`), state in Application Support, and moves the old
  `Documents/Received` across on first launch.
- "Wipe all" shreds every file in that folder, including ones the user put there.

## Look & feel ("Sonar" design)

Implemented from the "CrossDrop mobile app" design canvas: near-black ink
`#06080C`, one mint accent `#5CF2C0`, translucent white "glass" cards, amber /
violet / sky only for file types and status. Sora (display) and JetBrains Mono
(figures) are bundled as Latin subsets, SemiBold only (~90 KB, OFL notice in
`assets/licenses/FONTS-OFL.txt`); body text is the platform sans.

- Tokens + `displayStyle`/`monoStyle` live in `ui/theme/Theme.kt`. Font families are
  per-platform (`ui/theme/Fonts.kt` in `:app` from `res/font`, in `:desktop`
  from resources).
- Building blocks are shared: `ui/Components.kt` (GlassCard, GroupCard,
  PillButton, CdSwitch, BackHeader, SegmentedTabs, ConfirmDialog, …) and
  `ui/CdIcons.kt` (stroke icons built from the design's SVG path data — no
  material-icons). `LocalNavInset` tells scrolling tabs how much the floating
  nav covers.
- `Modifier.dropShadow` paints its whole blurred shape *behind* a view, so a
  glow under a translucent fill bleeds through; `GlassCard` lays an ink base
  under glowing glass. `monoStyle` turns ligatures off (`://` must stay as typed).
- Radar nodes are real composables (focusable, TalkBack labels), not canvas
  hit-testing; device glyphs are guessed from the advertised name.
- App icons everywhere are the brand mark (mint ring + dot) on ink with a glow,
  radar sweep and two faint sonar rings. Raster icons (iOS AppIcon + LaunchMark,
  desktop `icon.png`/`tray.png`/`.icns`/`.ico`, `store-assets/crossdrop_icon.png`) come
  from `tools/icons/render_icons.py` (numpy + pillow; `iconutil` for `.icns`) — edit
  the script, don't hand-edit the PNGs. Android's adaptive icon is vector
  (`res/drawable/ic_launcher_*.xml`, same geometry, mark at 0.43 of the canvas)
  and doubles as the Android 12+ splash icon (`values-v31/themes.xml`).

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
**Live updates**: a guest's explorer follows the host's folder without a refresh. Every 1.5 s
`VaultExplorerManager` (Android, desktop, iOS) re-lists the open folder on its existing connection and only
touches state when the listing differs (files added/removed/renamed/grown on the host, or uploaded by another
guest; selection keeps only paths that still exist; a dead socket is re-dialled quietly). It pauses while the app
is in the background (`livePaused`, set from `MainActivity` onPause/onResume and iOS `didEnterBackground`/`resume`).
The browser page (`vault.html`, and its byte-identical Swift copy `VaultPage.swift` — regenerate it with the
formula in the file header whenever the HTML changes) polls `/api/fs` the same way while the tab is visible.
It is poll-based on purpose: it works against every host version (listings are never cached; SAF lists live).
Test: `DesktopWiringTest.aGuestSeesFilesAppearInAHostedDriveWithoutRefreshing`. Cost to watch: a SAF-backed
host lists via one `DocumentFile` query per attribute per child, so a huge shared folder makes each poll slow.
Client side: `VaultClient` + `VaultMediaBridge` (127.0.0.1 HTTP→pull bridge so
players get Range streaming over the AEAD session).

UI: Settings → "Turn into drive" opens `ui/drive/DriveHostScreen` — start
card (vault vs SAF folder, guests-edit toggle, passcode toggle) → live card
("● Drive is on", shared-folder summary, QR, copyable address + passcode,
"Stop Sharing Drive") → `DriveGuideScreen` (Mac Finder ⌘K / Windows network
location / browser-Safari tabs + troubleshooting — no iOS Files SMB tab:
unrooted Android can't serve SMB2/3). Inbound vaults still mount via the
radar beacon → `VaultExplorerScreen`.

## Desktop (macOS)

`:desktop` mirrors `:app`'s architecture with desktop implementations behind the
same class names (`AppContainer`, `TransferCoordinator`, `QrShareManager`,
`VaultHostManager`, `VaultExplorerManager`, `HistoryStore`, `InboxStore`).

- **Shared UI**: `desktop/build.gradle.kts` `syncSharedUi` copies the
  platform-free Compose files (theme, radar canvas, icons, offer
  sheet, history, `Components.kt`, `CdIcons.kt`…) out of `:app` at build time — one
  source of truth. The desktop screen copies follow the Android Sonar screens
  line for line except for platform hooks (native file/folder pickers instead of
  the photo picker/SAF, Finder/Explorer reveal in the inbox, "Rescan network"
  instead of the BLE toggle, no runtime permissions or haptics); the shell puts the
  glass pill nav on its side as a left rail (`SideNav`). Esc = Back via CMP's
  `androidx.compose.ui.backhandler.BackHandler` (`libs.compose.ui.backhandler`).
  Overlays can be checked headlessly with `ImageComposeScene` in a throwaway test. Files that
  touch platform APIs (Radar/Inbox/Drive/Vault/Settings/Transfer/QrShare screens,
  Root) are desktop copies under `desktop/src/main/kotlin/.../ui`; when you change
  one on Android, port the change. Shared files must stay free of `android.*`
  (the only tolerated call, `android.util.Log`, is rewritten by the sync filter).
- **Discovery**: JmDNS, one shared responder (`MdnsHub`, re-binds when the Wi-Fi
  IP changes) used by `MdnsDiscovery`/`VaultDiscovery`/`VaultAdvertiser`. Same
  service types + TXT keys as Android NSD, so phones and Macs see each other.
  **No BLE** on desktop (no pure-JVM BLE) — BLE only affected radar distance anyway.
- **Platform bits** (`platform/`): Application Support dirs (the inbox itself is in Downloads, see "Where
  received files go"), `Prefs` (properties file), native `FileDialog` pickers (incl. folder mode), Finder reveal,
  `SleepGuard` (`caffeinate -w <pid>` while transferring/serving = WakeLock).
- **Drive**: pick any folder → `FileVaultFs(folder, stagingRoot=…)` (staging kept
  out of the shared folder). `VaultHostManager.isShareable` refuses `/`, `~`,
  `~/Library` and CrossDrop's own state.
- **App lifecycle**: closing the window hides to the menu bar (listener keeps
  running); inbound offers raise the window + notify; ⌘Q / tray Quit exits.

```sh
./gradlew :desktop:run          # dev run (add -Dcrossdrop.debug=1 via JAVA_TOOL_OPTIONS for logs)
./gradlew :desktop:test         # headless: real send/receive, drive over HTTP, share guard
JAVA_HOME=<full JDK 21> ./gradlew :desktop:packageDmg   # needs jpackage (Android Studio's JBR lacks it)
```

DMG is unsigned by default (right-click → Open on first launch). Developer ID signing +
notarization is opt-in via `MACOS_*` env vars (`desktop/build.gradle.kts`, `:desktop:notarizeDmg`,
`.github/workflows/desktop-macos.yml`) — see `desktop/RELEASE.md`. The macOS bundle version is
derived from `APP_VERSION` in `Main.kt` (0.x.y → 1.x.y, must be ≥1), same as the Windows one. First launch triggers the macOS
Local Network prompt — "Rescan network" in Settings re-binds mDNS after approving.

## Desktop (Windows)

Same `:desktop` module, one codebase. Everything OS-specific sits behind
`platform/Platform.kt` (`Platform.current` = MAC / WINDOWS / OTHER; `computerNoun` and
`fileManager` feed the UI strings: "Turn this PC into a drive", "Show in File Explorer").

- **State**: `%LOCALAPPDATA%\CrossDrop` (`AppDirs.baseDir`; local, not roaming).
- **Discovery**: unchanged JmDNS path. Windows Firewall asks once on first launch (allow
  *private* networks); "Rescan network" in Settings re-binds mDNS afterwards. `LocalIp.choose`
  skips Hyper-V/WSL/VMware/TAP/VPN/Bluetooth adapters by display name — but keeps the
  guest-side NIC inside a Hyper-V VM ("Microsoft Hyper-V Network Adapter"), or CrossDrop would
  see no network in Azure/Hyper-V VMs.
- **Platform bits** (`platform/`): Explorer `/select,` reveal; a system-look-and-feel Swing
  folder chooser (AWT `FileDialog` has no folder mode on Windows); `SleepGuard` =
  `SetThreadExecutionState(ES_CONTINUOUS|ES_SYSTEM_REQUIRED)` via JNA on a dedicated thread
  (the flag is per-thread, so set and clear must share it — a crash releases it); tray icon +
  toast via AWT `SystemTray` (when the tray is unsupported, closing the window quits instead
  of stranding the app); `SingleInstance` (exclusive `instance.lock` + loopback `show` signal)
  so launching again raises the tray-hidden window instead of starting a second copy.
- **Names from peers are untrusted** — Windows adds rules beyond `..` and `/`:
  `InboxStore.sanitizeName` (no `:` = NTFS alternate data stream, no `CON`/`NUL`/`COM1`… device
  names, no trailing dot/space) and `FileVaultFs(windowsNames = …)` (default on exactly when
  `File.separatorChar == '\\'`): `VaultFs.isWindowsUnsafeSegment` on every segment, plus a
  hidden-entry re-check *after* canonicalization because Win32 expands 8.3 short names
  (`STAGIN~1` → `.staging`). `VaultHostManager.isForbiddenRoot` refuses drive roots, the
  profile and its ancestors, `AppData`, `Windows`/`Program Files`/`ProgramData`,
  `$Recycle.Bin`, `System Volume Information`, `Windows.old`, `Recovery`, other users' profiles
  (`Public` is allowed). A drive hosted from Windows rejects names Windows can't store with a 400
  naming the segment (like SMB/IIS) rather than silently renaming; case-only renames
  (`photo.jpg` → `Photo.jpg`) work via a temp name because canonicalization collapses the two.
- **Build** — installers can only be built *on Windows* (jpackage + WiX 3.x):

```sh
./gradlew :desktop:packageMsi :desktop:packageExe   # -> desktop/build/compose/binaries/main/{msi,exe}
./gradlew :desktop:run                              # dev run (JDK 17+)
```

  CI does this: `.github/workflows/desktop-windows.yml` (manual run, or push a `desktop-v*` tag),
  after running `:core:test` and `:desktop:test` on the Windows runner. The installer is a
  per-user install (no admin prompt) and **unsigned** — SmartScreen warns until it is
  code-signed. `upgradeUuid` in `desktop/build.gradle.kts` identifies the product to Windows
  Installer: **never change it** or upgrades install side by side. The MSI/EXE version is derived
  from `APP_VERSION` in `Main.kt` (0.x.y → 1.x.y): Windows Installer refuses to upgrade to the
  same ProductVersion, so **bump `APP_VERSION` for every Windows release**. `modules(...)` must
  keep `java.logging` — JNA needs it and nothing else drags it into the jlink image. The `-Xdock:*` JVM flags are
  macOS-launcher options that make any other JVM refuse to start, so the build adds them only
  when the build host is a Mac. For the Microsoft Store an MSIX (and a signing identity) is
  still needed on top of this MSI.
- **Not yet proven on a real Windows machine**: `SleepGuard`'s actual kernel32 call, the
  firewall prompt flow, mDNS interop on port 5353 alongside Windows' own responder, tray
  toasts, and the Explorer reveal. They are unit-tested through injected platform values and a
  fake kernel32; the Windows CI run executes the same suites natively.

## Desktop (Linux)

Same `:desktop` module again; `Platform.LINUX` (an unknown OS stays `OTHER`, dev-only). The Linux-specific code is in
`platform/LinuxDesktop.kt` (pure functions that decide what to run or where to look, unit-tested on any host in
`LinuxSupportTest`, plus the few functions that start a process) and a `LINUX` branch wherever Mac/Windows have one.

- **State**: `$XDG_DATA_HOME/CrossDrop` (`~/.local/share/CrossDrop`; a relative `XDG_DATA_HOME` is ignored, per the spec).
  **Inbox**: `<Downloads>/CrossDrop`, where Downloads is `XDG_DOWNLOAD_DIR` from `$XDG_CONFIG_HOME/user-dirs.dirs`
  (translated or moved folders); a value of `$HOME` itself means "switched off" and falls back to `~/Downloads`.
- **Open / reveal**: `xdg-open` first (AWT's `Desktop` only works when GTK/GNOME libraries load); reveal = the
  `org.freedesktop.FileManager1.ShowItems` D-Bus call through `dbus-send`, falling back to opening the folder.
- **File and folder pickers**: `zenity` (GNOME and most others) or `kdialog` (KDE sessions first), whichever is
  installed; else a Swing `JFileChooser` with the system look and feel. AWT's own `FileDialog` is a Motif box on Linux and
  has no folder mode. The wait pumps AWT events (a secondary loop) while a helper thread runs the picker — blocking the UI
  thread would make the window manager show "not responding".
- **Keep awake**: `systemd-inhibit --what=sleep:idle … tail --pid=<ours> -f /dev/null` (ends with us, like `caffeinate -w`);
  does nothing without systemd.
- **Single instance**: `SingleInstance` runs on Windows and Linux (a second launch raises the first).
- **Network**: `LocalIp` also skips libvirt (`virbr`, `vnet`), Docker user networks (`br-<hash>`), LXC/LXD/Incus, Podman,
  Kubernetes CNIs and VPN-named interfaces (`wg0`, `tailscale0`, `zt…`); `en*`/`wl*` (systemd predictable names) count as
  LAN. A plain `br0` bridge is kept: that is how a server or VM host attaches its real NIC.
- **Drive roots**: `VaultHostManager.isForbiddenRoot` refuses dot-folders under the home (`.ssh`, `.gnupg`, `.config`…),
  other users' homes, and system trees (`/etc`, `/usr`, `/proc`, `/run/user`…); `/run/media` (USB sticks on Fedora/Arch) is fine.
- **Tray**: AWT `SystemTray`. GNOME has no tray without the AppIndicator extension (Ubuntu ships it); `SystemTray.isSupported()` is
  then false and closing the window quits (the existing fail-safe) rather than hiding where it can't be reopened.
- **Packaging**: `.deb`, `.rpm` and a portable `.tar.gz` of the app image, built by `.github/workflows/desktop-linux.yml` on
  Ubuntu (see `desktop/RELEASE.md`); the workflow installs the `.deb` and starts the installed app under Xvfb. `release.yml`
  attaches them to the GitHub release (x86-64 only). `linux { packageName = "crossdrop" }`: Debian names must be lowercase.
  jpackage's `.rpm` has no dependencies, so `packageRpmWithDeps` (build.gradle.kts) rebuilds it from the app image with the
  X11/GL/font libraries as soname `Requires`; `tools/release/linux_smoke.sh` (Ubuntu, .deb) and `linux_smoke_rpm.sh` (clean Fedora)
  install the package and start the app. A bare container needs the freedesktop menu folders before the `.deb` installs
  (jpackage's post-install calls `xdg-desktop-menu`); every desktop has them. Java reads `user.home` from the passwd entry, not
  `$HOME`: pass `-Duser.home` when testing with a throw-away profile.

```sh
sudo apt install fakeroot dpkg-dev rpm            # jpackage's tools (Linux host only)
./gradlew :desktop:run                            # dev run
./gradlew :desktop:packageDeb :desktop:packageRpm :desktop:createDistributable
```

## iOS

`ios/CrossDropCore` (SwiftPM, no deps beyond CryptoKit/Foundation) mirrors `:core`:
`Crypto.swift` (X25519/HKDF/ChaCha20-Poly1305 via CryptoKit, BouncyCastle-compatible
record layout), `Frame.swift`/`Messages.swift`/`Chunks.swift` (codec — Messages use
custom `init(from:)` so missing JSON fields default like kotlinx), `Blake3.swift` +
`FastCdc.swift` (bit-exact ports — official BLAKE3 vectors pass, the gear table must
stay identical or delta sync silently degrades), `FileDeltaStore.swift` (same
`.staging/` + `.idx/` `CDX3` layout), `TransferEngine.swift` (v1/v2/v3, **blocking**
BSD sockets on dedicated threads, events via callback, `CancelToken` to abort),
`Share/` (QR share HTTP server), `Socket.swift` (`TCPSocket`/`TCPListener`).
`ios/CrossDropCore/Sources/crossdrop-cli` is the interop harness (`recv`/`send`).

`ios/CrossDrop` (generated by **xcodegen** from `ios/project.yml`; the `.xcodeproj` and
`Info.plist` are gitignored): `Services/` (`AppContainer`, `TransferCoordinator`,
`BonjourDiscovery` = NetService advertise + browse with the same service types/TXT keys
as Android NSD/desktop JmDNS, `InboxStore` = `Documents/Received`, `HistoryStore`),
`UI/` (Sonar design, mirroring the Android screens: `Theme.swift` tokens + fonts,
`CdIcons.swift` (the same SVG icon paths), `Components.swift`, `FloatingSheet.swift`
(in-app inset sheet, so the offer can still cover it), `RadarCanvas` = SwiftUI `Canvas` +
`TimelineView`, `Transfer/TransferScreen`, full-screen offer, inbox with QuickLook,
settings, `QrShare/`, `Drive/`, `Vault/`). Sora/JetBrains Mono ship in
`Resources/Fonts` via `UIAppFonts`. Files other
apps open in CrossDrop (`onOpenURL`) wait on the radar for a device tap
(`AppContainer.pendingShare`).

```sh
cd ios && xcodegen generate
xcodebuild -project CrossDrop.xcodeproj -scheme CrossDrop \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
(cd CrossDropCore && swift test)                       # Blake3/transfer/share tests
(cd CrossDropCore && swift build -c release)           # builds crossdrop-cli
CROSSDROP_SWIFT_CLI=$PWD/ios/CrossDropCore/.build/release/crossdrop-cli \
  ./gradlew :core:test --tests '*SwiftInterop*'        # Kotlin⇄Swift wire compat
```

Simulator debug hooks (DEBUG builds, pass as launch args to `xcrun simctl launch`):
`-dev_peer host:port` (radar peer), `-dev_send host:port` (send a generated 4 MiB file),
`-dev_autoaccept YES` (accept every offer), `-dev_share YES` (start a QR share, URL is
NSLogged), `-dev_tab <radar|transfers|inbox|settings>`, `-dev_sheet peer|anyone` (raise the
send sheet), `-dev_hide_transfer YES` (start with the live transfer minimised). The simulator shares the Mac's network, so `dns-sd -B _crossdrop._tcp` sees
the app and a Mac-side `crossdrop-cli recv/send` can talk to it directly. The app logs
`CrossDrop listening on :<port>` — read it with
`xcrun simctl spawn <sim> log show --last 1m --predicate 'eventMessage contains "CrossDrop listening"'`.

iOS constraints worth remembering: no BLE layer (radar distance only); transfers pause
when the app is suspended (a background task buys ~30 s, `isIdleTimerDisabled` while a
session is live); first launch triggers the Local Network prompt (`NSLocalNetworkUsageDescription`
+ `NSBonjourServices` in `project.yml`) — "Rescan network" in Settings re-binds mDNS after
approving; the offer sheet only answers via its buttons (a system alert over it must not
decline it). The package is compiled with `-O` even in Debug (BLAKE3/FastCDC are CPU-bound; checked `-O`, not `-Ounchecked`, because the parsers face the LAN).

Mac and iPad: the same target ships as iPad-native and **Mac Catalyst** (`supportedDestinations`
in `project.yml`; sandbox entitlements `CrossDrop/CrossDrop-Mac.entitlements`; privacy manifest
`CrossDrop/PrivacyInfo.xcprivacy`). Build Catalyst with
`-destination 'platform=macOS,variant=Mac Catalyst'`. When screenshotting the Mac app capture only
its window (`screencapture -l <windowId>`), never the full screen. App Store steps and export-compliance
notes are in `ios/RELEASE.md`. Both Swift servers (QR share, Wireless Drive) were security-audited:
Host/Origin checks against DNS rebinding/CSRF, shared PIN throttle, slow-connection limits, MOVE/COPY
root guards (the same MOVE/COPY-onto-root data-loss bug was fixed in Kotlin `FileVaultFs`/`SafVaultFs`).

## Multi-session transfers, offer kinds, trust grants

- `app/.../net/TransferHub.kt` holds every session (one per device per send; a receive is its own
  group). It is **shared verbatim with `:desktop`** (`syncSharedNet` in `desktop/build.gradle.kts`, like
  the shared UI), so it must stay free of `android.*`. `TransferCoordinator` on each platform is a thin
  face over it (URIs/files/text in, inbox + history out). The Swift twin is
  `ios/CrossDrop/Services/TransferCoordinator.swift`. Shared screens: `ui/transfer/GroupTransferScreen.kt`,
  `ui/live/LiveViewerScreen.kt` (image decoding is passed in: BitmapFactory on Android, Skia on desktop —
  Skia applies EXIF orientation itself).
- `Offer.kind` (`OfferKind`): `text` (the text rides inline in `Offer.text`, the `.txt` file is only for
  older receivers; new receivers show Copy/Open and discard the file), `camera` (live camera shots →
  `TransferHub.live`), `switch` (one batch of "Switch phones" → the platform `SwitchReceiver`).
  `contact` (one `.vcf`; `Offer.text` = the cards without photos, for the prompt — `core/contacts/
  ContactShare.kt`, Swift `ContactCards`; the hub's `ContactReceiver` stages the file and adds it to the
  address book: Android `contacts/ContactInbox`, iOS `Services/ContactShare.swift`; desktop has no receiver,
  so the card lands in the inbox). Any send whose items are all `.vcf` becomes a contact offer.
  `FileMeta.cat` / `mtime` and `Offer.batch` carry what each item is and the move's totals. All optional
  JSON: older peers ignore them.
- Trust grants (`core/transfer/TrustStore.kt`, Swift `TrustStore`): a receiver accepting a camera/switch
  offer returns `Accept.trust` (`TrustGrant`: id, 32-byte token, kind, ttl). The sender's later offers
  carry `trustId` + `trustProof` = HMAC-SHA256(token, `SessionKeys.binding` || transferId); the receiver
  auto-accepts a valid, unexpired, same-kind proof whose transfer id it hasn't seen. Memory only.
  `SessionKeys.binding` is HKDF of the per-launch keys, so it is the same for every connection between the
  same two app launches — the seen-id check is what stops replays.
- Switch phones: Android `switchphones/` (MediaStore query + `AndroidContacts` ↔ `core/contacts/VCard.kt`),
  iOS `Services/SwitchPhones.swift` (PhotoKit export/import, Contacts framework), desktop
  `switchphones/DesktopSwitchReceiver.kt` (backup folder). Receivers stage batches in their own
  `FileDeltaStore` and delete each staged file once imported (only that batch's files — the next batch
  may already be arriving). Android writes EXIF time + offset into date-less JPEGs before inserting:
  MediaStore drops offset-less EXIF times on fresh copies.
- Share entry points: Android `share/ShareActivity` (translucent, own task) hands its URI read grants to
  `TransferService` via ClipData before sending; `share/DirectShare` pushes a shortcut per device sent to
  (matched by name — ids rotate). iOS `ios/CrossDropShare` is an app extension with its own small Bonjour
  browser and engine (`APPLICATION_EXTENSION_API_ONLY`, so it can't use app code that touches
  `UIApplication.shared`). Desktop: `Main.kt` takes launch args, macOS `setOpenFileHandler`, window
  drops; `SingleInstance` forwards `file <path>` lines after `show`; `platform/WindowsIntegration` adds
  the per-user "Send to" shortcut once (not for MSIX).
- Testing without UI taps: `crossdrop-cli send --kind text|camera|switch|contact [--text T] [--cat C] [--each]`
  and `recv --grant --count N` speak the new kinds (a contact offer's `--text` is the photo-less vCard);
  Android `-e dev_peer a:p,b:p` takes several peers, `-e dev_send_contact h:p` sends a made-up card; iOS
  `-dev_send_multi`, `-dev_text`, `-dev_switch host:port`, `-dev_send_contact h:p`, `-dev_autoaccept YES`.
  The emulator's address book is real: test with made-up cards, filter the picker by search before
  screenshotting it, and delete test contacts afterwards.

## Radar liveness & merging

mDNS only retires a peer when it says goodbye, so a killed app, a sleeping laptop or a phone off
Wi-Fi stays cached on other devices for an hour or more (SIGKILL sends no goodbye; JmDNS does send
one on SIGTERM and on a normal quit). `core/discovery/PeerLiveness` therefore TCP-probes every
`Peer.probeable` peer every 4 s (1.5 s connect timeout): shown after its first successful probe,
hidden after 2 consecutive misses (a BLE-sighted peer keeps its proximity node, minus the
endpoint). `AppContainer.peers` on Android/desktop and `Services/PeerLiveness.swift` on iOS wrap
the merged sightings with it.

- A probe is a bare connect-and-close, so **listeners must treat EOF before HELLO as nothing**
  (`TransferEngine.receive` in Kotlin and Swift; the vault listeners already did). Older builds
  would surface it as a failed transfer, hence it is opt-in: only peers whose TXT carries
  `probe=1` are probed (`_crossdrop._tcp.` TXT is `id`, `name`, `probe`); vault beacons are always
  probeable. Peers on older builds simply keep the old "until the mDNS record expires" behaviour.
- `core/discovery/PeerMerge.kt`: `mergePeers` folds sightings per `instanceId` letting the one with
  an endpoint (NSD) speak for identity — a BLE sighting must not rename a peer to "Nearby device".
  `ServiceNames` ref-counts the service names behind an id (a conflicting registration is renamed
  `crossdrop-<id> (2)` and both can be advertised), so losing one name doesn't drop a live peer.
- Discovery never shows the raw `crossdrop-<id>` service name: a resolve that lands before the TXT
  record keeps the previously known name, else "Nearby device" (`UNNAMED_PEER`).
- Android `NsdDiscovery.publish` waits for an in-flight unregister before re-registering (same
  name registered too early gets the `(2)` rename).

## Languages (localization)

CrossDrop is translated into Georgian, Hindi, Indonesian, Arabic, Persian, Spanish (neutral Latin American), Brazilian
Portuguese, Russian and Turkish; English is the code itself. The language follows the phone (first of the user's system
languages we ship, else English; Android 13+ and iOS also list the languages as per-app languages).

- **Every user-visible string goes through `tr()`**: the English text is the key (`tr("Send to {0}", name)`,
  `trPlural(n, "1 file", "{n} files")`). Runtime: `core/.../i18n/I18n.kt` (Android + desktop) and its Swift twin
  `ios/CrossDrop/Services/I18n.swift`: keep the two (and the plural rules) in step. Catalogs are `l10n/<lang>.json`,
  shared by all platforms (Android assets, desktop resources, iOS folder reference). A missing entry shows English.
- Never call `tr()` in a constant, enum constructor, `static let`, `lazy` or `remember { }` cache: the language is read at call
  time. Keep one string literal per call (no `+`, no `$x`/`\(x)` inside: use `{0}` placeholders); translate whole sentences.
- `tools/l10n/l10n.py`: `extract` (code -> `tools/l10n/source/en.json`), `check` (every language complete, placeholders and
  plural forms right), `audit` (user-visible literals not yet wrapped; intentional exceptions go in `tools/l10n/audit_ignore.txt`),
  `plist` (iOS permission texts -> `InfoPlist.strings`). `core` tests (`L10nCatalogTest`) fail when a catalog is incomplete.
- Adding a string: wrap it, run `extract`, add the key to all nine `l10n/*.json`, run `check`. See `tools/l10n/README.md`.
- **In-app language list**: a globe button on the welcome tour and on the radar, and a "Language" row at the top of Settings, open
  `ui/language/LanguageUi.kt` (shared with desktop; Swift twin `ios/CrossDrop/UI/Language/LanguageUi.swift`): "Automatic" plus each
  language in its own script. The pick is stored (Android prefs / desktop `settings.properties` / iOS UserDefaults key `language`,
  "auto" = follow the phone) and wins over the system language (`I18n.setChoice`). It applies instantly: on Kotlin the runtime's
  `onRead` hook reads a Compose snapshot state (`LanguageReactivity`), so every `tr()` in a composable refreshes by itself and
  no state is lost; on iOS `I18nModel.version` rebuilds the tree (`.id` in `RootView`). Layout direction follows the language
  (not only the phone's). Debug: iOS `-dev_language_sheet YES`; `-dev_lang <code>` still wins over a saved pick.
- Scripts: Arabic and Persian are right to left (desktop sets the layout direction in `Main.kt`; Android and iOS follow the
  locale). Never apply negative or positive letter-spacing or forced capitals to translated text directly: use `trackingEm()` /
  `.caps()` (Kotlin) and `CDFont.tracking()` / `.caps` (Swift), which switch them off for Arabic, Persian, Hindi and Georgian.
  Sora (display font) is Latin-only: other scripts use the system font.
- Translations are machine-made: ask a native reader to review before a store release. Out of scope so far: the browser pages
  (`share.html`, `vault.html`, `VaultHttp`), the iOS share extension, and the Play / App Store listing text.
- Test a language: the in-app list, or Android 13+ `adb shell cmd locale set-app-locales com.crossdrop.debug --locales ar` (when the list is on Automatic); iOS launch args
  `-dev_lang ar`; desktop `-Duser.language=ar`.

## First-run welcome

`ui/welcome/WelcomeScreen.kt` is a skippable four-page tour (find → send → "Send to anyone" → private) with a
small animation per page. It is platform-free and shared verbatim with `:desktop` (`sharedUi` in
`desktop/build.gradle.kts`); iOS has a SwiftUI twin in `ios/CrossDrop/UI/Welcome/WelcomeScreen.swift`
(keep the copy and the page order in step). Shown once: `AppContainer.welcomeSeen` (prefs `welcome_seen`, also
true when the history already has an entry, so an update never greets someone who has used the app). Settings →
"Show the welcome tour" replays it. Android's `Root` doesn't compose the radar while it is up, so the
permission prompts follow the tour; iOS holds discovery back (`AppContainer.start`/`restartDiscovery` check
`welcomeSeen`) so the Local Network prompt does too. Debug hooks: Android `-e dev_skip_welcome 1`; iOS launch args
`-dev_skip_welcome YES`, `-dev_welcome YES` (force it), `-dev_welcome_page <0-3>`. A fresh install in tests
(`pm clear`, a new simulator) shows the tour first, so pass the skip hook.

## "Can't see the other device?" help and Connect directly

The radar says something when it can help: `core/connect/ConnectAdvice` decides (no network for 2 s → "You're not on
Wi-Fi"; on a network but 0 devices for 10 s → "Can't see the other device?"), `ui/connect/ConnectHelp.kt` is the
card + plain how-to guide (shared with `:desktop`; Swift twin `ios/CrossDrop/UI/Connect/ConnectHelp.swift`, keep the
rule, thresholds and copy in step). "Has a network" is Android `net/LocalNetworkMonitor` (Wi-Fi/Ethernet transport or an
`ap*`/`swlan*` interface), desktop `LocalIp` polled every 4 s, iOS `AppContainer.localNetworkUp()` (private IPv4 on `en*`/`bridge*`).
The card also hides while a direct link is on.

**Connect directly (Android only)**: `net/DirectLink` starts Android's *local-only hotspot* (`startLocalOnlyHotspot`:
random SSID/password, no internet shared, API 26+; needs `CHANGE_WIFI_STATE` + Nearby devices on 13+, or the Location permission *and* Location switched on before 13, so
`ACCESS_FINE_LOCATION` is declared through API 32 and asked for only here, after an explanation). The phone answers a hotspot request whenever it
likes or never, so `core/connect/DirectLinkMachine` (tested) tags every request: a 20 s start timeout, Stop that cancels a start in flight, and late
"started"/"stopped" answers for an older request are ignored (the late hotspot is closed).
`ui/connect/ConnectDirectScreen` shows `WifiQr.payload(...)` (`core/connect/WifiQr`, the standard `WIFI:T:WPA;S:..;P:..;;`
text: Android and iPhone cameras offer to join, no app needed) plus name/password with copy buttons for devices that
can't scan. While it is on the radar shows a "Direct connection is on" row; it stops on Stop, after 30 min, or when the
system or process ends it. A device with no app then uses the normal "Send to anyone" QR over that network. iPhone/Mac/Windows
can't create a network from an app, so they only get the guide. Debug hooks: Android `-e dev_directlink on|fail`.
Proven on the emulator (it does create the hotspot; the QR decodes with ZXing to the same SSID/password the system
reports). **Not proven**: two real phones, i.e. a second device joining and then discovering this one over the hotspot
(NSD on the ap interface differs by Android version/maker). If that fails, add a gateway probe: the guest tries the
gateway address on a fixed port that answers with this phone's listener port. Phone makers' hotspot quirks (some drop the
phone's own Wi-Fi) need a Samsung and a Xiaomi/Oppo to test. Not built: an in-app "scan a code to join" (needs a QR decoder; the
system camera does the job today), a Windows/Mac "Join" helper.

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

**v3 push** (first-time sends, zero start delay): when the receiver has no
baseline for any offered file and an empty staging pool
(`DeltaReceiver.canPush`), `Accept { push }` tells the sender to skip the
manifest. It FastCDC-chunks + BLAKE3-hashes in one streaming pass and emits
`PUSH_CHUNK` (`[u32 fileIndex][32B hash][bytes]`) as each chunk is cut, then
`PUSH_END` (`[u32 fileIndex][u32 count][32B file root]`) per file — hashing
overlaps the network instead of preceding it. Receiver verifies each chunk,
writes a `.part`, checks the root, moves into place and publishes the `.idx`.
A dying session salvages received chunks into `.staging/`, so the retry sees a
non-empty pool, falls back to v2 and resumes. Trade-off: no intra-file dedupe
on push (v2 manifest path keeps it). v2 peers on either side negotiate down.
`TransferEngine(protocolVersion=…)` exists so tests can pin older versions.

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
- iOS: only exercised on the simulator + against macOS Bonjour (`dns-sd`) and the JVM/Swift
  interop tests — never on a physical iPhone or against a real Android device. Local-network
  prompt flow, background suspension and Wi-Fi switching are unverified on hardware.
- Desktop: Mac↔phone discovery verified only against macOS's own Bonjour
  (`dns-sd`), not yet against a real Android device;
  DMG is ~80 MB (bundled JRE + Skia) — the <15 MB pillar is Android-only.
