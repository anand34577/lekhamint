# 04 – Data model

Every client (Android with Room, web, iOS) and the server use the **same logical schema**.
The server's canonical definition lives in code at
[`server/internal/entity/specs.go`](../server/internal/entity/specs.go). That file
drives validation, SQL generation and sync.

## Conventions

| Rule | Reason |
|---|---|
| IDs are **UUIDv7** strings, generated on the client | Records can be created offline without coordination. They sort by time, so B-tree inserts stay cheap. |
| Money is an `int64` in **minor units** | Exact arithmetic. See [02 §12](02-expense-domain.md#12-money-precision). |
| Instants are `int64` **Unix milliseconds (UTC)** | Unambiguous and compact. |
| Calendar dates are `TEXT` `YYYY-MM-DD` | A budget month or due date is a calendar day, not an instant. |
| A transaction stores both `occurred_at` and `local_date` | `local_date` is the day *as the user experienced it*. Reports group on it, and an index on it keeps them fast. |
| No hard deletes. `deleted = 1` marks a tombstone | Sync must spread deletes. Tombstones can be compacted later (see [05](05-sync-protocol.md#tombstone-compaction)). |
| Entity references are **not** database foreign keys | Sync batches can arrive in any order. Integrity is "eventual" and readers tolerate missing references. Only `ledger_id` is a real foreign key. |
| Lists of IDs (`tag_ids`, `category_ids`) are JSON arrays in a `TEXT` column | This keeps sync record-atomic. These lists are small and change rarely. |
| Balances and totals are **never stored** | They are calculated, so they cannot conflict. |

### Sync columns (on every synced entity)

| Column | Type | Set by | Meaning |
|---|---|---|---|
| `id` | TEXT PK | client | UUIDv7 |
| `ledger_id` | TEXT | client/server | Owning ledger (book) |
| `hlc` | TEXT | client | Hybrid logical clock of the last write. Decides last-writer-wins. |
| `seq` | INTEGER | **server** | Position in the ledger's change feed. Index `(ledger_id, seq)`. |
| `deleted` | INTEGER 0/1 | client | Tombstone flag |
| `server_updated_at` | INTEGER | server | Unix ms when the server accepted the write (for audit) |

Clients also keep local-only columns: `dirty` (not pushed yet) and `pushed_hlc`.

## Hierarchy

```
User ──< LedgerMember >── Ledger ──< Account
                              ├──< Category (self-referencing parent_id, 2 levels)
                              ├──< Tag
                              ├──< Transaction ──< TransactionSplit
                              ├──< Budget
                              ├──< RecurringRule
                              ├──< Debt
                              └──< ExchangeRate
```

A **ledger** ("book") is the unit of sync and sharing. One user can have several (Personal,
Business, Trip to Japan). A ledger can also be shared with others (a household).
Partitioning everything by `ledger_id` is also the scaling key; see [03](03-architecture.md#scaling).

## Synced entities

Legend: **req** = required, `?` = nullable.

### accounts
| Field | Type | Rules |
|---|---|---|
| name | string | req, 1–100 chars |
| type | enum | req: `cash` `bank` `wallet` `credit_card` `savings` `investment` `loan` `other` |
| currency | currency | req, ISO 4217 or custom code (`[A-Z0-9]{3,10}`). Must not change once transactions exist. |
| opening_balance | int | default 0, may be negative |
| credit_limit | int? | ≥ 0 |
| color / icon | string? | `#RRGGBB` / icon key |
| include_in_net_worth | bool | default true |
| archived | bool | default false |
| sort_order | int | default 0 |
| note | text? | ≤ 2000 |
| created_at | int | Unix ms |

### categories
| Field | Type | Rules |
|---|---|---|
| name | string | req, 1–100 |
| kind | enum | req: `expense` `income` |
| parent_id | ref? | Parent category. Maximum depth is 2. |
| nature | enum? | `need` `want` `savings` |
| color / icon | string? | |
| archived, sort_order, created_at | | as above |

### tags
`name` (req, 1–50), `color?`, `archived`, `created_at`.

### transactions
| Field | Type | Rules |
|---|---|---|
| type | enum | req: `expense` `income` `transfer` |
| status | enum | `cleared` (default), `pending`, `planned` |
| account_id | ref | req. Source account. |
| amount | int | req, > 0, in the account's currency. The sign comes from `type`. |
| to_account_id | ref? | **req for transfers**, must differ from `account_id` |
| to_amount | int? | **req for transfers**, > 0, in the destination currency |
| category_id | ref? | Optional. An income in an expense category is a refund. |
| payee | string? | ≤ 200 |
| note | text? | ≤ 2000 |
| occurred_at | int | req, Unix ms |
| local_date | date | req `YYYY-MM-DD` |
| tz_offset_min | int | −840…840 |
| tag_ids | id list | ≤ 50 |
| original_amount / original_currency | int? / currency? | Foreign spend reference. Both or neither. |
| recurring_rule_id | ref? | Rule that generated it |
| debt_id | ref? | Linked debt |
| is_adjustment | bool | Balance correction, excluded from reports |
| excluded_from_reports | bool | User-excluded, e.g. a reimbursed expense |
| created_at | int | |

Indexes: `(ledger_id, local_date)`, `(ledger_id, account_id)`, `(ledger_id, to_account_id)`, `(ledger_id, category_id)`, `(ledger_id, seq)`.

### transaction_splits
`transaction_id` (req), `category_id` (req), `amount` (req, > 0), `note?`, `sort_order`.
The splits of one transaction must add up to its `amount`. Clients enforce this when saving.
Reports fall back to the parent category if they do not.

### budgets
| Field | Type | Rules |
|---|---|---|
| name | string | req |
| amount | int | req, > 0 |
| currency | currency | req |
| period | enum | req: `weekly` `monthly` `quarterly` `yearly` `custom` |
| start_date | date | req. Anchors the period. |
| end_date | date? | req when `period = custom` |
| category_ids / account_ids | id list | Empty means all |
| rollover | bool | Carry the remainder into the next period |
| alert_percent | int | 1–100, default 80 |
| archived, created_at | | |

### recurring_rules
Template fields: `type`, `account_id`, `to_account_id?`, `amount`, `to_amount?`, `category_id?`, `payee?`, `note?`, `tag_ids`.
Schedule fields: `frequency` (`daily` `weekly` `monthly` `yearly`), `every` (≥ 1, e.g. every 2 weeks), `start_date`, `end_date?`, `max_occurrences?`, `mode` (`auto` | `remind`), `next_date`, `paused`.

Monthly rules anchored on day 29–31 use the last day of shorter months. `next_date` is the next
occurrence still to process; a new or rescheduled rule sets it to the first occurrence on or after
today. Clients only move it forward.

### debts
`person` (req), `direction` (`lent` | `borrowed`), `amount` (> 0), `currency`, `due_date?`, `note?`, `closed`, `created_at`.
Money movements are `transactions` with `debt_id` set; outstanding = `amount` − repayments (docs/02 §9).

### rules
`name?`, `match_field` (`payee` `note` `any`), `match_type` (`contains` `equals` `starts_with`), `pattern` (req),
`tx_type?` (`expense` | `income`), `account_id?`, `min_amount?`, `max_amount?`, `set_category_id?`, `set_payee?`,
`add_tag_ids`, `sort_order`, `enabled`, `created_at`. A rule must set a category, a payee or tags, and
`max_amount` must not be below `min_amount`. Added in schema version 2.

### exchange_rates
`base`, `quote` (currencies), `rate` (decimal string, > 0, ≤ 18 significant digits), `effective_date`, `source` (`manual` | `imported`).
To convert on date *d*, use the latest rate with `effective_date ≤ d`.

## Server-only tables

| Table | Key columns |
|---|---|
| `users` | id, email (unique, lowercased), name, password_hash (Argon2id), is_admin, disabled, created_at |
| `sessions` | id, user_id, token_hash (SHA-256, unique), device_name, created_at, last_used_at, expires_at |
| `ledgers` | id, name, base_currency, **seq** (change-feed counter), created_by, created_at |
| `ledger_members` | ledger_id, user_id, role (`owner` `editor` `viewer`) |
| `schema_migrations` | version, applied_at |

## Schema evolution

- Migrations are numbered SQL files, one set per dialect, embedded in the binary. They run automatically at startup, each inside its own transaction.
- **Adding** a field: older clients ignore unknown fields. The server keeps a field that an older client does not send, because pushes merge into the stored record rather than replacing it.
- **Never** rename or reuse a field. Add a new one and deprecate the old one.
- A push carries `schema_version`. If the app is newer than the server, the push is refused with `409 server_outdated` and nothing is written. Enforcing a minimum app version is on the roadmap.
