# 05 – Sync protocol

## Goals

1. Every client works fully offline, for as long as needed.
2. Any number of devices and users per ledger.
3. All devices end up with identical data (convergence), whatever order changes arrive in.
4. Simple enough to put in a protocol document and build again on iOS or web.

## Model: record-level last-writer-wins with hybrid logical clocks

Each synced record carries an **HLC** (hybrid logical clock) timestamp of its last write. When
two writes to the same record conflict, the **higher HLC wins**. The comparison is
deterministic, so every device and the server pick the same winner.

Record-level LWW fits personal finance well:

- **Balances and totals are calculated**, not stored, so there are no counters to merge.
- A conflict needs two people to edit **the same transaction** offline at the same time. That is rare, and the winning version is always a complete, valid record.
- Field-level merging (CRDTs) would add a lot of complexity for very little benefit. It stays an option for the future (`notes` merging, for example).

### HLC format

```
<wall-ms: 13 digits, zero-padded>-<counter: 4 hex digits>-<node-id: 16 hex chars>
1729771234567-0000-9f2c4a1b7e3d5c60
```

- Fixed width, so a **plain string comparison** is a correct ordering, in SQL too.
- `node-id` is a random ID per device install. It breaks ties.
- Client rules (standard HLC):
  - On a local write: `wall = max(now, last.wall)`. If `wall == last.wall`, increment the counter. Otherwise reset it to 0.
  - On receiving a remote HLC: `last = max(last, remote)`, so the local clock never falls behind what it has seen.
- **The server rejects HLCs more than 24 h ahead of its own clock** (`hlc_in_future`). Otherwise a device with a badly wrong clock would win every conflict forever.

## Change feed

Each ledger has a counter, `ledgers.seq`. Every accepted write does this inside one database transaction:

1. `UPDATE ledgers SET seq = seq + 1 WHERE id = ? RETURNING seq`. This also row-locks the ledger, so on PostgreSQL the commit order matches the `seq` order.
2. Writes the record with that `seq`.

Because of the row lock, a reader never sees seq 11 committed while seq 10 is still in
flight. A plain "everything after cursor *N*" pull is therefore gap-free.

## Push

`POST /api/v1/ledgers/{ledger}/sync/push`

```json
{
  "schema_version": 2,
  "device_id": "9f2c4a1b7e3d5c60",
  "changes": [
    { "entity": "accounts", "id": "0192…", "hlc": "1729771234567-0000-9f2c…", "deleted": false,
      "data": { "name": "HDFC Savings", "type": "bank", "currency": "INR", "opening_balance": 1250000 } },
    { "entity": "transactions", "id": "0192…", "hlc": "…", "deleted": true, "data": {} }
  ]
}
```

For each change the server:

1. Validates the entity name, the UUID, the HLC format, the clock skew and (for non-deleted records) the fields, using the entity spec.
2. Loads the stored record.
   - If `stored.hlc >= incoming.hlc`, it is a **conflict**: the server keeps its version and returns it in `conflicts`. The client overwrites its local copy with it.
   - Otherwise it **merges** the incoming fields over the stored record. Fields the client did not send are kept, which protects against older clients. It then assigns a new `seq` and saves.
3. A delete stores a tombstone (`deleted = 1`). The other fields are kept for undo or restore.

The whole batch is one database transaction, up to 1,000 changes. Response:

```json
{ "accepted": ["0192…"], "conflicts": [ { "entity": "…", "id": "…", "hlc": "…", "deleted": false, "seq": 41, "data": { } } ],
  "rejected": [ { "id": "…", "error": "validation", "message": "amount: must be > 0" } ],
  "cursor": 42 }
```

`rejected` records are bad input. The client marks them for the user to fix and does not retry them in a loop.

## Pull

`GET /api/v1/ledgers/{ledger}/sync/pull?since=<cursor>&limit=500`

The server returns every record with `seq > since`, ordered by `seq` and including tombstones,
plus `next_cursor` and `has_more`. Records come from several tables. Each table is queried with
`seq > since ORDER BY seq LIMIT n`, the results are merged, and the merged list is cut at *n*.
This is correct because `seq` is global per ledger.

The client applies each pulled record **only if** `remote.hlc > local.hlc`, or the local copy is
not dirty. It then saves `next_cursor`.

**Live updates.** `wait=N` (seconds, at most 25) makes the server hold the answer while there is
nothing after `since`, and answer as soon as a push, REST write or import changes the book. While
the Android app is on screen it keeps one such request open (`limit=1`) and runs a full sync when it
returns with a change. The web app does the same while its tab is visible, starting from the book's
`seq` in `GET /ledgers`, and refreshes the page's data when a change arrives. Only writes made by the same server process wake it; with several processes a
waiting pull still answers at its deadline. Servers that ignore `wait` answer at once, and the app
then polls every 20 seconds.

## Client loop (Android: WorkManager; web: on focus or interval)

```
sync():
  1. push all dirty rows (batches of 500), oldest HLC first
       accepted  → clear dirty if row.hlc still equals the pushed hlc
       conflicts → overwrite local row with the server's version
  2. pull since last_cursor until has_more = false
  3. save cursor; update local HLC with the max remote HLC seen
```

Triggers: after a local write (debounced by 5 s), when the app comes to the foreground, a
periodic job every 15 min on unmetered networks, and pull-to-refresh. Sync is **never**
required for the app to work.

## First sync of an existing local ledger

A ledger created offline already has a UUID. Joining the server is:

1. `POST /api/v1/ledgers {id, name, base_currency}`
2. Push everything as dirty.

If the ledger already exists on the server and the caller is a member, the call is idempotent.

## Recurring transactions without duplicates

Generated transactions use `id = UUIDv5(namespace, rule_id + ":" + occurrence_date)`. If two
devices generate the same occurrence, they produce the same ID, and LWW merges them.

## Tombstone compaction

Tombstones older than 90 days *and* older than every member device's last pull cursor can be
deleted for good. A device that has been offline longer than that does a **full re-sync**
(`since=0`) and keeps its unpushed changes. This is roadmap work. Tombstones are cheap, so v1
keeps them.

## Security

- Only ledger members can sync. `viewer` members can pull but not push.
- Every query is scoped by `ledger_id` taken from the URL after the membership check. It never comes from the request body.
