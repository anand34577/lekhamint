# 03 – Architecture

## System overview

```
   ┌─────────────────────┐   ┌─────────────────────┐   ┌──────────────┐
   │  Android (Kotlin)   │   │   Web (React/TS)    │   │ iOS (future) │
   │  Room DB = full copy│   │  online via REST,   │   │  SwiftData   │
   │  works offline      │   │  offline (roadmap)  │   │              │
   └─────────┬───────────┘   └─────────┬───────────┘   └──────┬───────┘
             │    push / pull (HTTPS + JSON, HLC-stamped)     │
             └──────────────┬──────────┴──────────────────────┘
                            ▼   (optional)
              ┌──────────────────────────────┐
              │ lekhamint server (Go binary) │
              │  auth · sync · REST · reports│
              │  backups · scheduler         │
              └───────┬──────────────────────┘
                      ▼
          SQLite (default, WAL)  or  PostgreSQL
```

**Local-first** means each client's database is the primary copy *for that user*. The
server only relays and stores changes, so if it is down or missing, nothing breaks.

## Server (Go)

Why Go rather than Rust: one static binary with no CGO, thanks to a pure-Go SQLite driver.
It cross-compiles to every OS from one machine and builds quickly, and the standard library
covers HTTP, JSON, logging and crypto.

### Layers (dependencies point down only)

```
cmd/lekhamint       CLI: serve · migrate · backup · restore · user · healthcheck
internal/httpapi    HTTP transport: routing, middleware, JSON, error mapping
internal/ledger     use cases: ledgers, members, access control
internal/syncer     use cases: push/pull, LWW, server-stamped writes, import
internal/report     read models: balances, summaries, trends (SQL aggregates)
internal/backup     snapshots, NDJSON dumps, ledger export/import, scheduler
internal/auth       Argon2id passwords, session tokens, login
internal/entity     declarative entity specs: validation + column mapping
internal/store      SQL repositories (work on a pool or inside a transaction)
internal/db         connection, dialect (SQLite/PostgreSQL), migrations
internal/hlc, ids   hybrid logical clock, UUIDv7/v5
```

Key decisions:

| Decision | Why |
|---|---|
| **Declarative entity specs** (`entity/specs.go`) | One definition drives validation, SQL columns, value conversion, sync and export. Adding a field means one spec line plus one migration. A test fails if they drift apart. |
| **Typed tables per entity** (not a JSON blob store) | Reports are real SQL aggregates over indexed columns and stay fast at millions of rows. |
| **Composite key `(ledger_id, id)`** | Tenant isolation is part of the schema: a record can never be addressed outside its ledger. |
| **Standard-library HTTP router** | Go 1.22+ patterns cover the routing, so there is no framework to keep up to date. Only 3 direct dependencies: SQLite driver, pgx, x/crypto. |
| **Opaque session tokens, stored hashed** | Revocable per device ("sign out other devices"). A database leak exposes nothing usable. |
| **Writes serialise per ledger** | Lock the ledger row, then allocate `seq`. Gives a gap-free change feed on both databases with no extra log table. |

## Android (Kotlin)

One Gradle module with strict package layering (dependencies point down). It can be split
into Gradle modules later without code changes, because packages already follow module lines.

```
app.kosh                    KoshApplication (AppContainer = manual DI graph), MainActivity, Quick Settings tile
app.kosh.ui                 navigation, app lock, theme, components (amount pad, charts, rows), screens
app.kosh.domain             pure logic: reports, budgets (periods, pace, rollover), recurring engine, lookup
app.kosh.data               Repository (the only writer), sync codec, settings, Keystore secret store
app.kosh.data.db            Room entities, DAOs, database (WAL)
app.kosh.sync               REST client (OkHttp + kotlinx.serialization), push/pull engine
app.kosh.backup             .kosh writer/reader, Argon2id + chunked AES-GCM, CSV export
app.kosh.work               WorkManager: sync, daily job (recurring, auto backup, alerts, reminders)
app.kosh.core               Money, currencies, calculator, HLC, UUIDv7/v5, dates, recurrence maths
```

