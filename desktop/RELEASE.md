# Shipping the desktop apps (macOS DMG, Windows MSI/EXE)

Everything here is **opt-in**: with no credentials, `packageDmg` / `packageMsi` still produce the same
unsigned installers as before. Nothing is ever published automatically.

## macOS — Developer ID signing + notarization

Without this, Gatekeeper makes users right-click → Open on first launch, and Homebrew will not accept
the app. Signed + notarized, it opens with a double-click.

### One-time setup (needs your Apple Developer account)

1. **Certificate.** Xcode → Settings → Accounts → your team → *Manage Certificates* → **+** →
   **Developer ID Application** (normally only the Account Holder can create it). This is *not* the
   "Apple Development" / "Apple Distribution" certificate the iOS app uses.
   Check: `security find-identity -v -p codesigning` must list
   `Developer ID Application: <Name> (<TEAMID>)`.
2. **App-specific password** for notarization: appleid.apple.com → Sign-In and Security →
   App-Specific Passwords.
3. **Team ID** — the same value as `DEVELOPMENT_TEAM` in `ios/project.yml`.

### Build locally

```sh
export JAVA_HOME=<full JDK 21>            # e.g. ~/.gradle/jdks/eclipse_adoptium-21-*/jdk-*/Contents/Home
export MACOS_SIGN_IDENTITY="<Name> (<TEAMID>)"      # the part after "Developer ID Application: "
export MACOS_NOTARY_APPLE_ID="you@example.com"
read -s MACOS_NOTARY_PASSWORD; export MACOS_NOTARY_PASSWORD    # keeps it out of shell history
export MACOS_TEAM_ID="<TEAMID>"

./gradlew :desktop:packageDmg :desktop:notarizeDmg   # sign, submit to Apple, wait, staple
```

Verify the result:

```sh
DMG=desktop/build/compose/binaries/main/dmg/CrossDrop-*.dmg
xcrun stapler validate $DMG
hdiutil attach $DMG -nobrowse && spctl --assess --type execute -vv /Volumes/CrossDrop/CrossDrop.app
```

`spctl` must say `accepted` / `source=Notarized Developer ID`.

How it works: setting `MACOS_SIGN_IDENTITY` flips `signing.sign` on in `desktop/build.gradle.kts`;
jpackage then signs the launcher, the Java runtime and the native libraries inside the bundled JARs (JNA,
Skiko) with the hardened runtime. A JVM needs JIT / unsigned-memory / library-validation entitlements
to run under the hardened runtime — Compose's default entitlements already grant exactly those three,
so there is no custom entitlements file to maintain. (Verified: the ad-hoc, hardened-runtime build launches
and loads Skiko with them.)

**If Apple rejects the build**: `xcrun notarytool log <submission-id> --apple-id … --team-id … --password …`
names the offending file. Usual causes are a native library that ended up unsigned, or missing
hardened-runtime / timestamp on a binary inside a JAR.

### CI

`.github/workflows/desktop-macos.yml` (manual run, or push a `desktop-v*` tag) builds an Apple Silicon
and an Intel DMG — a JVM app bundles an arch-specific runtime, so one DMG cannot serve both. It signs and
notarizes when these repository secrets exist, and builds unsigned when they don't:

| Secret | What |
| --- | --- |
| `MACOS_CERTIFICATE` | the Developer ID Application cert **with its private key**, exported from Keychain Access as `.p12`, then `base64 -i cert.p12 \| pbcopy` |
| `MACOS_CERTIFICATE_PASSWORD` | the password you chose when exporting the `.p12` |
| `MACOS_SIGN_IDENTITY` | `<Name> (<TEAMID>)` |
| `MACOS_NOTARY_APPLE_ID` / `MACOS_NOTARY_PASSWORD` / `MACOS_TEAM_ID` | Apple ID, app-specific password, Team ID |

Never commit the `.p12`. macOS runner minutes bill at 10x on a private repository, which is why the
workflow does not run on pull requests.

## Windows — still unsigned

The MSI/EXE (`.github/workflows/desktop-windows.yml`) trigger SmartScreen until they are code-signed.
Signing needs a decision on a provider, and none is wired up yet. The realistic options: **Azure Artifact
Signing** (formerly Trusted Signing; cheap, but eligibility depends on country/organisation age),
**SignPath Foundation** (free, open-source projects only), or a CA-issued OV/EV certificate.
Whichever is chosen, signing is a post-build `signtool` step on the `.msi`/`.exe` in that workflow.

## Version numbers

`APP_VERSION` in `Main.kt` is the single source: the macOS bundle version and the Windows
ProductVersion are both derived from it (`0.x.y` → `1.x.y`). Bump it for every release — Windows Installer
refuses to upgrade to an equal version.

## Other channels (status)

- **Mac App Store / iOS App Store** — `ios/RELEASE.md` (the Catalyst build; independent of this DMG).
- **Google Play** — `./gradlew :app:bundleRelease` (signed with `key.properties`, git-ignored).
- **Homebrew cask, winget, F-Droid** — all need a *public* download URL or public source; not possible
  while the repository is private and releases are not published anywhere.
