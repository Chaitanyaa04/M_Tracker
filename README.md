# Arthak

A private expense tracker (Kotlin, Jetpack Compose, Room). Your data stays on the phone. The only network use
is checking/downloading app updates from this repository's GitHub Releases.

## Publish an update (what the in-app updater looks for)
1. Make this repository **public** (the app reads Releases without logging in; no secrets are in the code).
2. One-time (offline on your own machine — never generate signing keys in public CI logs/summaries):
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias mtracker -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 release.jks
   ```
   Store the resulting values in Settings > Secrets and variables > Actions
   (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and keep an offline backup of `release.jks`.
3. To ship a version: `git tag v1.3.0 && git push origin v1.3.0`.
   The workflow builds a signed APK and creates a Release named v1.3.0 with the APK attached.
4. On the phone: a banner appears in the app ("Update available"). Tap **Update**, confirm once in Android's installer.

First install: download the APK from the Release (or the workflow artifact) and install it from your file manager.
Allow "Install unknown apps" for Arthak the first time you update from inside the app.

## Flavors
`standard` is the recommended build (no notification-reading code). `autofill` adds optional notification auto-fill.