- **Manual dependency injection** (`AppContainer`) instead of Hilt: no annotation processing, explicit and fast to build. ViewModels are created through one small helper.
- **UDF:** ViewModels expose `StateFlow` state built with `combine` over Room `Flow`s. Composables are stateless where it matters.
- **Room is the single source of truth.** Sync writes into Room, and every screen updates by itself.
- Every write goes through `Repository`, which stamps `hlc = clock.now()` and `dirty = 1` in the same transaction.
- **Unknown fields survive.** Each row keeps an `extras` JSON of fields a newer client added, and entities this version has no screen for live in `raw_records`. Nothing a newer device writes is lost by an older one.
- **Reference data** (accounts, balances, categories) is one shared, always-fresh `StateFlow` (`Lookup`), so screens never re-query it.
- **WorkManager jobs:** sync (5 s after edits, every 15 min with network), daily job (auto recurring entries, bill reminders, budget alerts, automatic backup).
- **Search** uses indexed `LIKE` over payee and note today; FTS is a drop-in later if needed.
- **Testing:** JVM unit tests for all core and domain logic, the sync codec and backup encryption; instrumented and screenshot tests are on the roadmap.

## Web (React + TypeScript)

- Vite, React 18 and TanStack Query (cache, refetch on focus). No UI library: one CSS file with the same design tokens as Android, light and dark.
- Online client: talks to `/records`, `/transactions` and `/reports`. Server-stamped writes enter the same change feed, so phones receive them on their next pull.
- Built into the server binary (`internal/webui`, refreshed with `make web`), so a self-hoster runs one process and there is no CORS. `LEKHAMINT_WEB_DIR` serves another build; `LEKHAMINT_NO_WEB` disables it.
- Roadmap: local-first web with SQLite-WASM on OPFS using the same push/pull protocol, as an installable PWA.

## Scaling

Data grows mainly in `transactions`, at roughly 2–10k rows per user per year.

| Scale | Setup |
|---|---|
| 1–50 users (family, small team) | SQLite on one small VPS or a Raspberry Pi. WAL mode handles hundreds of writes per second. |
| 50–10k users | PostgreSQL, one server process (stateless), connection pool |
| 10k+ users | Several stateless `lekhamint` instances behind a load balancer, PostgreSQL with read replicas for reports, and **hash partitioning on `ledger_id`** (every table and index already leads with it). Rate limiting moves to the proxy or Redis. |

Why it scales:

- Every query is scoped to one ledger and served by a `(ledger_id, …)` index. Nothing ever scans across tenants.
- Pagination is **keyset**, not `OFFSET`, so page 1,000 costs the same as page 1.
- Sync pulls are incremental (`seq > cursor`), so an up-to-date client costs one small index-range query.
- Exports, dumps and imports **stream**, so memory stays flat whatever the ledger size.
- Balances are calculated on demand from indexed rows. If one ever gets too slow, a per-account per-month aggregate table can be added. It is a cache that can always be rebuilt, never the source of truth.

## Cross-cutting concerns

| Concern | Approach |
|---|---|
| Validation | On the client for UX, **and again on the server** (`entity` specs) for integrity |
| Errors | One JSON error shape `{error: {code, message, fields}}`. Internal details are logged, never returned. |
| Logging | `log/slog` structured logs (text or JSON) with a request ID in every line |
| Security | Argon2id, hashed tokens, per-IP login rate limits, CORS allow-list, security headers, body size limits, a non-root distroless container, hardened systemd unit |
| Config | Environment variables or flags (12-factor). Every setting has a safe default. |
| Compatibility | `schema_version` in sync. Unknown fields are ignored. Merge-on-write protects fields from older clients. |
