# Security policy

LekhaMint holds people's financial records, so security reports are taken seriously.

## Reporting a vulnerability

Please **don't open a public issue**. Use GitHub's private reporting: [Security → Report a vulnerability](https://github.com/anand34577/lekhamint/security/advisories/new). Include the version (`lekhamint version`, or the app version under *More*), what you found, and how to reproduce it. You will get a reply within a few days.

## Supported versions

Security fixes go into the latest release. Please upgrade before reporting.

## What the project already does

- Passwords hashed with Argon2id; session tokens stored only as SHA-256 hashes; changing a password signs out every other device.
- Sign-in and registration rate limited per client address; sign-ups closed by default after the first account.
- Every book is private to its members, with owner, editor and viewer roles checked on every request; IDs of books you are not a member of are indistinguishable from missing ones.
- Strict Content-Security-Policy, `X-Frame-Options: DENY`, `nosniff` and `no-referrer` headers; request bodies are size-limited.
- No telemetry, analytics or third-party requests from any app.
- Android: optional app lock, encrypted backups (AES-256-GCM with an Argon2id key derived from your passphrase), backups excluded from cloud auto-backup.
- The Docker image is distroless and runs as a non-root user with a read-only filesystem; the systemd unit is sandboxed (`ProtectSystem=strict`, `NoNewPrivileges`).
- Release downloads are published with SHA-256 checksums, which the installers verify, and the Android APK is signed with the same key for every release.
