## Downloads

| Platform | Recommended | Direct download (below) |
| --- | --- | --- |
| Android 10+ | [Google Play](https://play.google.com/store/apps/details?id=com.crossdrop) | `CrossDrop-{{VERSION}}-android.apk` |
| Windows 10/11 (x64) | [Microsoft Store](https://apps.microsoft.com/detail/9PBRFPV9GD7P) | `CrossDrop-{{VERSION}}-windows-x64.msi` or `.exe` |
| macOS, Apple Silicon | — | `CrossDrop-{{VERSION}}-macos-apple-silicon.dmg` |
| macOS, Intel | — | `CrossDrop-{{VERSION}}-macos-intel.dmg` |

The store versions update automatically; the {{VERSION}} store updates are in review and roll out once approved.

### Install notes

- **Android APK:** if CrossDrop is already installed from Google Play, update it there — the APK is signed with a different key, so Android won't install it over the Play version.
- **Windows MSI/EXE:** per-user install, no admin prompt. The installers aren't code-signed yet, so SmartScreen may warn: choose **More info → Run anyway**. The Microsoft Store version has no warning. Allow CrossDrop on *private* networks when Windows Firewall asks.
- **macOS:** open the DMG and drag CrossDrop to Applications. Both builds are signed with a Developer ID and notarized by Apple, so it opens like any other app. Allow it on the Local Network prompt.

Checksums are in `SHA256SUMS.txt`.
