# Upgrading

New releases are listed on the [releases page](https://github.com/anand34577/lekhamint/releases) with what changed. Upgrades keep your data, and database changes are applied automatically when the new version starts. A backup is never a bad idea first ([Backup and Restore](Backup-and-Restore)).

| Installed with | Upgrade |
|---|---|
| Linux or macOS installer | run the same `curl -fsSL …/install.sh \| sh` command again |
| Windows installer | run the same `irm …/install.ps1 \| iex` command again |
| Docker Compose | `docker compose pull && docker compose up -d` |
| `docker run` | `docker pull ghcr.io/anand34577/lekhamint:latest`, remove the container, run it again with the same volume |
| By hand | replace the binary and restart the service |
| Android app | install the newer `LekhaMint.apk` over the old one |

To stay on a release line, pin a tag: `LEKHAMINT_VERSION=1.1` in `.env` follows 1.1.x patch releases only.

**Going back** to an older version is not supported once a newer one has changed the database. Restore the backup taken before the upgrade instead. `lekhamint version` prints the program and database versions.

## From 1.0.0

Version 1.0.0 called the server `kosh`. From 1.1.0 it is `lekhamint`:

- settings are `LEKHAMINT_*` instead of `KOSH_*`;
- the default database file is `lekhamint.db` instead of `kosh.db`; rename the file (and its `-wal`/`-shm` files) or set `LEKHAMINT_DB_DSN` to the old path;
- release files are named `lekhamint-<os>-<arch>` without the version, so links to the latest release always work.

The Android app and its `.kosh` backups are unchanged.
