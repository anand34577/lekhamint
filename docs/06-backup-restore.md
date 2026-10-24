# 06 – Backup & restore

The goal is that **a user never loses data, whether or not they run a server**. LekhaMint follows
the 3-2-1 rule: 3 copies, on 2 kinds of storage, with 1 off-device.

## Layers

| Layer | Who | What | When |
|---|---|---|---|
| 1. Device database | client | Room/SQLite with WAL mode | Always |
| 2. Automatic local backup | client | Encrypted `.kosh` file in app storage. Keeps the last 7 daily and 4 weekly copies. | Daily (WorkManager) and before every app upgrade or restore |
| 3. User-chosen location | client | Same `.kosh` file written to a folder the user picks (Google Drive, OneDrive, SD card, Nextcloud, through Android's Storage Access Framework) | Daily or weekly, user's choice |
| 4. Sync server | server | Full ledger copy. A new phone can rebuild from it. | Continuous |
| 5. Server snapshots | server | SQLite `VACUUM INTO` snapshots, or `pg_dump` for PostgreSQL | Scheduled (`KOSH_BACKUP_INTERVAL`), kept by `KOSH_BACKUP_KEEP` |
| 6. Portable export | both | JSON snapshot of one ledger (API, CLI or app) | On demand |
| 7. Human-readable export | client | CSV of transactions, for spreadsheets or a move to another app | On demand |

Android's Auto Backup (`allowBackup`) is turned **off** for the database. It restores stale
copies silently and conflicts with sync. LekhaMint's own backups replace it.

## The `.kosh` backup file (client and server JSON export)

A ZIP file containing:

```
manifest.json      { "format": "kosh-backup", "format_version": 1, "schema_version": 1,
                     "app": "kosh-android/1.0.0", "created_at": 1729771234567,
                     "ledgers": [{ "id": "…", "name": "Personal", "records": 18234 }],
                     "sha256": { "ledger-<id>.json": "…" }, "encryption": null | {…} }
ledger-<id>.json   the same structure as GET /api/v1/ledgers/{id}/export
```

`ledger-<id>.json`:

```json
{ "format": "kosh-ledger", "format_version": 1, "schema_version": 1, "exported_at": 1729771234567,
  "ledger": { "id": "…", "name": "Personal", "base_currency": "INR" },
  "entities": { "accounts": [ {"id": "…", "hlc": "…", "deleted": false, "data": {…}} ], "categories": [ … ], … } }
```

Each record has the same shape as a sync record. **Restoring is a sync push**, so the same
validation and merge rules apply. A restore can never write data the app could not have written itself.

### Encryption (optional, recommended for cloud destinations)

- The key is derived from a user passphrase with **Argon2id** (m = 64 MiB, t = 3, p = 1, 16-byte random salt).
- The entry is split into 1 MiB chunks, each sealed with **AES-256-GCM** (`aes-256-gcm-chunked`). Nonce = an 8-byte random prefix + a 4-byte chunk counter; the associated data marks the final chunk. Reordered, dropped or truncated chunks fail to decrypt, and memory use stays flat for any backup size. On disk each chunk is `uint32 length ‖ ciphertext+tag`.
- The manifest stores the KDF parameters, salt and nonce prefix:
  `"encryption": {"kdf": "argon2id", "memory_kib": 65536, "iterations": 3, "parallelism": 1, "salt": "<b64>", "cipher": "aes-256-gcm-chunked", "chunk_size": 1048576, "nonce_prefix": {"ledger-<id>.json": "<b64>"}}`
- The SHA-256 in the manifest is of the **plaintext**, so a restore verifies both the password and the content before touching any data.
- If the passphrase is lost, the backup cannot be recovered. The UI says this plainly before the user turns encryption on.

## Restore modes

| Mode | Behaviour | Use case |
|---|---|---|
| **Merge** (default) | LWW by HLC. Records missing on the target are added, and newer records on the target are kept. Nothing is removed. | Combine devices, recover from a lost phone |
| **Replace** | Wipes the local ledger, then imports. The app makes an automatic backup first. | Roll back to a point in time |

To recover a single deleted item, use **Trash**, not a restore. Tombstones keep their fields, and
"Restore from Trash" is an ordinary new write with a fresh HLC, so it syncs everywhere.

Restore safety checks:

1. Verify every entry's SHA-256 checksum before touching any data.
2. If `schema_version` is newer than the app understands, refuse and ask the user to update the app. If it is older, migrate.
3. Write everything in a single database transaction. A failed restore changes nothing.
4. Before a **replace**, always make a backup of the current state.

## Server backups

### SQLite (default)

- `kosh backup --out /backups/kosh-2026-10-24.db` makes a consistent online snapshot with `VACUUM INTO`. It is safe while the server is running, and the result is compacted.
- The built-in scheduler runs the same command every `KOSH_BACKUP_INTERVAL` (for example `24h`) into `KOSH_BACKUP_DIR` and keeps the newest `KOSH_BACKUP_KEEP` files.
- `kosh restore --from file.db` checks the snapshot (`PRAGMA integrity_check`, schema version), moves the current database aside as `kosh.db.pre-restore-<ts>`, and installs the snapshot. The server must be stopped first.
- For continuous off-site replication, run [Litestream](https://litestream.io) next to the server. This is optional.

### PostgreSQL

- Use the standard tools: `pg_dump -Fc` on a schedule, plus WAL archiving or PITR if needed.
- `kosh backup --format json --out kosh-dump.ndjson.gz` writes a **portable, streaming** dump of the whole server: users, ledgers, members and every record, with their `seq` values. Memory use stays flat however big the database is. The dump restores into an **empty** database of either type, which is also how you move between SQLite and PostgreSQL.

### Moving between databases

```bash
kosh backup --format json --out kosh-dump.ndjson.gz                      # from SQLite
KOSH_DB_DRIVER=postgres KOSH_DB_DSN=… kosh restore --format json --from kosh-dump.ndjson.gz
```

Because the dump keeps each record's `seq`, clients that are already synced keep their cursors and do not need to re-download anything.

## Test your backups

A backup you have never restored is only a hope. The test suite restores into a fresh database
and compares record counts and checksums. Operators should run a restore drill every quarter.
