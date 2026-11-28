# Arthak

A private, offline-first expense tracker built with Kotlin, Jetpack Compose and Room. Your financial data stays on the phone. The only network use is checking and downloading app updates from this repository's GitHub Releases.

## Build locally

Use the committed Gradle wrapper:

```powershell
.\gradlew test
.\gradlew assembleDebug
.\gradlew assembleRelease
```

Release signing uses environment variables `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Debug builds use Android's default debug keystore.

## Signing key

Generate the keystore locally once and keep an offline backup:

```bash
keytool -genkeypair -v -keystore release.jks -alias mtracker -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks
```

Store these values as GitHub Actions secrets, never in the repository:

- `KEYSTORE_B64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## Releases

The repository is public because the app reads Releases without logging in. No secrets are in the code.

- Every push to `main` builds, tests, signs and publishes a dev release named `dev-1.3.<run number>`.
- Stable releases use tags such as `v1.4.0`. Tags `v1.3.x` are rejected because `v1.3.0` is already published.
- Dev version codes start above the installed `v1.3.0` code (`10300`); future stable tags use a much higher code base so they can install over dev builds.
- Manual workflow runs build and verify an artifact but do **not** publish a Release.
- The workflow refuses to run without signing secrets and verifies the APK with `apksigner` before upload.

## Install and updates

First install: download the APK from the Releases page and install it from your file manager. Allow "Install unknown apps" only for that source.

Afterwards, the app checks GitHub daily and on resume. It shows one notification per new tag. Downloading is restricted to trusted GitHub HTTPS hosts, capped at 100 MB, checked against the installed signing certificate and package name, and installed only through Android's own confirmation screen.

## Themes

System, Paper (light), OLED (pure black) and Soft Dark (charcoal) are available in Settings. All four chips stay reachable on narrow screens; System dark uses Soft Dark, and OLED Black is pure black. The daily safe-to-spend card is off by default and can be enabled under Settings → Appearance.

## Payment prompts

Add the payment apps you use under Settings → Payment reminders. Arthak only notices *which* of those apps was opened; it never reads notification text, SMS, screens, contacts or amounts. It asks **Expense / Income / Wrong call**, then opens an empty entry sheet so you type the amount and merchant yourself. Nothing is saved automatically from a notification. Quiet mode checks about every 15 minutes; Instant mode asks sooner and shows a small status notification.

## Data safety

- Backups are encrypted locally with AES-256-GCM using your passphrase and include the Recycle Bin and change history. Restore previews and validates the file before asking for confirmation, and makes an app-private safety snapshot first. If a backup folder and passphrase are configured, an encrypted copy is saved there too.
- Deleted transactions go to a local Recycle Bin. Removing an account archives it and moves its ordinary transactions there, while settlement records stay so settled debts keep their money trail; restoring recreates a missing account if needed.
- Settings → Change history shows a small local log of edits, deletions and restores.
- Accounts support Bank, Cash, UPI, Wallet, Credit card and short custom types. Archived accounts are hidden from pickers but their past transactions are kept.
- Heads can be reordered, hidden, renamed and re-iconed under Settings → Heads & icons. Hidden heads stay in past transactions but leave the pickers.

## Flavors

The old `standard` / `autofill` flavors were removed. The main build workflow is the only supported build.

## Roadmap

See [ROADMAP.md](ROADMAP.md) for shipped work and the remaining queue.
