# Changelog

## 1.2.0

**Fixes**
- Net worth now counts money others owe you and money you owe people (web, Android and a new server `reports/position`). Lending to a friend no longer lowers net worth.
- Saves can no longer create duplicates on double click or a retried request: forms keep one record ID and create with `create_if_missing`; Android does the same.
- Repayments above what is owed are refused with an explanation; overpaid debts show clearly and count on the other side. Web and Android now agree.
- Reminders no longer show amounts on the lock screen.
- The web app no longer signs you out when you type a wrong current password in *Change password*.
- A wrong AI key on the server's assistant proxy is reported as a server problem (502) instead of a 401 that looked like an expired session.
- Android: release lint is clean (Quick Settings tile on older Android, app lock activity lookup, CSV byte-order mark); a version tag like `v1.3.0-rc1` no longer breaks the build.

**New**
- Loans & EMIs: new or running loans, EMI split into principal and interest, schedules, prepayment (keep EMI or keep tenure), rate changes, foreclosure, reminders.
- Fixed and recurring deposits: maturity estimates, payouts, RD instalments, maturity calendar, recording maturity or early closure.
- Lent & borrowed by person, with statements, oldest-first repayment, interest, write-off confirmation.
- Calculators: EMI, affordability, FD/RD, bill splitter (saves your share and what others owe), household & budget planner, savings goal, emergency fund, debt payoff, subscriptions, net worth over time.
- Statements & export: income & spending, account statement, transactions, loans, deposits, person, net worth, budgets, as PDF (print view on web, native on Android) and Excel; full-data Excel export.
- Dashboard: everything due (bills, EMIs, RD, maturities, debts), "safe to spend", shortfall warnings. Transaction form warns before overdrawing, passing a card limit or a likely duplicate.
- Assistant with any OpenAI-compatible model (cloud, home server, on-phone), read-only tools, consent prompt and optional server proxy. See docs/11-assistant.md.
- Delete account: *More → Sync* on Android, *Settings → Your account* on the web, `DELETE /api/v1/auth/me` on the server. Deletes the account and the books only you own.
- Google Play readiness: release builds also produce an App Bundle (`LekhaMint-play.aab`), a privacy policy ([PRIVACY.md](PRIVACY.md)) linked in the app, a *Report* action on assistant answers, and a publishing guide (docs/12-google-play.md).
- Schema 3: `loans`, `deposits`, and `transactions.loan_id / deposit_id / part`; account type `deposit`.

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
