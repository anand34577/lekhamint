# 08 – Deployment

The step-by-step guides for people running LekhaMint live in the [wiki](https://github.com/anand34577/lekhamint/wiki), generated from [`docs/wiki`](wiki):

| Topic | Page |
|---|---|
| One-line install (Linux, macOS, Windows), install by hand | [Installation](wiki/Installation.md) |
| Docker, Compose, PostgreSQL, NAS dashboards | [Docker](wiki/Docker.md) |
| Every setting, logs, health checks | [Configuration](wiki/Configuration.md) |
| HTTPS with Caddy, Nginx, Traefik, Cloudflare Tunnel, Tailscale | [Reverse Proxy](wiki/Reverse-Proxy.md) |
| Backups and restores | [Backup and Restore](wiki/Backup-and-Restore.md) |
| Upgrades | [Upgrading](wiki/Upgrading.md) |
| User management, backups from the terminal | [Command Line](wiki/Command-Line.md) |
| Building the server, web app, Android app and image | [Building from Source](wiki/Building-from-Source.md) |

## How the deployment pieces fit

| File | Purpose |
|---|---|
| `install.sh`, `install.ps1` | One-line installers. Download a release, verify its SHA-256, install a service (systemd, launchd or a Windows scheduled task), wait for `/healthz`. Re-running upgrades in place. `--docker` / `-Docker` switches to Compose. |
| `Dockerfile` | Multi-stage build: web app (Node), static server binary (Go, no CGO), distroless non-root runtime with a `HEALTHCHECK`. Built for amd64, arm64 and arm/v7. |
| `docker-compose.yml` | SQLite in a named volume, read-only root filesystem, all capabilities dropped. |
| `deploy/docker-compose.postgres.yml`, `deploy/docker-compose.caddy.yml` | PostgreSQL, and automatic HTTPS with Caddy. |
| `deploy/lekhamint.service` | Hardened systemd unit (`ProtectSystem=strict`, `NoNewPrivileges`, state in `/var/lib/lekhamint`, settings in `/etc/lekhamint/lekhamint.env`). |
| `.github/workflows/release.yml` | On a `v*` tag: signed APK, binaries for 8 platforms, `SHA256SUMS.txt`, GitHub release, multi-arch image on GHCR. |
| `.github/workflows/pages.yml` | The online demo on GitHub Pages: the web app plus the server compiled to WebAssembly (`server/cmd/lekhamint-wasm`) on an in-memory SQLite, seeded by `server/internal/demo`. |
| `.github/workflows/wiki.yml` | Copies `docs/wiki` to the GitHub wiki. |

## Operational defaults

- The server refuses to start on any setting it cannot parse and lists every problem at once.
- Logs are structured (`text` or `json`), one line per request with a request ID echoed in `X-Request-ID`; panics are recovered and logged with a stack trace; `LEKHAMINT_LOG_FILE` adds a size-rotated file for platforms without a journal.
- Automatic backups every 24 hours (SQLite snapshot with `VACUUM INTO`, or a portable JSON dump for PostgreSQL), 14 kept.
- Sign-in is rate limited per client address; behind a proxy, `LEKHAMINT_TRUST_PROXY=true` makes that address the real visitor.
- Migrations run on start and are forward-only; an older binary refuses to open a newer database.
