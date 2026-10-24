# LekhaMint — a modern, local-first money manager

> *Lekha* (लेखा) means "account" or "record": LekhaMint keeps a clear, exact record of your money.

LekhaMint tracks money across accounts, currencies and devices. The Android app works fully
offline with no account. A self-hosted server is optional: it adds sync between devices,
a web app, and off-device backups.

| Part | Tech | Status |
|---|---|---|
| `server/` | Go, SQLite (default) or PostgreSQL, one static binary (~16 MB) that also serves the web app | **Working**, tested |
| `android/` | Kotlin, Jetpack Compose, Room, WorkManager | **Working**, tested on an emulator; 4 MB release APK |
| `web/` | React + TypeScript, built into the server binary; installable PWA | **Working**, tested end to end in Chrome |
| iOS | SwiftUI on the same sync protocol | Future |

**What you can do today:** log expenses, income and transfers in seconds (with a calculator
pad), manage accounts with exact calculated balances, reconcile, budgets with pace and
rollover, reports (category donut, 12-month trend, needs/wants/savings), recurring bills
with reminders or auto-entry, lent/borrowed tracking, Trash with undo, app lock, hide
amounts, dark/AMOLED themes, encrypted `.kosh` backups (daily, plus a folder of your choice),
CSV/JSON export, and two-way sync between phones and the web.

Also: splits, tags, auto-categorise rules, CSV statement import with duplicate detection, lent &
borrowed with repayments, multi-currency totals with your own exchange rates, book sharing with
family (editor or viewer), and a home-screen widget. The web app adds a command palette
(Ctrl/⌘ K), natural-language quick add ("450 swiggy dinner yesterday #work @hdfc"), dashboard
insights with a spending-pace chart, an uncategorised review queue and keyboard shortcuts (press ?).

## Principles

1. **Local-first.** Every client keeps the full ledger on the device. The server is a sync hub and backup target. It is never required.
2. **Money is exact.** Amounts are integers in minor units (paise, cents). Floats are never used.
3. **Correct accounting.** Transfers, card payments and investments move money; they are not spending. Refunds reduce spending. Balances are calculated, never stored.
4. **History is never lost.** Records are soft-deleted (tombstones), every change is versioned, and backups are automatic.
5. **Fast entry.** Adding an expense takes about 3 taps and 5 seconds. Everything else comes second.
6. **Few dependencies, no lock-in.** Runs on Linux, macOS, Windows, Docker or a Raspberry Pi with no internet. Every byte can be exported.

## Documentation

| Doc | What it covers |
|---|---|
| [01 – Product & UX](docs/01-product-and-ux.md) | Personas, screens, interaction rules, accessibility |
| [02 – Expense domain study](docs/02-expense-domain.md) | How personal expenses work: types, transfers, refunds, credit cards, categories, budgets |
| [03 – Architecture](docs/03-architecture.md) | System overview, server, Android and web layering, scaling plan |
| [04 – Data model](docs/04-data-model.md) | Every entity and field, with the rules behind them |
| [05 – Sync protocol](docs/05-sync-protocol.md) | Offline-first sync with hybrid logical clocks |
| [06 – Backup & restore](docs/06-backup-restore.md) | Client and server backup strategy, file format, encryption |
| [07 – API reference](docs/07-api.md) | HTTP endpoints |
| [08 – Deployment](docs/08-deployment.md) | Docker, bare metal (systemd), Windows, configuration, building the apps |
| [09 – Roadmap](docs/09-roadmap.md) | What is done and what comes next, phase by phase |
| [10 – Product vision](docs/10-product-vision.md) | From expense tracker to personal finance manager: goals, investments, net worth, automation, AI |

## Quick start

```bash
# Server + web app (open http://localhost:8080 and create your account)
cd server
go run ./cmd/kosh serve
# or: docker compose up -d

# Android app (Android SDK + JDK 17)
cd android
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
```

In the app, go to **More → Sync**, enter the server address, sign in, and choose
"Upload this book". The same data then appears in the web app.

## Tests

| Part | Command | Covers |
|---|---|---|
| Server | `cd server && make test` (add `KOSH_TEST_PG_DSN` for PostgreSQL) | Sync semantics, full API journey with exact amounts, auth hardening, backups, web serving, reports (budgets, tags, payees, FX), filters |
| Android | `cd android && ./gradlew testDebugUnitTest` (34 tests) and `connectedDebugAndroidTest` (3, on an emulator) | Money, calculator, HLC and UUIDv5 (cross-checked with the server), recurrence, budgets, reports, rules, splits, debts, sync codec, backup encryption, CSV; Room migration 1→2 |
| Web | `cd web && npm test` (31 tests) | Money, calculator, budget periods, IDs, CSV import, rules, recurrence (same cases as Android), quick-add parsing, insights |

CI (`.github/workflows/ci.yml`) runs all of these, plus the server suite on PostgreSQL 17.
