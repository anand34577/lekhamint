# Privacy policy

Last updated: 26 October 2026

LekhaMint is a free, open-source money manager. This policy covers the LekhaMint Android app
(package `app.kosh`), the web app and the self-hosted LekhaMint server. The source code is public at
<https://github.com/anand34577/lekhamint>, so every statement here can be checked.

## The short version

- The developers of LekhaMint do not run a cloud service and never receive your data.
- The Android app works fully offline. Your records stay on your phone unless **you** turn on sync or
  the assistant.
- There are no ads, no analytics, no tracking and no crash reporting SDKs.

## What the app stores on your phone

Accounts, transactions, categories, budgets, loans, deposits, people you lend to or borrow from, notes
and settings are stored in the app's private storage. Automatic backups are written to the app's
private storage; backups you export go where you choose and can be password-encrypted. Server sign-in
tokens and assistant API keys are encrypted with a key held in the Android Keystore.

Some things are read on the phone and never sent anywhere:

- **Receipt photos.** When you attach or scan a receipt, the app reads its amount, date and shop with
  text-recognition models built into the app. No Google Play services and no network are involved. The
  photo is saved with the transaction like any other attachment.
- **Bank statements you import.** The file (PDF, Excel, CSV, OFX or QIF, and its password if it has one)
  is read on the phone; only the entries you choose to import are saved. The file and its password are
  not kept.
- **Voice entry.** The microphone button uses your phone's own speech recognition service (offline when
  the phone supports it). LekhaMint receives only the words, not the recording. The app does not hold
  the microphone permission itself.

Android's own cloud backup and device-to-device transfer are turned off for this app, so your data is
not copied to Google's backup service.

## When data leaves your phone

Only in these cases, and only after you set them up:

1. **Sync with your own server.** If you sign in to a LekhaMint server, your records are sent to that
   server address and nowhere else. The server is run by you or by someone you choose (for example a
   family member). Its operator can read the data stored on it. If you share a book with someone
   (*More → Share with family*), they see that book's records on their own devices.
2. **The assistant.** If you configure the assistant, your question and the records it looks up
   (totals, balances, loans, deposits and, if you allow it, payee names and notes) are sent to the
   model service address you enter. The app asks for your consent before the first request to each
   service. That service's own privacy terms apply. If you point it at a model on your phone or home
   network, nothing leaves that network.
3. **Files you share.** Statements (PDF, Excel) and backups you export or share go wherever you send
   them.

The app never sends data to the LekhaMint developers.

## Permissions

- **Internet and network state:** sync and the assistant, only when you set them up.
- **Notifications:** bill, EMI, deposit and budget reminders. Reminders never show amounts on the lock
  screen.
- **Notification access (optional):** only if you turn on *Find payments automatically*. The app then
  reads incoming notifications on your phone to spot bank, card and payment-app alerts. Alerts that look
  like a payment (amount, payee, the account's last digits and the alert text) are kept in a small list
  on the phone, which is never synced or backed up; everything else is ignored, and chat apps are
  skipped. Nothing is sent anywhere, and nothing is recorded until you tap *Add*. Turn it off in
  Android's *Notification access* settings at any time.
- **Biometrics:** the optional app lock. Fingerprint and face data stay with Android; the app only
  receives "unlocked" or "not unlocked".

## Accounts and deleting your data

You do not need an account to use the Android app. A server account exists only on the LekhaMint server
you sign in to.

- **Delete a server account:** in the Android app open *More → Sync → Delete account*, or in the web
  app open *Settings → Your account → Delete account*. This deletes the account, every book that only
  you own and all of their data from that server. You are removed from books that have another owner.
- **Delete data on your phone:** delete records in the app, or uninstall the app (or clear its storage
  in Android settings), which removes everything it stored.

## Children

LekhaMint is a general-audience finance tool and is not directed at children under 13.

## Changes

Changes to this policy are published in this file, and its history is visible in the repository.

## Contact

Open an issue at <https://github.com/anand34577/lekhamint/issues> or see [SECURITY.md](SECURITY.md) to
report a security problem privately.
