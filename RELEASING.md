# Releasing CrossDrop

One tag releases everywhere the pipeline is connected. No browser needed.

```sh
git tag v0.6.0 && git push origin v0.6.0
```

`.github/workflows/release.yml` then builds and publishes:

| What | Where | Status |
| --- | --- | --- |
| Android bundle + APK | GitHub release; the bundle goes to **Google Play** (production, 100%, sent for review) | live |
| Windows MSI, EXE | GitHub release | live |
| Windows MSIX | **Microsoft Store** (submitted for certification, publishes when it passes) | waiting for credentials, see below |
| macOS DMGs (Apple Silicon, Intel) | GitHub release (unsigned until Apple credentials exist) | live, unsigned |
| iPhone / iPad / Mac App Store builds | App Store Connect | not connected yet |

Each store still runs its own review, so they don't go live at the same moment.

## Before you tag

1. Bump the versions: `versionCode` and `versionName` in `app/build.gradle.kts`, `APP_VERSION` in
   `desktop/src/main/kotlin/com/crossdrop/Main.kt` (the Windows package version follows it), and
   `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in `ios/project.yml`. Play refuses a `versionCode` it has seen.
2. Write the notes in `release/<version>/`:
   - `whats-new.md`: the GitHub release text (the shared download/install notes come from `release/footer.md`),
   - `play.txt`: Google Play "What's new", at most 500 characters,
   - `msstore.txt`: Microsoft Store "What's new", at most 1500 characters,
   - `title.txt` (optional): a subtitle for the GitHub release title.
3. Commit and push to `main`, then tag.

The first job of the workflow stops the release if the tag doesn't match the app version, if Android and
desktop versions differ, or if a notes file is missing.

## Dry run

A full dry run on 2026-11-17 passed every job (Android, Windows, both macOS builds, Play credentials check) and
produced the five downloads plus `SHA256SUMS.txt`.

Actions → Release → Run workflow. It builds everything, checks the signing key and the Play
credentials (it creates and discards a Play edit), and publishes nothing.

If one job fails (a flaky test, a runner that never started), fix the cause or use **Re-run failed jobs**.
The GitHub step is safe to repeat: it replaces files on an existing release.

## Secrets (repo Settings → Secrets and variables → Actions)

| Secret | What it is |
| --- | --- |
| `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | The Play **upload** key (`upload-keystore.jks`). The build refuses any other key (checked against its public fingerprint in `release.yml`). |
| `PLAY_SERVICE_ACCOUNT_JSON` | Key of the service account `crossdrop-release@api-4914878283957576311-185590.iam.gserviceaccount.com` (Google Cloud project "Google Play Android Developer"). |
| `AZURE_AD_TENANT_ID`, `AZURE_AD_APPLICATION_CLIENT_ID`, `AZURE_AD_APPLICATION_SECRET`, `SELLER_ID` | Microsoft Store (Partner Center) API access. **Not set yet.** |
| `MACOS_CERTIFICATE`, `MACOS_CERTIFICATE_PASSWORD`, `MACOS_SIGN_IDENTITY`, `MACOS_NOTARY_*`, `MACOS_TEAM_ID` | macOS signing and notarization, see `desktop/RELEASE.md`. **Not set yet**, so the DMGs are unsigned. |

Secrets can't be read back from GitHub, and the Google key was deliberately not kept anywhere else. If one is
lost, create a new one and replace it. Another repository gets its own new key for the same service account
(Google Cloud → IAM & Admin → Service accounts → crossdrop-release → Keys → Add key), not a copy of this one.

### Google Play

The service account was invited under Play Console → Users and permissions → **Account permissions**, so it
covers every app in the developer account, including apps added later. Its permissions: view app
information, view app quality, release to production, release to testing tracks (Play also switches on
"manage policy declarations" and "manage deep links" with these). No admin, financial or store-listing
rights. To use it for another app, run the script with that package name:

```sh
PLAY_SERVICE_ACCOUNT_FILE=key.json tools/release/play_upload.sh --check com.granturismo.gcamator
PLAY_SERVICE_ACCOUNT_FILE=key.json tools/release/play_upload.sh --upload <package> app.aab notes.txt
```

`tools/release/play_upload.sh` talks to the Android Publisher API with `curl`, `openssl` and `jq` only, no
third-party action. To revoke access: remove the user in Play Console, or delete the key in Google Cloud
(IAM & Admin → Service accounts → crossdrop-release → Keys).

New Play policy forms (for example the photo-permission declaration) and the Data safety form still have to
be filled in once on the website. After that, routine updates don't.

### Microsoft Store (to do)

The Store API needs a Microsoft Entra tenant linked to the Partner Center account, and the account has none
yet (Partner Center → Account settings → Tenants is empty). To connect it:
1. Link a tenant there, either **Create Microsoft Entra ID** (free) or **Associate** an existing one.
2. In Account settings → User management → Microsoft Entra applications, add an application with the
   **Manager** role, and create a client secret for it.
3. Set the four `AZURE_AD_*` / `SELLER_ID` secrets. The workflow's Store job starts working by itself; until
   then it skips with a notice. It follows Microsoft's documented flow (`msstore publish --noCommit`, set the
   release notes, `msstore submission publish`) but has not been run against the real Store yet.

### Apple (to do)

App Store Connect API key (Users and Access → Integrations → Team Keys, role App Manager) would let the
workflow upload the iPhone/iPad and Mac builds, and notarize the Mac DMGs. Then add the macOS signing
secrets from `desktop/RELEASE.md`. Not connected yet; until then iOS and Mac App Store builds are archived
and uploaded by hand (`ios/RELEASE.md`).
