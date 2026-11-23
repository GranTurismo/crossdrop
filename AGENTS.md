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
- **Choosing another folder** (Settings → Storage → "Inbox folder", "Use the default folder" to go back):
  - *Desktop*: native folder picker → `AppContainer.setInboxFolder(dir)`. Same guard as a shared drive
    (`VaultHostManager.isShareable`: not the home folder, `~/Library`, system or app-state folders) and refused while a
    transfer runs (its half-written files belong to the old folder). `InboxStore.switchTo` flips `dir`/`delta` at runtime
    (so **read them each time, never keep them**); `moveFrom` carries the finished files across on a background thread
    (names kept as they are, numbered on a clash; dot-files, `.part` and sub-folders stay). Saved in prefs `inbox_dir`
    (empty = default); a saved folder that has gone at launch (unplugged drive) means the default for that run, without
    forgetting the choice.
  - *Android*: a SAF tree from `OpenDocumentTree` with a persisted grant, pref `inbox_tree` (SharedPreferences `inbox`).
    `publish` then copies into the tree instead of MediaStore (a taken name is numbered, a failed copy leaves no half
    file and the original stays in the private dir, still listed). `list()` merges the tree (one `DocumentsContract`
    children query, not a `DocumentFile` round trip per file), whatever is still in `Download/CrossDrop/` and the private
    dir, so switching never hides earlier receives; they are **not** moved. Shredding a picked-folder file goes through
    `DocumentsContract.deleteDocument`. Android refuses `Download` itself and `Android/*` in its own picker.
  - *iOS*: not offered. iPhone/iPad can't write outside the app container; Mac Catalyst would need a security-scoped
    bookmark plus the `files.user-selected.read-write` entitlement.
  - Tests: `InboxLocationTest` (switch + move, clash numbering, names kept, leaving files behind). The Android SAF path
    and both native pickers are not exercised by tests.
- **The inbox may hold the person's own things, and the sender picks the names.** So CrossDrop only ever lists, counts, moves or
  wipes what it received itself:
  - *Folders*: it remembers the ones it created (desktop `.crossdrop-folders-<hash>.txt` in the state folder, Android prefs
    `inbox_tree_folders`; `Download/CrossDrop/**` in MediaStore only ever shows what this app published). A received folder
    never merges into one of the person's own that has the same name (it becomes `Name (1)`).
  - *Loose files in a folder the person picked*: only the ones CrossDrop received are listed and wiped (desktop
    `.crossdrop-files-<hash>.txt`, Android `inbox_tree_loose`); their own files are invisible to the inbox. In CrossDrop's own
    folder every loose file counts, as before.
  - *Never over a file CrossDrop didn't assemble* (desktop `placeFor` + `FileDeltaStore.knows`: the index must exist **and match the
    file's size and time**; the delta path would otherwise write over a same-named file it takes for an earlier copy). A deleted
    file's leftover index is dropped on wipe (`shred` → `forget`) and could not vouch for another file anyway.
  - *Links* are never listed, followed or overwritten (`shred` of a link only removes the link).
  - **Leaving a folder the person picked moves nothing** (`InboxStore.moveFrom` only empties CrossDrop's own folder), and the
    Settings dialog doesn't offer the move.
  - *Names*: `core/protocol/FileNames.sanitize` keeps letters/marks/digits/emoji of every script (NFC), strips everything
    unsafe and clips by UTF-8 bytes (200). `NameClaims` makes sure two different originals that still clean to the same place
    (`a:b` / `a_b`) never share it: the first keeps it, the other becomes `name (1)`, and each keeps its answer. (The old ASCII-only
    rule turned every non-ASCII character into `_`, so two different Georgian names of equal length were one file: the second
    silently replaced the first.) Index file names longer than 200 bytes are named by their hash; failing to write an index never
    fails a finished transfer; `canPush` creates nothing.

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
  `app` (installed Android apps, see "Share apps"; `FileMeta.pkg` groups each app's APKs; the hub's `AppReceiver`
  stages them, only Android sets one, so elsewhere they land in the inbox as `.apk` files).
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
  `probe=1` are probed (`_crossdrop._tcp.` TXT is `id`, `name`, `probe`, `os`); vault beacons are always
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

**Connect directly**: the card's "Connect directly" opens a choice, *Show a code* (Android only) or *Scan a code* (Android +
iPhone); nothing starts until one is picked, because the phone that scans must not make a network. A Bluetooth-only radar node
(near, not on this Wi-Fi) opens the same choice as a sheet (`RadarScreen.NotOnWifiSheet`) instead of doing nothing.

*Show a code* — `net/DirectLink` starts Android's *local-only hotspot* (`startLocalOnlyHotspot`:
random SSID/password, no internet shared, API 26+; needs `CHANGE_WIFI_STATE` + Nearby devices on 13+, or the Location permission *and* Location switched on before 13, so
`ACCESS_FINE_LOCATION` is declared through API 32 and asked for only here, after an explanation). The phone answers a hotspot request whenever it
likes or never, so `core/connect/DirectLinkMachine` (tested) tags every request: a 20 s start timeout, Stop that cancels a start in flight, and late
"started"/"stopped" answers for an older request are ignored (the late hotspot is closed). Once the hotspot interface has an
address, `DirectLink.findAddress` puts it in the code with our listener port (`DirectLinkMachine.located`): the QR is the standard
`WIFI:T:WPA;S:..;P:..;` text plus CrossDrop's own field `CD:<ipv4>/<port>/<instance id>;` before `;;` (`core/connect/WifiQr`).
Phone cameras ignore unknown fields (ZXing's and Android's parsers look up known keys; `JoinCodeTest` checks ZXing still reads the
network), so the no-app path is unchanged; CrossDrop's scanner connects straight to the address. **Android 15+ lists the hotspot itself as a
network with `NET_CAPABILITY_LOCAL_NETWORK`**: the address finder must not treat it as a client network (it did, and the code had no
`CD`). While it is on the radar shows a "Direct connection is on" row; it stops on Stop, after 30 min, or when the system or process ends it.

