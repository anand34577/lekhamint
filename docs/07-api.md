# 07 – API reference (v1)

Base path is `/api/v1`. The API speaks JSON only. Authenticated endpoints need
`Authorization: Bearer kosh_…`. Amounts are integers in minor units.

## Errors

```json
{ "error": { "code": "validation", "message": "amount: must be >= 1", "fields": { "amount": "must be >= 1" } } }
```

| HTTP | `code` |
|---|---|
| 400 | `bad_request` |
| 401 | `unauthenticated`, `invalid_credentials` |
| 403 | `forbidden`, `signup_closed` |
| 404 | `not_found`, `unknown_entity`, `user_not_found` (non-members also get 404 for a ledger) |
| 409 | `conflict`, `last_owner`, `server_outdated` |
| 422 | `validation`, `invalid_id` |
| 429 | `rate_limited` (login and register: 10 per minute per IP) |

## Health & metadata (public)

| Method & path | Notes |
|---|---|
| `GET /healthz` | Liveness |
| `GET /readyz` | Database reachable |
| `GET /api/v1/meta` | Version, schema version, database type, `signup_allowed`, entity list, limits, server time |

## Auth

| Method & path | Body → response |
|---|---|
| `POST /auth/register` | `{email, password, name?, device_name?}` → `201 {user, token}`. The first user can always register and becomes admin. |
| `POST /auth/login` | `{email, password, device_name?, otp?}` → `{user, token}`. With two-step sign-in on, a right password without `otp` gives 401 `otp_required`; a wrong code 401 `invalid_otp`. After 5 wrong codes the account's code check pauses for 15 minutes: 429 `otp_locked` with `Retry-After`. |
| `POST /auth/logout` | Revokes the current token → `204` |
| `GET /auth/me` | `{user, session}` |
| `GET /auth/sessions` | Devices signed in: `{sessions:[{id, device_name, last_used_at, current}]}` |
| `DELETE /auth/sessions/{id}` | Sign out that device |
| `POST /auth/password` | `{current_password, new_password}`. Also signs out all other devices. |
| `DELETE /auth/me` | `{password}` → `{deleted_ledgers}`. Permanently deletes the account and every ledger it alone owns; removes it from ledgers with another owner. Wrong password: 401 `invalid_credentials` (the session stays valid). |

## Per-person settings

A signed-in person's own app choices (theme, accent, chosen book, hidden tips, assistant options), kept on the server so they are the same in every browser. Never shared with other members of a book; removed with the account; included in full-server dumps.

| Method & path | Body → response |
|---|---|
| `GET /settings` | `{settings: {key: value, …}}` (values are any JSON) |
| `PUT /settings/{key}` | `{value}` → `{key, value}`. `null` removes the setting (`204`). Keys: up to 64 of `a-z 0-9 . _ -`; values up to 16 KB; at most 200 per person (409 `too_many_settings`). |
| `DELETE /settings/{key}` | `204` |

The web app uses keys such as `theme`, `accent`, `ledger`, `hide_amounts`, `nav_rail`, `tx_view`, `assistant`, `fy_start.{ledger}`, `last_account.{ledger}`, `subscriptions_hidden.{ledger}` and `getting_started_hidden.{ledger}`. Android phones share `android.appearance` (`{theme, palette, amoled, dynamic_color, updated_at}`): a change made on one phone is sent on its next sync, and the newest `updated_at` wins on the others.

## Ledgers

| Method & path | Notes |
|---|---|
| `GET /ledgers` | The caller's ledgers, with `role` |
| `POST /ledgers` | `{id?, name, base_currency, seed_default_categories?}`. Returns `201` if created, `200` if it already existed (idempotent for members). |
| `GET /ledgers/{lid}` | |
| `PATCH /ledgers/{lid}` | `{name?, base_currency?}` (owner) |
| `DELETE /ledgers/{lid}?confirm={lid}` | Permanent (owner). Needs the confirm parameter. |
| `GET /ledgers/{lid}/members` | |
| `POST /ledgers/{lid}/members` | `{email, role: owner\|editor\|viewer}` (owner) |
| `DELETE /ledgers/{lid}/members/{uid}` | Owner removes a member, or a member leaves. The last owner cannot leave. |

## Sync ([protocol](05-sync-protocol.md))

| Method & path | Notes |
|---|---|
| `POST /ledgers/{lid}/sync/push` | `{schema_version, device_id, changes:[{entity, id, hlc, deleted, data}]}`, up to 1,000 changes → `{accepted, conflicts, rejected, cursor}` (editor+) |
| `GET /ledgers/{lid}/sync/pull?since=&limit=` | `{changes, next_cursor, has_more, server_time}`, up to 2,000 per page |

Entity names: `accounts`, `categories`, `tags`, `debts`, `recurring_rules`, `transactions`, `transaction_splits`, `budgets`, `exchange_rates`.

