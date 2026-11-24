# M_Tracker

Private, offline expense tracker (Kotlin, Jetpack Compose, Room). No INTERNET permission.

## Build
Open in Android Studio (Koala+) and run the `standardDebug` variant, or use GitHub Actions below.
Two flavors: **standard** (recommended, no notification-reading code) and **autofill** (adds notification auto-fill).

## Quick start (GitHub Actions)
See the step list in the chat, or: create a PRIVATE repo, push this folder's contents to `main`, open Actions, run **Build M_Tracker APK**, download the `M_Tracker-apk` artifact.

## Signed releases (fixes "app changes every build" + helps Play Protect)
1. Create ONE key and keep it forever:
   `keytool -genkeypair -v -keystore release.jks -alias mtracker -keyalg RSA -keysize 2048 -validity 10000`
2. `base64 -w0 release.jks` -> GitHub repo Settings > Secrets > Actions:
   `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (mtracker), `KEY_PASSWORD`.
3. Push to `main`; download the APK artifact. Always sign updates with the same key.

## Installing on your phone
- Install from a file manager (not straight from a browser/chat). If Play Protect asks, tap *Scan app*.
- Usage access greyed out: App info > ⋮ > *Allow restricted settings*, then enable usage access
  (the in-app Settings > Install & permission help has the steps and buttons).