*Scan a code* — `ui/connect/ScanCodeScreen` (CameraX `ImageAnalysis` + ZXing core's `QRCodeReader`, a shipped dependency now,
~0.25 MB in the release APK) reads any `WIFI:` code; `net/WifiJoin` joins it with a `WifiNetworkSpecifier` request (the system asks
"Connect to device"; needs `CHANGE_NETWORK_STATE` and an app on screen — a request from `onCreate` is refused), binds the whole process to
that network (it has no internet, so nothing would route there otherwise), then **announces** itself to the code's host (ANNOUNCE, see
Wire protocol) every 15 s. The host adds announced devices to its radar (`core/discovery/AnnouncedPeers`, probed for liveness, 60 s TTL,
capped at 32, listed after mDNS so a device's own advert wins); the guest adds the host the same way, so both see each other and either can
send. Leave (radar row "Linked with …") releases the request and Android returns to the usual Wi-Fi. A code without `CD` (a router's sticker)
just joins; discovery takes over. iPhones scan and join too, but can't host: `ios/CrossDrop/Services/WifiJoin.swift`
(`NEHotspotConfiguration`, `joinOnce`; entitlement `com.apple.developer.networking.HotspotConfiguration` in
`CrossDrop/CrossDrop.entitlements`, iPhone/iPad only; everything NetworkExtension is compiled out on Mac Catalyst), AVFoundation
scanner `UI/Connect/ScanCodeScreen.swift`, announced peers `Services/AnnouncedPeers.swift`; the help card's button is "Scan a code".
The Swift engine answers ANNOUNCE; it agrees to PAIR only for "Add a device" (`purpose: devices`) and refuses the old backup request (iPhones don't keep backups).
iOS debug launch args: `-dev_join '<code>'`, `-dev_join_skip_wifi '<code>'`, `-dev_scan YES`. `crossdrop-cli announce --to h:p --port N`
and `recv` (prints `ANNOUNCED-FROM …`) cover it; `SwiftInteropTest` checks ANNOUNCE both ways and the pairing crypto has the same
known-answer vectors in Kotlin `ControlTest` and Swift `ControlTests`.
Debug hooks: Android `-e dev_directlink on|fail` (the forced code carries the device's real address/port), `-e dev_ble_peer 'Galaxy S24'`
(a Bluetooth-only radar node: emulators have no Bluetooth peers), `-e dev_join '<code>'` (real
join, system dialog), `-e dev_join_skip_wifi '<code>'` (skips the Wi-Fi step: emulators can't join each other's hotspot). Mac-side peer for
emulator tests: `JAVA_TOOL_OPTIONS=-Dcrossdrop.linkProbe=1 ./gradlew :desktop:test --tests 'com.crossdrop.LinkInteropProbe' --rerun -i`
(listens on :47000; the emulator reaches the Mac at 10.0.2.2, the probe `adb forward`s back).
Proven on the Android 16 emulator: the real hotspot's QR carries `CD:` with its address; a real `WifiNetworkSpecifier` join of the
emulator's own network, bind, announce to the Mac, the Mac's offer received; Leave. **Not proven**: two real phones (a guest joining the
host's hotspot), iPhone cameras with the extra field, makers that drop the host's own Wi-Fi (Samsung, Xiaomi/Oppo).

## Shake to connect

Two people shake their phones at the same moment and each phone opens the other's send sheet ("You shook phones
with …"). Over the network, so Android ↔ iPhone works; both must be on each other's radar (same Wi-Fi or a link).
Computers can't shake: they answer SHAKE and add the sender to their radar, nothing more.

- **Gesture** (`core/shake/ShakeGesture`, Swift twin with the same numbers): accelerometer magnitude incl. gravity above
  13.5 m/s² for ≥ 3/4 of the samples over ≥ half of a 500 ms span (≥ 4 samples), 1.5 s cooldown. Walking, a single bump and
  lying still don't count (`ShakeTest`). Android `shake/ShakeSensor` reads the sensor only while `Root` shows the radar with
  nothing on top and the activity is resumed (and Settings → Shake to connect is on, pref `shake_enabled`).
- **Signal**: `TransferHub.shake(peers, listenPort)` sends SHAKE (0x24, `{port}`) to every reachable device on the radar; the
  listener notes the *arrival* time on its own clock and adds the sender to `AnnouncedPeers` (fixes one-way discovery).
- **Match** (`core/shake/ShakeMatcher`): our shake vs. their SHAKE's arrival within 2.5 s, whichever comes second; closest wins,
  each used once; no clock sync needed. `TransferHub.shakeMatch` → `Root` waits ≤ 4 s for that device on the radar, haptic,
  `RadarScreen` opens its send sheet. A shake with no match shows "Now shake the other phone at the same time"; 2+ reachable
  devices show "Several devices? Shake both phones to find each other."
- **Teaching it**: until shaking has matched once on this device (pref `shake_learned`, set by the first match), 2+ reachable
  devices also bring up an animated card (`ui/radar/ShakeHint.kt`: two phones shake, then a mint ring links them; 2.6 s loop)
  once per launch, for 12 s or until tapped or the person shakes. Reuses the two strings above.
- **iOS** (twin): `ios/CrossDrop/Services/ShakeSensor.swift` (CoreMotion raw accelerometer ×9.81, not on Mac Catalyst),
  `TransferCoordinator.shake(_:listenPort:)`, Swift `ShakeMatcher`/`ShakeGesture` (uptime clock); launch arg `-dev_shake YES`;
  `crossdrop-cli shake --to h:p --port N` and `recv` (prints `SHAKE-FROM …`).
- Tests: `ShakeTest` (core), `LinkAndBackupTest.twoPhonesShakenTogetherFindEachOther` (two hubs + a bystander). Emulator:
  `-e dev_shake 1` shakes 3 s after launch; `adb emu sensor set acceleration 22:0:9.8` / `-22:0:9.8` in a loop drives the real
  sensor path; Mac-side partner: `JAVA_TOOL_OPTIONS=-Dcrossdrop.shakeProbe=1 ./gradlew :desktop:test --tests
  'com.crossdrop.LinkInteropProbe' --rerun -i` with `-e dev_peer 10.0.2.2:47000` (it shakes back the moment the emulator's SHAKE
  arrives). Proven that way on Android 16: both sides match, the sheet opens. **Not proven**: two real phones (how hard people
  shake, Wi-Fi latency), iPhone ↔ Android on hardware.

## "My devices" and phone backups

A phone pairs once with a computer, then backs its photos and videos up to it whenever both are on the same network, and can free up space
once the computer has re-checked the files. Backups are phone → computer only (the desktop app is the target). A phone does answer pairing requests now, but only for "Add a device" (see below): a `backup=false` pairing never keeps backups.

- **Pairing** (`core/pairing/PairingStore`, `ControlChannel.pair`): a sealed PAIR exchange; the receiving human compares the 6-digit code
  (both screens) and agrees (`TransferHub.pairPrompt` → shared `ui/pairing/PairPromptSheet`). Both sides derive the key from the session,
  `HKDF(shared, salt = binding, "crossdrop/v1/pair/<pair id>")`: it never crosses the network. Stored in app-private JSON
  (Android `filesDir/pairings.json`, desktop `support/pairings.json`, owner-only); nothing is ever advertised.
- **Recognising it** without a stable id: `BackupRunner.find` asks every radar device PAIR_CHECK (fresh nonce, one HMAC per pairing held);
  only the holder answers with its own proof over the nonce. Strangers learn nothing linkable; recorded answers can't be replayed.
  A device that borrows the computer's *name* is not recognised (tested).
- **Sending** (`app/.../net/BackupRunner.kt`, platform-free, **shared verbatim with `:desktop`** via `sharedNet`, like `TransferHub`):
  items the ledger lacks, in batches of ≤100 items / 512 MB, offer kind `backup` with `FileMeta.key` (Android: `p:<MediaStore id>:<DATE_MODIFIED>`,
  so an edit is a new item) and `Offer.pairProof`; the computer accepts without a prompt only for a matching pairing and proves it back in
  `Accept.pairProof`, which the engine checks **before sending a byte**. A batch is recorded in the phone's ledger
  (`core/backup/BackupLedger`, append-only, per pairing) only when it completed with matching BLAKE3 roots (`TransferEvent.Completed.roots`).
- **Android** (`backup/BackupManager`, `ui/backup/BackupScreen`, Settings card): one paired computer; backs up automatically when CrossDrop
  is open and the computer appears (at most every 15 min), or on "Back up now". **Free up space** (Android 11+): items backed up, still on the
  phone, taken over 30 days ago → `BackupRunner.verify` (BACKUP_VERIFY after a PAIR_CHECK on the same connection: the computer re-hashes
  every file it vouches for) → `MediaStore.createDeleteRequest` (Android's own confirmation; ≤500 per round) → ledger marks them freed.
- **Photo locations**: Android strips EXIF GPS unless the app holds `ACCESS_MEDIA_LOCATION` and opens `MediaStore.setRequireOriginal(uri)`.
  `media/MediaLibrary` does both (asked together with the media permissions; no separate dialog) for backups **and Switch phones** (which
  lost locations before).
- **Desktop** (`backup/DesktopBackupReceiver`): staging per batch in app state, then `<backup root>/<phone>/<yyyy>/<MM>/<name>` with the original
  date (default root `~/Pictures/CrossDrop Backup`, Settings → My devices: show in Finder/Explorer, Change, Unpair). Per-pairing index
  (`core/backup/BackupIndex`, `support/backup-<pair id>.json`): key → path, size, root; the same bytes are stored once (even within one
  batch); `verify` refuses paths outside the folder. The phone folder name is fixed at the first backup (prefs `backup_folder_<id>`), so renaming the
  phone doesn't start a new one. History gets one "Phone backup" entry per run.
- Tests: `core` `ControlTest` (pair/pairCheck/verify/proofs), `BackupStoresTest`; `desktop` `LinkAndBackupTest` (pair with the code on both sides, find
  among a name-borrowing stranger, month folders + dates + dedupe, nothing re-sent, verify drops an edited file, unpaired refused silently).
  Emulator: `-e dev_backup_only 'Pictures/CrossDropTest/'` limits backups and freeing to one folder (never test on someone's real gallery);
  Mac-side computer: `JAVA_TOOL_OPTIONS="-Dcrossdrop.backupProbe=1" ./gradlew :desktop:test --tests 'com.crossdrop.LinkInteropProbe' --rerun -i`
  (pairs automatically, prints what it filed) with `-e dev_peer 10.0.2.2:47000`. Proven that way: pair, automatic backup, month folders,
  free up space → Android's dialog → only the three test photos deleted.
- Not built yet: iPhone as a sender (PhotoKit, like Switch phones), a background job on Android (today: only while the app is open), more
  than one computer, other folders (WhatsApp, Downloads).

## Share apps (Android ↔ Android)

The send sheet shows an **Apps** tile only when the device is an Android phone: the one platform that can install
what it gets. Devices now say what they run on (`core/Platforms`: `android`/`ios`/`mac`/`windows`/`linux`): TXT `os`
on `_crossdrop._tcp.` (Android NSD, desktop JmDNS) and `Hello.os`/`HelloAck.os` in the handshake, which is how a peer
reached through a code or a shake (`AnnouncedPeers`, `ControlHandler.announced/shaken(…, os)`) gets its `Peer.platform`.
iOS doesn't send it yet: unknown means no tile. Older builds ignore the field (`ignoreUnknownKeys`; Swift's decoder too).

- **Sending** (`apps/InstalledApps`, `ui/apps/AppPickerScreen`): lists launchable, non-system apps through a launcher
  `<queries>` intent (no `QUERY_ALL_PACKAGES`), with search. Each app goes as its base APK plus every split
  (`ApplicationInfo.splitSourceDirs`: language, density, CPU), named `<label>.apk` / `<label> - <split>.apk` (names are cleaned by
  `FileNames.sanitize`), `FileMeta.pkg` = package, offer kind `app`. The files are read when the
  offer is accepted: an app updated or removed in between fails that send.
- **Receiving** (`apps/AppInbox`, `ui/apps/ReceivedAppsScreen`): the prompt groups pieces into apps ("wants to send you
  an app", "Receive"). Accepted APKs are staged in `filesDir/apps-in/<transfer id>/` (never the shared inbox), then the
  screen lists each app as read *from the APK itself* (label, icon, version, package; nothing the sender claims) with
  Install / Update / Reinstall (re-checked on resume). Install = one `PackageInstaller` session with all the pieces;
  Android's own confirmation follows (`STATUS_PENDING_USER_ACTION`). Needs `REQUEST_INSTALL_PACKAGES` and the person's
  "Install unknown apps" switch: the screen shows a card that opens it. "Done" deletes the batch; a new receive sweeps
  leftovers older than 10 min; launch clears it all.
- **Google Play**: `REQUEST_INSTALL_PACKAGES` is allowed for file sharing / transfer and phone transfer, with a
  Permissions Declaration in Play Console (declared for 0.10.0: core purpose "file sharing, transfer or management",
  usage "App functionality", a feature description and a demo video link). Play only offers that form once a bundle
  with the permission is uploaded *through the Console*: the API refuses the upload first ("hasn't been declared"), so
  a release that adds a sensitive permission is finished by hand (upload in Console → declare → save → submit).
- Tests: `LinkAndBackupTest.appsGoToAnotherAndroidPhoneWithAllTheirPiecesAndLandAsFilesElsewhere` (platform learnt from
  the handshake, pieces grouped and staged in order, a computer keeps them as files), `ControlTest` (os both ways).
  Emulator, single device: `-e dev_peer 127.0.0.1:0` puts this phone's own listener on the radar, so it can send an app to
  itself. Use a throwaway app (a code-free APK with a language split, built with `aapt2 link --split`, signed with the
  debug key, `adb install-multiple`), search the picker before screenshotting (it lists the person's real apps), and
  uninstall it and reset `appops set com.crossdrop.debug REQUEST_INSTALL_PACKAGES default` afterwards. Proven that way on
  Android 16: grouped prompt, staging, allow card → system switch, fresh install of base + split through Android's
  dialog, Open. Also proven between two fresh emulators that found each other by real mDNS (2026-11-20; that run is
  the demo video for the Play declaration). **Not proven**: two real phones, large Play apps (asset packs / Play
  Feature Delivery are not sent).

## "Add a device": my own devices send without asking

Settings → **My devices** → "Add a device" pairs any two devices that run CrossDrop (phone ↔ computer, phone ↔ phone, a
computer ↔ a computer): both screens show the 6-digit code, the other human agrees (`PairPromptSheet`, now shown on Android
too). The same pairing machinery as backups, with two flags on `PairedDevice` (`core/pairing/PairingStore`):

- `accept` — files from this device are accepted **without a prompt**. On for "Add a device", off for old/backup pairings
  (never silently widened); a per-device switch in My devices, on each side (`PairingStore.setAccept`).
- `backup` — the pairing is for phone backups (`true` for everything written before the flags; `false` for "Add a device").
  `TransferHub.decideBackup` only matches `secrets(backupOnly = true)`, and Android's `BackupManager` only looks at
  `backup` pairings, so a friend's phone can never be mistaken for the backup computer (nor back up to you).
- Wire: `PairRequest.purpose` (`"devices"`; absent = backup, as older builds ask), `ControlHandler.pairRequested/paired`
  4-argument overloads (the old ones remain the defaults). `TransferHub.pairWith(peer, onCode)` initiates; shared UI is
  `ui/pairing/MyDevices.kt` (`MyDevicesSection`, `AddDeviceDialog`; in `sharedUi`).
- **Recognising the sender**: before a plain-files send to anyone, `TransferHub.pairKeyFor` runs one short PAIR_CHECK
  connection (3 s cap, only when we hold any pairing) and, on a match, sends the offer with `pairKey`. The receiver's
  `decide()` finds the pairing from `Offer.pairProof`, auto-accepts **only plain files** (`kind` null/files; text, contacts,
  apps… are always shown first) when `accept` is on and the transfer id is new (`rememberTransfer`, the replay guard shared
  with backups), and **every** accepted answer proves the pairing back (`Accept.pairProof`), prompted or not, because the
  sender checks it before sending a byte. A device that only borrows the name has no key: it gets the normal prompt.
  One-sided Unpair is safe: the sender's PAIR_CHECK no longer matches, so it just sends and is asked.
- **A device that keeps no backups refuses the old backup-only pair request** (`pairRequested` returns false when
  `purpose != devices && backupReceiver == null`, so a phone never shows it): agreeing would make the asker the phone's
  "backup computer" and Android's `BackupManager` would start sending it every photo. Test: `aDeviceThatKeepsNoBackupsNeverAcceptsABackupPairing`.
- iPhone/iPad/Mac App Store app (from 1.6.0): the same, in Swift. `CrossDropCore/Control.swift` serves PAIR (blocks the engine
  thread on `ControlHandler.pairRequested` until the person answers `PairPromptView`), PAIR_CHECK with real proofs and CLIPBOARD;
  `ControlChannel.pair/pairCheck/clipboard` initiate. `InboundRequest.pairedWith` + `ReceiveDecision.accept(…, pairKey:)` prove a
  pairing back on every accepted offer; `SendOptions.pairKey` proves it on plain-file sends and the send fails ("This isn't your
  paired device") unless the receiver proves it back. App side: `Services/PairingStore.swift` (same JSON as Kotlin, Application
  Support, excluded from backups), `PairedPresence.swift`, `MyDevicesServices.swift` (`DevicesControl` = the listener's handler,
  `PairPrompt`), `TransferCoordinator` (auto-accept plain files when `accept` is on, `SeenIds` replay guard; a PAIR_CHECK before
  plain-file sends), UI `UI/Pairing/MyDevicesUI.swift` (Settings → My devices, `AddDeviceSheet`, `PairPromptView`). An iPhone
  only takes part while CrossDrop is open (iOS suspends it). Older iOS builds (≤ 1.5.0) decline PAIR and prove nothing.
  Android keeps the service alive for a receive nobody tapped (`Root` starts `TransferService` while a receive runs).
- Tests: `desktop` `TrustedDevicesTest` (auto-accept both ways, switch off, name-borrowing impostor, one-sided unpair, a
  devices pairing can't back up, an old `pairings.json`).

## Shared clipboard ("My devices")

Copy on one paired device, paste on another. Text only (`MAX_CLIPBOARD_BYTES` = 256 KB), Android, desktop, and (from 1.6.0)
iPhone/iPad and the Mac App Store app. Per-device switch **Share clipboard** in My devices (`PairedDevice.clipboard`, `PairingStore.setClipboard`, off by
default and never on for a backup pairing's row); both sides must have it on.

- **Wire**: `CLIPBOARD 0x25` `{id, text}` → `{accepted}`, only after a successful PAIR_CHECK on the same connection (the
  listener's post-PAIR_CHECK loop takes it beside BACKUP_VERIFY), so the copy is never written to a device that hasn't proven
  the pairing. `id` is fresh per copy and the receiver refuses one it has seen (the same two launches derive the same keys, so a
  recorded connection could be replayed). An older build drops the connection at the unknown frame → `ClipShare.OLD_VERSION`.
- **`net/ClipboardSync.kt`** (platform-free, shared verbatim with `:desktop` via `sharedNet`): `copied(text)` from the platform
  → sent to every device with the switch on that `PairedPresence` has proven, one connection per copy, one device at a time,
  newest copy only (quick copies can't arrive out of order). A copy waits `FRESH_MS` (1 min) for a device that isn't here yet.
  `received` (the hub's `ClipboardReceiver`) checks the switch, remembers the text's hash *before* applying it (the platform's
  watcher then sees it as ours: no echo) and drops our pending copy (no forwarding to a third device). `status` per pairing
  (`SENT`, `RECEIVED`, `OFF_THERE`, `WANTED` = they share but we're off, `OLD_VERSION`, `FAILED`) drives the row's subtitle.
  `shareNow` (Android tile) waits up to 12 s and returns who got it.
- **Desktop** `platform/SystemClipboard.kt`: `ClipboardWatcher` polls every 700 ms only while some device has sharing on
  (`ClipboardSync.active`), never reports what was there when watching began, and `write()` re-baselines so a pasted arrival
  isn't reported. Change counters avoid reading the content: macOS `NSPasteboard.changeCount` and Windows
  `GetClipboardSequenceNumber` (JNA); Linux has none, so it compares the text. Password-manager copies are skipped: macOS
  `org.nspasteboard.ConcealedType/TransientType/AutoGeneratedType` (pasteboard `types` via the objc runtime; AWT can't see them,
  checked), Windows `ExcludeClipboardContentFromMonitorProcessing` / `CanUploadToCloudClipboard` / `Clipboard Viewer Ignore`
  (presence only), Linux `x-kde-passwordManagerHint` via a SystemFlavorMap mapping (best effort, unverified).
- **Android** `clipboard/PhoneClipboard.kt`: Android 10+ lets an app read the clipboard only while one of its windows has
  focus. So `onFocus` runs from `MainActivity.onWindowFocusChanged` **and** 400 ms after `onResume` (a dialog open on return
  takes the focus instead of the activity window, and the callback never comes); it shares a copy only if its
  `ClipDescription.timestamp` (epoch ms) is new (remembered in prefs `clipboard`/`last_stamp`, so a restart doesn't resend it),
  ≤ 10 min old, text, not labelled ours (`LABEL` "CrossDrop" = arrived from a device) and not `EXTRA_IS_SENSITIVE`. The Quick
  Settings tile (`ClipboardTileService`) opens the invisible `ClipboardShareActivity` (focus for a moment), which shares whatever
  is there (even sensitive: the person asked) and toasts the outcome. Arrivals go straight to `setPrimaryClip`, also in the
  background. Android 12+ shows its own "CrossDrop pasted from your clipboard" notice on each read; Android 13+ shows its
  copy preview on each arrival. Debug builds log lengths (never text) under tag `Clipboard`.
- **iPhone/iPad** (`Services/ClipboardSync.swift`, the Swift twin of `net/ClipboardSync.kt`, same rules; `PasteboardShare` in
  `MyDevicesServices.swift`): iOS asks the person every time an app reads the clipboard in code, so CrossDrop never does. When the
  app comes to the front, it compares `UIPasteboard.changeCount` with the last one it handled (stored as `clipboard_seen_count`) and
  checks `hasStrings` and the nspasteboard.org markers in `types` (none of which ask); if there is a new copy, the radar shows
  `ClipboardShareCard` with SwiftUI's system `PasteButton`, which hands the text over without a prompt, then `shareNow` and a result
  line. Arrivals go straight onto `UIPasteboard` (writing never asks) and count as handled (no card for them). Only while CrossDrop
  is open: a copy made elsewhere waits the usual minute for it. **Mac App Store app** (Catalyst): like the desktop app, it polls
  `changeCount` every 0.7 s while sharing is on, never shares what was there when watching began, skips marked copies.
  Debug launch args: `-dev_pair h:p`, `-dev_autopair YES`, `-dev_clip_on YES`, `-dev_paste_text T` (answers the card as if Paste
  had handed over T). Proven on the iOS 27 simulator (2026-11-23) against the Kotlin desktop code (`LinkInteropProbe.macSharesClipboard`
  with `-Dcrossdrop.peer=127.0.0.1:<sim port> -Dcrossdrop.clipFile=<file> -Dcrossdrop.pairFirst=1 [-Dcrossdrop.sendAfterPair=1]`,
  which keeps the real Mac clipboard out of it): the pairing prompt with the same code, a file from the Mac taken without asking,
  Mac → iPhone (Unicode), no card for what arrived, the card for a new copy, iPhone → Mac with "Clipboard sent to …". Simulator
  gotchas: a headless simulator doesn't sync its pasteboard with the Mac (Simulator.app does: keep it closed or switch that off);
  the one-time notifications alert keeps the app inactive (set `asked_notifications` in the app container's plist before the
  first launch). **Not proven**: the system Paste button itself (simulators can't tap), a real iPhone, the Catalyst watcher.
- Tests: `ClipboardSyncTest` (desktop: real loopback devices; lands and isn't sent back, either way and in Georgian; refused while
  off there + `WANTED`; a device at the paired id's address that can't prove the pairing gets nothing; waits for a device
  for a minute, not longer; quick copies end on the newest; too long/empty; `shareNow`; the watcher with and without a change
  counter, secrets, busy clipboard, write-back), `ControlTest` (only over a proven pairing, fresh ids, refused), Swift
  `PairingTests` (pairing both ways, PAIR_CHECK, clipboard only after a proven pairing, files proven both ways), `SwiftInteropTest`
  (Kotlin ⇄ Swift: pair, clipboard and paired sends both ways; an impostor gets nothing). `crossdrop-cli pair|clip`, `recv --agree
  --clips --pairing id:keyHex`, `send --pairing id:keyHex` drive them.
  Mac-side partner with the **real macOS clipboard**: `JAVA_TOOL_OPTIONS="-Dcrossdrop.clipProbe=1 -Dcrossdrop.adbSerial=emulator-5554"
  ./gradlew :desktop:test --tests 'com.crossdrop.LinkInteropProbe.macSharesClipboard' --rerun -i` (auto-pairs, turns sharing on,
  forwards the emulator's port itself) with the emulator started `-e dev_peer 10.0.2.2:47000`. **The emulator syncs its own
  clipboard with the Mac's** (`defaults read com.android.Emulator set.clipboardSharing`): set it to 0 (and back) before
  such a test or it proves nothing. Proven that way on Android 16 + macOS 27 (2026-11-23): refused while off with the hint on
  both sides; Mac → phone (Unicode); phone → Mac on opening CrossDrop, also with a dialog open; the tile from another app,
  with its toasts; no echo. **Not proven**: Windows and Linux clipboards, the Windows/Linux secret markers, two real phones,
  macOS versions that prompt before an app reads another app's copy (macOS 27 didn't, as far as the reads went through at once).

## Auto-resume (a send that loses its connection finishes by itself)

A plain-files send (`TransferHub.send` groups; not backups/camera/switch/text) whose connection drops **after the channel
was up** (a Wi-Fi blip, the other device locking its screen) no longer fails: `startResume` keeps the session
`CONNECTING` with `SessionState.resuming` (labels say "Reconnecting…", the note says "Lost the connection to X. Trying again as
soon as it's back; what already arrived is kept.") and retries with growing pauses (`core/transfer/ResumePolicy`: 2 s → 60 s,
10-minute window), each try only when the device is on the radar again. Once resuming, an "unreachable" attempt keeps waiting;
a full disk, a damaged file or a version mismatch ends it. When the window passes it becomes the usual "Lost the connection…"
failure. Cancelling stops it. What had arrived is kept by the receiver's resume pool, so a retry only moves what is left.

- **Which device is "back"**: the same radar id (same launch), or, for one of "My devices" that restarted, the radar entry that
  proves the same pairing (`PairedPresence`, below). A same-*name* device is never enough: a stranger borrowing the name would
  otherwise be offered your files. `hub.radar` / `hub.presence` are set by the containers; with no radar the hub just retries.
- **The receiver doesn't ask twice**: `core/transfer/ResumableOffers` remembers an accepted plain-files offer for 10 min,
  keyed by `InboundRequest.sessionKey` (a hash of the session binding: only the same two app launches can reproduce it) and
  a fingerprint of the files (names, folders, sizes, in order). The same sender repeating exactly that, before it completed,
  is accepted without a prompt, and replaces the dead first receive (a silent network cut may still be holding it). Different
  files, another launch, another device, a completed transfer or the passing of 10 minutes all mean "new offer, ask". Older
  receivers and iPhones just ask again (nothing on the wire changed).
- **Rules that keep it safe**: a retry of a send that already proved a paired device must prove it again (`spec.pairIds`; whoever
  now answers at that radar id, or a timed-out check, means "still away", never "send unproven": the id is public, so anyone can
  advertise it). Moving again after a pause starts a fresh 10-minute window next time. A receive the person cancelled, or that failed
  for its own reasons (a full disk), is remembered as *stopped* (`ResumableOffers.stop`): its retries are asked about, and a "no" ends
  the sender's retrying (to a sender mid-stream a receiver's Cancel looks like a dropped connection). The receive is registered for
  resume *before* it becomes visible (a Cancel in between used to be lost), and a paired device's auto-accepted repeat also replaces the
  stale half-open receive.
- iOS: the Swift coordinator has no resume yet (and doesn't take repeats unasked); it sends and receives as before.
- Tests: `AutoResumeTest` (a TCP relay cuts the connection after 2 MB: no second prompt, never "failed", bytes skipped, the
  dead receive replaced; gives up after the window with a plain message; cancel stops the retries), `ResumeTest`.

## Send when it's back (outbox) and `PairedPresence`

Quick "is this one of mine?" checks pass `engine.control(..., readTimeoutMs = 3000)`: a coroutine timeout can't interrupt a blocked socket read, so the 3 s cap has to be the socket's own.

`PairedPresence` (shared net file): every radar device is asked **once** whether it holds one of our pairings
(`pairCheck`, proven both ways, nothing linkable to strangers) and the verdict lives as long as its radar entry; no
pairings → no work. `present: pairId → Peer` is what auto-resume and the outbox use, and what the UI shows as "Here now / Not here".

`Outbox` (shared; `outbox.json`, app-private) holds things picked for one of **My devices that isn't here**; `OutboxRunner` sends
an entry when that device appears **and proves the pairing** (`PairedPresence`, then `SendOptions.pairKey` makes the engine
check the receiver's proof before a byte moves), via `hub.sendNow(quiet = true)` (history records it; no transfer screen
pops up). One try per entry per visit: a "no" or a real failure is counted (3 and it is dropped) and waits for the device's
next visit; the device going away again isn't counted. Entries expire after 7 days or when the pairing is removed; at most
20 entries / 50 items each. If the other device doesn't have "Accept files without asking" on, it prompts as usual.

- **Queue**: the radar's "offline devices" strip (`OfflineDevicesStrip`, shared) and each device's row in Settings → My
  devices ("Send when it's back", "Clear", "n items waiting", "Sending…", "Sent to X"). Desktop queues paths (files and
  folders); Android queues document URIs with a persisted read grant (`OpenMultipleDocuments`), or a private copy under
  `filesDir/outbox/` when the grant can't be kept (the photo picker's); copies and grants are released when an entry leaves
  (`AppContainer.forgetQueued`). Android also posts a "Sent to X" notification (`OutboxNotifier`).
- **Details**: a pick of more than 100 items becomes several entries; a transfer cut off while the device is still on the radar is
  retried (3×, 5 s apart) instead of waiting for it to leave and return; a read error counts as a failed try, only a truly empty
  result drops the entry; a verdict from `PairedPresence` is tied to the address it was proven at and dropped when the pairing it
  named goes away; when a device holds two pairings with us the one that accepts files is reported (`secrets()` sorts them first).
- **Limit worth knowing**: it works while CrossDrop is running on the sending device (Android discovery only lives while the
  process does; the desktop app stays in the tray). There is no background job that wakes a closed app.
- Tests: `OutboxTest` (delivered when it appears; a device that only borrowed the name never gets it; a "no" waits for the
  next visit; the queue survives a restart; limits/age/failures).

## Sending a folder

A folder goes as **one offer** whose files each carry `FileMeta.dir` (`"<picked folder>/<sub>/<sub>"`, `/`-separated; absent for
loose files, and older receivers ignore it and get flat files, numbered on a name clash — so an iPhone receiving a folder gets
its files without the tree). `core/protocol/Folders.kt` has the rules: at most `MAX_FILES` = 5 000 per offer (an offer lists
every file and must fit one 4 MiB frame; more throws `TooManyFilesException`, which the hub turns into a plain "too many
files" failure rather than sending half), `MAX_DEPTH` = 16, `relativePath` (every segment cleaned by the platform's
sanitizer; `..`, separators, Windows device names can't climb out or alias), `topFolder`, junk names left behind
(`.DS_Store`, `Thumbs.db`, `desktop.ini`).

- **Sending**: desktop — "Send a folder" on the radar, dropping a folder on the window, "Open With"/Send to (`toOutgoingFolder`
  walks it, **links are not followed**); Android — the send sheet's **Folder** tile (`OpenDocumentTree`, a persisted read grant
  because the send outlives the screen; `Context.outgoingFolder` walks it with one provider query per folder, `storage/SafTree`).
  iOS cannot send folders yet.
- **Receiving**: the tree is rebuilt under the inbox (`<inbox>/<folder>/<sub>/<file>`). `FileDeltaStore` keys its index by
  the path inside the inbox (`$` for `/`), so same-named files in different folders don't share one.
  **CrossDrop only lists, moves or wipes the folders it created itself**: desktop remembers their names per inbox in
  `<state>/folders-<hash>.txt`, Android in prefs `inbox_tree_folders` (picked folder) and `Download/CrossDrop/**` in MediaStore
  (which only ever shows what this app published). A folder of the person's own in the same place is never looked into, so
  "Wipe inbox" can't reach it. Received folders appear in the Inbox list as `folder/sub/name` rows; shredding the last
  file removes the emptied folders; moving the inbox carries them (`InboxStore.moveFrom`).
- Tests: `FolderTransferTest` (real send/receive twice with no duplicates, hostile `dir`/names, wipe and move scope, too many
  files, links), `FoldersTest` (rules and wire compatibility).

## Failure messages

`core/transfer/FailureAdvice.kt` (Swift twin: `CrossDropCore/FailureKind.swift` + `CrossDrop/Services/FailureAdvice.swift`)
turns what a transfer died with into a title and a hint ("Couldn't reach Anna's phone — open CrossDrop there and check you're
on the same Wi-Fi"; "Lost the connection… what already arrived is kept"; full disk; unreadable file; damaged file; version
mismatch). `TransferEvent.Failed(message, cause, kind)` carries the exception; `ProtocolException` is only "update CrossDrop on both devices"
(`INCOMPATIBLE`) while the handshake is still going, a changed/short source is its own `SourceChangedException`
(`SOURCE_UNREADABLE`, sender only) and a failed hash check is `INTEGRITY`; the hub's `reduce` fills `SessionState.error`
(the title) and `.hint`; "connected" = the session had reached the offer (before that a timeout means nothing was listening).
A declined send gets its own hint; **while sending to an iPhone/iPad** (`SessionState.peerOS == ios`, from TXT `os` / the
handshake, which iOS now sends) the transfer screen says it only receives with CrossDrop open and the screen on. `.other`
shows the raw message, so nothing is ever hidden. Tests: `FailureAdviceTest`, Swift `FailureKindTests`.

## Rating prompt

`core/review/ReviewPolicy.kt` (Swift twin in `CrossDropCore`, same numbers and keys): ask after **3 successful transfers in a
row** (a failure resets the run; declines/cancels count neither way; camera/switch/backup sessions aren't counted), at most
**3 times ever**, **120 days** apart, and only when the person **closes a finished transfer that all went well**
(`finishTransfer` in Android `Root`, `TransferCoordinator.finishGroup` on iOS). Android uses Play's in-app review
(`com.google.android.play:review`, ~nothing after R8; `ReviewPrompter`); iOS `AppStore.requestReview(in:)`. The ask counts the
moment it is made (neither store says whether a sheet appeared). Desktop has none. Nothing is sent by us.

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

**Control exchanges** (no files; sent instead of an OFFER right after the handshake, `core/transfer/Control.kt`,
`TransferEngine.control` on the initiating side, a `ControlHandler` on the listener): `ANNOUNCE 0x20` `{port}` → `{port: 0}`
("reach me here"); `PAIR 0x21` `{pairId}` → `{accepted}` (the receiver's human decides; key = HKDF(shared, binding, "crossdrop/v1/pair/<id>"));
`SHAKE 0x24` `{port}` → `{port: 0}` (shake to connect, see above); `CLIPBOARD 0x25` `{id, text}` → `{accepted}` (only after a
successful PAIR_CHECK on the same connection, see "Shared clipboard"); `PAIR_CHECK 0x22` `{nonce, proofs}` → `{nonce, proofs: [0 or 1]}` with HMAC(key, "check-i"/"check-r" ‖ 0 ‖ binding ‖ nonce); then any number
of `BACKUP_VERIFY 0x23` `{items:[{key,root,size}]}` → `{ok:[key]}` on the same connection. Builds that predate these fail the unknown
frame before any session exists, so nothing shows on their side (Kotlin hub and Swift coordinator both ignore failures before an accept).
`PairRequest.purpose` (`devices` = "Add a device"; absent = backup) tells the receiver what the pairing is for. Pairing proofs on transfers: `Offer.pairProof` = HMAC(key, "offer" ‖ 0 ‖ binding ‖ transferId), `Accept.pairProof` the same with "accept";
backup batches use `OfferKind.BACKUP` and `FileMeta.key`. `TransferEvent.Completed.roots` carries the agreed per-file BLAKE3 roots (v2/v3).

## Build / verify

```sh
./gradlew :core:test            # JVM unit tests (crypto + loopback transfer)
./gradlew :app:assembleDebug    # debug APK
./gradlew :app:assembleRelease  # unsigned, minified — real size check
```

Requires Android SDK at `~/Library/Android/sdk` (local.properties). Java 21 OK.

## Emulator testing

Two emulators can share one virtual Wi-Fi subnet — unicast TCP crosses, and on the Android 36.1
image (seen 2026-11-20) multicast mDNS crosses too: two fresh AVDs started this way found each other on
the radar with no `dev_peer` (older images didn't, hence the `dev_peer` hooks). Name them with
`adb shell settings put global device_name "…"` (CrossDrop falls back to it). `screenrecord` can't encode
the Pixel 9 Pro's 1280x2856 natively and silently squashes to 720x1280: pass `--size 720x1606`.

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
