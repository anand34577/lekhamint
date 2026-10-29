# Backup and Restore

Your financial history is worth protecting. LekhaMint backs up on its own, but **a backup on the same disk is not enough**: keep a copy somewhere else (another disk, a NAS, a cloud-synced folder).

## Automatic server backups

The server writes a backup every 24 hours into `<data dir>/backups` and keeps the latest 14. Change this with `LEKHAMINT_BACKUP_INTERVAL`, `LEKHAMINT_BACKUP_KEEP` and `LEKHAMINT_BACKUP_DIR` ([Configuration](Configuration)).

| Database | Backup file | Notes |
|---|---|---|
| SQLite | `lekhamint-YYYYMMDD-HHMMSS.db` | A consistent snapshot taken while the server runs; open it with any SQLite tool |
| PostgreSQL | `lekhamint-YYYYMMDD-HHMMSS.ndjson.gz` | A portable dump that restores into SQLite or PostgreSQL |

Where the data folder is:

| Installed with | Data folder |
|---|---|
| Linux installer | `/var/lib/lekhamint` |
| macOS installer | `~/Library/Application Support/LekhaMint` |
| Windows installer | `C:\ProgramData\LekhaMint\data` (or `%LOCALAPPDATA%\LekhaMint\data`) |
| Docker | the `lekhamint-data` volume, mounted at `/data` |

## Back up now

```sh
sudo -u lekhamint lekhamint backup --data-dir /var/lib/lekhamint              # SQLite snapshot
sudo -u lekhamint lekhamint backup --data-dir /var/lib/lekhamint --format json
docker compose exec lekhamint /lekhamint backup                               # Docker
```

## Restore the whole server

1. **Stop the server** (`sudo systemctl stop lekhamint`, `docker compose stop`, or `Stop-ScheduledTask LekhaMint`).
2. Restore:
   ```sh
   sudo -u lekhamint lekhamint restore --data-dir /var/lib/lekhamint --from /path/to/lekhamint-20261024-020000.db
   ```
   With Docker:
   ```sh
   docker compose run --rm -v "$PWD:/restore" lekhamint restore --from /restore/lekhamint-20261024-020000.db
   ```
   The current database is kept next to it as `lekhamint.db.pre-restore-<time>`, so a restore can be undone.
3. Start the server again. Phones and browsers pick up the restored data on their next sync. A phone that synced after the backup was taken sends everything it has again, so changes made since the backup are not lost as long as some phone still has them.

## One book at a time

- **Export:** web app → **Settings → Your data → Export (JSON)** downloads the whole book. The Android app and the server use the same format. **Transactions → CSV** exports for spreadsheets.
- **Import:** **Settings → Your data → Import (JSON)** merges an export into the open book, on this server or another one. Nothing is deleted, newer versions win, and repeating it adds nothing. A failed import changes nothing.

## On the phone

The Android app keeps encrypted `.kosh` backups daily (**More → Backup & restore**) in a folder you choose; they can be restored on a new phone with your backup password. These work with or without a server.
