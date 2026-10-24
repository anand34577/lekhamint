# Changelog

## 1.1.0

LekhaMint is now open source, with a live demo, one-line installers and Docker images.

**Try it and install it**
- Online demo at https://anand34577.github.io/lekhamint/ with a year of sample data. It runs entirely in your browser; sign in with `demo@example.com` / `demo1234`.
- One-line server install for Linux and macOS (`curl … | sh`) and Windows (`irm … | iex`). The installers verify the download checksum, set up a service that starts on boot, and upgrade in place when run again. `--docker` runs it in Docker instead.
- Docker images for amd64, arm64 and Raspberry Pi at `ghcr.io/anand34577/lekhamint`, with Compose files for SQLite, PostgreSQL and automatic HTTPS.
- `lekhamint demo` starts a throwaway server with sample data, to try the apps without entering anything.
- A wiki with guides for installation, Docker, HTTPS, configuration, the apps, backups, upgrades and troubleshooting.

**Improvements**
- The server stops with a clear message naming every bad setting, instead of quietly using a default.
- Optional log file with rotation (`LEKHAMINT_LOG_FILE`), used by the Windows and macOS installers.
- Server errors in the web app show a reference that matches the server log; a crash on one page no longer blanks the app.
- `lekhamint user add` and `user passwd` hide the password as you type.

**Fixes**
- Split transactions showed as "Uncategorised" in the Home page's recent activity.
- Money lent, borrowed or repaid was counted as income or spending in the Transactions totals (Reports were already right).
- Exported books were named `kosh-…json`.

**Upgrading from 1.0.0**
- The server is now called `lekhamint` and reads `LEKHAMINT_*` settings instead of `KOSH_*`. Its default database file is `lekhamint.db`: rename `kosh.db` (with its `-wal` and `-shm` files) or point `LEKHAMINT_DB_DSN` at it.
- Release files no longer carry the version in their name (`lekhamint-linux-amd64`), so "latest" download links always work. The Android APK is `LekhaMint.apk`.

## 1.0.0

First public release of LekhaMint: a local-first money manager with an Android app, a web app,
and an optional self-hosted server for sync and backups.

**Everywhere**
- Expenses, income and transfers (including between currencies), with exact balances.
- Budgets with rollover, per-category and per-account limits, and a daily allowance.
- Splits across categories, tags, and auto-categorise rules that also clean up payee names.
- Bills and recurring items that remind you or record themselves, without duplicates across devices.
- Lent & borrowed: lending takes money from an account, repayments bring it back, and you always
  see what is still owed. None of it counts as spending.
- Reports: category breakdown, 12-month trend, needs/wants/savings, top payees and tags.

**Web app**
- Quick add in plain words: `450 swiggy dinner yesterday #work @hdfc`.
- Command palette (Ctrl/⌘ K) and keyboard shortcuts (press ?).
- Home screen insights, a spending-pace chart and a list of transactions that need a category.
- Import bank statements (CSV); importing the same file again adds nothing.
- Share a book with family as editor or viewer; see and sign out other devices.
- Exchange rates for totals across currencies; light, dark and true-black themes.
- Installs like an app and opens offline.

**Android**
- Works fully offline with no account; encrypted automatic backups.
- Home-screen widget, quick settings tile and app shortcuts for instant entry.
- App lock, hide amounts, Material You and AMOLED themes.

**Server**
- One small binary (Linux, macOS, Windows, FreeBSD) with SQLite or PostgreSQL that also serves the web app.
