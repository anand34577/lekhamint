# Publishing the Android app on Google Play

The app is built to meet Google Play's requirements. This page lists what the build already does and
the steps that must be done by hand in the Play Console.

## What the build already covers

| Requirement | How LekhaMint meets it |
|---|---|
| App Bundle upload | `./gradlew bundlePlayRelease` makes `app-play-release.aab`; the release workflow publishes it as `LekhaMint-play.aab`. |
| Target API level (API 36 from 31 August 2026) | `targetSdk = 36`, `compileSdk = 36`. Raise both each year when Play raises the bar. |
| Release signing | The release workflow refuses to publish a debug-signed APK or bundle. |
| 16 KB memory pages | Native code: AndroidX's `libandroidx.graphics.path.so` and the receipt reader `libkoshocr.so` (ncnn, built with NDK 27). Both have 16 KB-aligned ELF segments (`llvm-readelf -lW`, `Align 0x4000`); they are stored compressed (`useLegacyPackaging`), so `zipalign -c -P 16` reports them as "OK - compressed". |
| Receipt reading and voice | Receipts are read on the phone (PaddleOCR models with ncnn, no Play services). Photos come from the camera app (`TakePicture`) or the photo picker and speech from `RecognizerIntent`, so the app holds no camera, microphone or storage permission. |
| In-app account deletion | *More → Sync → Delete account* (and *Settings → Your account* on the web) calls `DELETE /api/v1/auth/me`. |
| Privacy policy | [`PRIVACY.md`](../PRIVACY.md), linked from *More → Privacy policy*. |
| Generative AI content | Each assistant answer has a *Report* action; the answer is never attached automatically. |
| Notifications permission | Asked for at runtime on Android 13+ (onboarding, or *More → Allow reminders*). |
| Version codes | `MAJOR*10000 + MINOR*100 + PATCH` from the tag, so each release is higher than the last. |
| No restricted permissions | Internet, network state, notifications, biometrics, plus WorkManager's wake lock and boot-completed. No SMS, contacts, location, storage, exact alarms or foreground services (WorkManager's foreground service is removed in the manifest). |
| Notification access (optional) | *More → Find payments automatically* uses a `NotificationListenerService` to read bank, card and payment-app alerts **on the phone** and list payments to review. It only runs after the person turns it on in Android's *Notification access* settings, after an in-app explanation. Chat apps are skipped; nothing is uploaded or saved until the person taps *Add*. Describe it in the store listing and the Data safety form ("Messages: read on device, not collected"). |

## One-time setup

1. **Upload key.** Create it once and keep a copy somewhere safe:
   ```sh
   keytool -genkeypair -v -keystore lekhamint-upload.jks -alias lekhamint -keyalg RSA -keysize 4096 -validity 10000
   ```
   Add it to the repository secrets `ANDROID_KEYSTORE_BASE64` (`base64 -w0 lekhamint-upload.jks`),
   `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. For local builds, put
   the same values in `android/keystore.properties` (ignored by git).
2. **Play App Signing.** Keep it on (the default). Google holds the app signing key; you upload with the key above.
3. **Tag a release** (`git tag v1.2.0 && git push --tags`) and upload `LekhaMint-play.aab` from the release to an internal test track first.

## Play Console answers

**App access:** all features work without signing in. Sync needs a self-hosted server; say so in the notes.

**Ads:** no ads.

**Content rating:** a finance tool with no user-to-user content, no gambling, no violence.

**Target audience:** 18 and over (it handles loans and bank data).

**Financial features declaration:** the app *tracks* personal finances, loans and deposits. It does not
offer loans, move money, connect to banks or trade. Choose the personal finance / budgeting option, not
"loans" (that policy is for apps that lend money).

**Data safety form:**

| Question | Answer |
|---|---|
| Does the app collect or share user data? | **No.** Google defines "collected" as sent off the device to the developer or a third party. LekhaMint sends data only to a server or model address the user enters, which counts as user-initiated transfer to a destination they choose. |
| Is data encrypted in transit? | Yes when the user's server or model uses `https://`. Plain `http://` is allowed for home-network addresses and the app warns about it, so answer carefully: if in doubt, answer **No** for "all data encrypted in transit". |
| Can users request deletion? | Yes: in-app (*Delete account*) and through the URL below. |

If a reviewer disagrees about "no collection", the conservative answer is: *Financial info* (purchase
history, other financial info), *Personal info* (email, name), collected optionally, for *App
functionality*, not shared, encrypted in transit only over https, deletable.

**Privacy policy URL:** `https://github.com/anand34577/lekhamint/blob/main/PRIVACY.md`

**Account deletion URL:** `https://github.com/anand34577/lekhamint/blob/main/PRIVACY.md#accounts-and-deleting-your-data`

## Store listing assets you still need to make

- App icon 512 × 512 PNG (from `android/app/src/main/res/drawable/ic_launcher_foreground.xml` on the launcher background colour).
- Feature graphic 1024 × 500.
- At least 2 phone screenshots (`docs/images` has some; take them on a recent emulator at 1080 × 2400).
- Short description (80 characters), for example: *Private, offline money manager: expenses, budgets, loans, EMIs and deposits.*

## Known warnings that are intentional

Android lint warns that the network security config allows plain HTTP and user-installed certificates.
This is on purpose: self-hosted servers often run on a home network over HTTP or with a private
certificate authority (for example Caddy `tls internal`). Play allows this; the app warns before sending
a password over plain HTTP to an address outside the home network.