## Records (REST view for online clients)

The server stamps these writes with its own HLC. They go into the same change feed as pushes,
so phones pick them up on their next pull.

| Method & path | Notes |
|---|---|
| `GET /ledgers/{lid}/records/{entity}?after=&limit=&include_deleted=` | Keyset-paged by id. `next_after` is set when more exist. |
| `POST /ledgers/{lid}/records/{entity}` | `{id?, data}` → `201 {record}`. `created_at` is filled in if missing. |
| `GET /ledgers/{lid}/records/{entity}/{id}` | Includes tombstones |
| `PATCH /ledgers/{lid}/records/{entity}/{id}` | `{data}`. Partial update, merged over the stored record. |
| `DELETE /ledgers/{lid}/records/{entity}/{id}` | Moves to Trash (tombstone) |
| `POST /ledgers/{lid}/records/{entity}/{id}/restore` | Restore from Trash |

### Transactions list

`GET /ledgers/{lid}/transactions` – newest first, keyset-paginated.

| Query | Meaning |
|---|---|
| `from`, `to` | `local_date` range (inclusive) |
| `account_id` | Source **or** destination account |
| `category_id` | The category and its sub-categories. `none` lists income and expenses that still need a category (not split, not adjustments, not debt movements). |
| `tag_id`, `type`, `status` | Exact match |
| `debt_id` | Loans and repayments of one debt; `any` lists every debt movement |
| `min_amount`, `max_amount` | Amount range in minor units, inclusive |
| `q` | Case-insensitive search in payee and note |
| `deleted=true` | List the Trash |
| `limit` (≤ 500), `cursor` | Paging. Pass back `next_cursor`. |

## Reports

| Method & path | Notes |
|---|---|
| `GET /ledgers/{lid}/reports/balances?as_of=` | Each account's balance (opening + flows). Planned transactions are excluded. |
| `GET /ledgers/{lid}/reports/summary?from=&to=` | Per currency: `income`, `expense`, `net`, `expense_by_category`, `income_by_category`. Refunds net against their category. Splits are used. Transfers, adjustments, planned rows, debt movements and excluded rows are left out. |
| `GET /ledgers/{lid}/reports/summary?from=&to=&convert_to=INR` | Adds `converted`: totals in that currency using the newest `exchange_rates` on or before `to` (inverse rates allowed), plus `missing_rates` |
| `GET /ledgers/{lid}/reports/trend?from=&to=&interval=day\|month` | Income and expense per period and currency |
| `GET /ledgers/{lid}/reports/budgets?date=` | Progress of every non-archived budget on `date` (default today): window, `carry` (rollover, up to 24 periods), `limit`, `spent`, `days_left`, `per_day_left`, `pace_fraction`. Category filters include sub-categories; account filters, refunds and splits are applied. |
| `GET /ledgers/{lid}/reports/tags?from=&to=` | Per tag and currency: net `expense`, `income`, `count`. A transaction with two tags counts under both. |
| `GET /ledgers/{lid}/reports/payees?from=&to=&limit=` | Top payees per currency (case-insensitive, most used spelling), net `expense`, `income`, `count`, `last_date`. `limit` 1–500, default 20. |

## Batch writes

`POST /ledgers/{lid}/batch` with `{ops: [{op, entity, id, data}]}`, at most 500 ops (`meta.limits.batch_ops`),
applied atomically. `op` is `create`, `create_if_missing` (skips existing records, including ones in Trash: used for imports
and recurring occurrences), `update`, `delete` or `restore`. The response has a `status` per op:
`created`, `updated`, `deleted`, `restored` or `skipped`. Viewers get 403.

## Backup

| Method & path | Notes |
|---|---|
| `GET /ledgers/{lid}/export` | Streams the `kosh-ledger` JSON ([format](06-backup-restore.md)) |
| `POST /ledgers/{lid}/import` | Body is a `kosh-ledger` document. Merges by HLC in one transaction → `{accepted, skipped_newer_exists, rejected, cursor}`. If anything fails, nothing is written. |

## Example: first sync from a phone

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login \
  -d '{"email":"me@example.com","password":"…","device_name":"Pixel 9"}' | jq -r .token)

curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/ledgers \
  -d '{"id":"0192f0c4-0000-7000-8000-000000000001","name":"Personal","base_currency":"INR"}'

curl -s -H "Authorization: Bearer $TOKEN" \
  localhost:8080/api/v1/ledgers/0192f0c4-0000-7000-8000-000000000001/sync/push -d '{
  "schema_version": 1, "device_id": "9f2c4a1b7e3d5c60",
  "changes": [{ "entity": "accounts", "id": "0192f0c4-0000-7000-8000-0000000000a1",
                "hlc": "1792832085465-0000-9f2c4a1b7e3d5c60",
                "data": { "name": "Cash", "type": "cash", "currency": "INR" } }] }'
```
