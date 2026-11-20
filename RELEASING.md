# Releasing CrossDrop

One tag releases everywhere the pipeline is connected. No browser needed.

```sh
tools/release/release.sh v0.6.0      # checks, tags, pushes, then waits for the result
# or just: git tag v0.6.0 && git push origin v0.6.0
```

`.github/workflows/release.yml` builds and publishes:

| What | Where | Status |
| --- | --- | --- |
| Android bundle + APK | GitHub release; the bundle goes to **Google Play** (production, 100%, sent for review) | live |
| Windows MSI, EXE | GitHub release | live |
| Windows MSIX | **Microsoft Store** (submitted for certification, publishes when it passes) | live |
| macOS DMGs (Apple Silicon, Intel) | GitHub release, signed with your Developer ID and **notarized** by Apple | live |
| Linux `.deb`, `.rpm`, `.tar.gz` (x86-64) | GitHub release (unsigned; no Linux store yet) | live from 0.8.0 (built by `desktop-linux.yml`) |
| iPhone / iPad / Mac App Store builds | **App Store Connect**: uploaded and sent to **App Review**; each releases by itself after Apple approves it | live |

Each store still runs its own review, so they don't go live at the same moment.

## Before you tag

1. Bump the versions: `versionCode` and `versionName` in `app/build.gradle.kts`, `APP_VERSION` in
   `desktop/src/main/kotlin/com/crossdrop/Main.kt` (the Windows package version follows it), and
   `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in `ios/project.yml`. Play refuses a `versionCode` it has seen.
2. Write the notes in `release/<version>/`:
   - `whats-new.md`: the GitHub release text (the shared download/install notes come from `release/footer.md`),
   - `play.txt`: Google Play "What's new", at most 500 characters,
   - `msstore.txt`: Microsoft Store "What's new", at most 1500 characters,
   - `appstore.txt`: App Store "What's New" (English, at most 4000 characters; every existing App Store language gets this text),
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
| `AZURE_AD_TENANT_ID`, `AZURE_AD_APPLICATION_CLIENT_ID`, `AZURE_AD_APPLICATION_SECRET`, `SELLER_ID` | Microsoft Store (Partner Center) API access, see below. **The client secret expires 2028-10-17.** |
| `APPSTORE_KEY_ID`, `APPSTORE_ISSUER_ID`, `APPSTORE_KEY_P8` | App Store Connect API key `crossdrop-ci` (Admin role): cloud signing and upload of the App Store builds, and Apple's notary service for the Mac downloads. See Apple below. |
| `MACOS_CERTIFICATE`, `MACOS_CERTIFICATE_PASSWORD`, `MACOS_SIGN_IDENTITY`, `MACOS_TEAM_ID` | The "Developer ID Application: Gogita Kupatadze (C58ABB3474)" certificate (one `.p12` with only that identity, random password) used to sign the Mac downloads. **Expires 2027-12-07.** |

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

### Microsoft Store

The Store's API needs a Microsoft Entra tenant linked to the Partner Center account. Set up on 2026-11-17:

- Tenant **GT** (`kupatadze.onmicrosoft.com`), created through Partner Center's "Create Microsoft Entra ID" (that
  wizard asks for a billing account and a payment method; the card is only for identity verification). Its admin
  is `GogitaKupatadze@kupatadze.onmicrosoft.com`, protected by Microsoft Authenticator. This is a separate account
  from the Outlook login: Partner Center's *User management* page needs the admin (Account settings → User
  management → "Sign in with Microsoft Entra ID"), and the Outlook account can't sign in to the tenant.
- The tenant is associated with the Partner Center account (Account settings → Tenants).
- App registration **`crossdrop-release`** (client ID `c71e80b6-fc00-4a92-b7d7-b805fadad446`) with a client secret, added in
  Partner Center → User management → Microsoft Entra applications with the **Developer** role (upload packages
  and submit; no financial info, no account settings). Microsoft's docs say "Manager", but Developer was proven
  enough (below), so it's the smaller key. If the API ever answers 401/403, widen the role to Manager there.
- Secrets `AZURE_AD_TENANT_ID`, `AZURE_AD_APPLICATION_CLIENT_ID`, `AZURE_AD_APPLICATION_SECRET`, `SELLER_ID`
  (32006500) are set in GitHub.

The release's Store job runs `msstore publish --noCommit` (upload as a draft), sets "What's new" from
`release/<version>/msstore.txt`, then `msstore submission publish`. The package must have a higher version than
the live one (the version bump before tagging takes care of that).

**Check it any time:** Actions → *Microsoft Store check* → Run workflow. It logs in and reads the product (read-only).
Give it `msix_run_id` (a build run whose `CrossDrop-windows` artifact has an `.msix` with a higher version) to also
upload that package as a draft, set the notes and delete the draft; that passed on 2026-11-17.

**Renewing the client secret** (before 2028-10-17; the check workflow starts failing when it expires): sign in to
https://entra.microsoft.com as the tenant admin → App registrations → crossdrop-release → Certificates & secrets
→ New client secret, then `gh secret set AZURE_AD_APPLICATION_SECRET` with the new value (pipe it from the
clipboard: `pbpaste | gh secret set AZURE_AD_APPLICATION_SECRET`, so it is never printed).

### Apple

Set up 2026-11-17, nothing in the repository holds a certificate or a provisioning profile:

- **App Store Connect API key** `crossdrop-ci` (Key ID `GRJCH6W9NX`, role **Admin**, which Apple's cloud signing needs;
  the issuer ID is in the secret `APPSTORE_ISSUER_ID`). The `.p8` file was downloaded once, loaded into the secret
  `APPSTORE_KEY_P8` and then deleted from the Mac. To rotate or revoke: App Store Connect → Users and Access →
  Integrations → Team Keys.
- **The `apple` job** (macOS runner, newest stable Xcode, at least 26) archives the iPhone/iPad app and the Mac
  (Catalyst) app and signs them through Apple's cloud signing with that key. On a tag it uploads both to App Store
  Connect. A dry run signs and exports only.
- **The `appstore_review` job** (`tools/release/appstore_submit.py`, Apple's API with the same key) then waits for Apple to
  process the builds, attaches each to the App Store version (renaming an editable older version or creating one),
  sets "What's New" from `release/<version>/appstore.txt` and submits iPhone/iPad and the Mac app to **App Review**, set to
  release automatically after approval. A version already in review or live is left alone, so re-running is safe.
  If Apple wants something only you can give (a screenshot size, the review contact details, a form), that job fails
  *without* failing the release: the builds stay uploaded, `release.sh` says "NOT submitted", you fix it in App Store
  Connect and run **Actions -> App Store submit** (version, build, notes folder, "submit" on; with it off the workflow only
  reads and prints the state). Apple's own review then takes about a day.
- **It skips itself** (with a notice) when `ios/project.yml` still has the same `CURRENT_PROJECT_VERSION` as the previous
  release, because App Store Connect refuses a build number it has seen. To ship an Apple update, bump
  `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` there before tagging (last uploaded and submitted: 1.4.0, build 5).
- **The Mac downloads** are signed with the Developer ID certificate and notarized with the same API key (no Apple
  ID password anywhere); macOS opens them with a double-click. The certificate expires **2027-12-07**: renew it at
  developer.apple.com → Certificates (type "Developer ID Application"), export the new identity and replace the four
  `MACOS_*` secrets.
- Proven on 2026-11-17: a dry run built and cloud-signed both Apple apps on a clean GitHub runner, notarized and
  stapled both DMGs, and App Store Connect's upload service accepted the key (it rejected only the already-used
  build number 3 when I validated that build). The real upload happens on the first release with a bumped build.
