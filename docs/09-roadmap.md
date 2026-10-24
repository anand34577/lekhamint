# 09 – Roadmap

The long-term plan, organised in phases, is in [10 – Product vision](10-product-vision.md).
This page tracks concrete status.

## Done

### Server v0.2
- [x] Go server with SQLite (default) and PostgreSQL, embedded migrations
- [x] Auth: Argon2id, per-device revocable sessions, rate limiting, first user becomes admin
- [x] Ledgers with sharing (owner, editor and viewer roles)
- [x] Sync: HLC last-writer-wins, idempotent push, gap-free paginated pull, tombstones, merge-on-write
- [x] REST records API with Trash and restore, a filtered and keyset-paginated transaction list
- [x] Reports: balances, summary (refunds, splits, exclusions), trend
- [x] Backups: SQLite snapshots, streaming full dumps, ledger export/import, scheduler with retention
- [x] Serves the web app from the same binary (embedded build, or `KOSH_WEB_DIR`), with SPA routing, CSP and asset caching
- [x] Docker (distroless, non-root), systemd unit, cross-platform release builds

### Android v1.0
- [x] Onboarding in one step (currency), default categories from the shared seed file
- [x] Quick add with calculator pad, smart category suggestions (recent use, payee history), payee autocomplete, save-and-add-another
- [x] Expense, income (refunds), transfers including cross-currency
- [x] Accounts with calculated balances, credit-limit usage, reconcile (adjustments excluded from reports), archive
- [x] Home: available cash, investments, debt and net worth shown separately; month income/spend; upcoming bills; budgets; where the money went
- [x] Activity: month browsing, search, filters, day totals
- [x] Budgets: weekly/monthly/quarterly/yearly/custom, category filters, pace marker, per-day allowance, rollover, alerts
- [x] Reports: category donut with drill-down, 6/12-month trend, needs/wants/savings, top payees, income sources, per currency
- [x] Recurring rules (remind or auto), deterministic occurrence IDs, bill reminders
- [x] Lent & borrowed, Trash, categories editor
- [x] Sync client (WorkManager, debounced after edits, periodic, conflict handling, rejected-item tracking)
- [x] `.kosh` backups: daily automatic (7 daily + 4 weekly), copy to any SAF folder, optional encryption, restore merge/replace with safety backup; JSON and CSV export
- [x] App lock (biometric / device credential), recents blur, hide amounts, Material You, dark and AMOLED themes
- [x] Quick Settings tile and app shortcuts for instant entry

### Web v1.0
- [x] Sign in / register, book creation with default categories
- [x] Dashboard (available, income/spend, investments, debt, net worth, budgets, bills, top categories, recent)
- [x] Transactions table with month, type, account, category and text filters, paging, **bulk recategorise/delete**, CSV export, Trash
- [x] Add/edit transactions (arithmetic in the amount), accounts with reconcile, budgets, categories, recurring rules, reports
- [x] Settings: book name and currency, theme, JSON export/import, password change

### Tests
- [x] Server: unit, sync semantics, full API journey with exact amounts, auth hardening, backup round trips, web serving
- [x] Android: 25 JVM tests incl. cross-checks of UUIDv5 and HLC against the server
- [x] Web: money, calculator, budget periods, IDs
- [x] Manual end-to-end on an emulator: onboarding → add expense → sync up → web shows it → web/API change → syncs down

### Phase 2 (October 2026)
- [x] Server and API tests run on PostgreSQL 17 as well as SQLite (CI job with a postgres service)
- [x] Tags: UI, filters and a by-tag report on Android and web
- [x] Rules engine for categorisation (synced `rules` entity), shared test cases on Android and web, "apply to past"
- [x] CSV statement import on the web: column mapping, duplicate detection, deterministic IDs (re-import is harmless)
- [x] Budget progress endpoint (rollover, account filters, splits) used by the web; FX conversion in summaries
- [x] Splits UI on Android and web; atomic batch endpoint; home-screen widget
- [x] Lent & borrowed with linked money movements and repayments (Android and web)
- [x] Installable web app (PWA) that opens offline
- [x] Android instrumented tests (Room migration 1→2, raw-record promotion, splits, loans) and release signing via keystore.properties
- [x] Web v2: command palette (Ctrl/⌘ K), natural-language quick add ("450 swiggy dinner yesterday #work"),
      dashboard insights and spending-pace chart, uncategorised review queue, top payees, recurring items with
      transfers/end conditions and "mark paid", auto-recording of due items, exchange-rate editor, book sharing,
      signed-in devices, keyboard shortcuts, mobile tab bar, true-black theme

## Next (Phase 3)

1. Web offline writes (SQLite-WASM + the sync protocol), so the PWA can record while offline.
2. Receipt attachments (blob storage on the server, thumbnails on clients).
3. Ivy Wallet / other app backup importers; OFX and QIF import.
4. Contract test of the Android sync client against a real server in CI (emulator job).
5. Docker image build in CI and published images.
6. Android: command-style quick add and the web's dashboard insights.

## Later (Phases 3–4)

Goals, investment holdings and activity, valuations and net-worth history, debt payoff
planner, cash-flow forecast, Account Aggregator integration, statement parsing, on-device AI
categorisation and Q&A, end-to-end encrypted ledgers, tombstone compaction, iOS app, admin UI,
metrics endpoint and a generated OpenAPI spec. Details in [10](10-product-vision.md).
