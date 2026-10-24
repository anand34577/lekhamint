# 08 – Deployment

The server is **optional**. Run it if you want sync between devices, a web app, or off-device
backups. It needs no internet access.

## Option A: Docker (recommended)

```bash
cd server
docker compose up -d                  # SQLite in the kosh-data volume, port 8080
docker compose logs -f kosh
```

With PostgreSQL:

```bash
export POSTGRES_PASSWORD='change-me'
export KOSH_DB_DRIVER=postgres
export KOSH_DB_DSN="postgres://kosh:${POSTGRES_PASSWORD}@postgres:5432/kosh?sslmode=disable"
docker compose --profile postgres up -d
```

The image is distroless, runs as non-root with a read-only root filesystem, and has a built-in
`HEALTHCHECK` (`kosh healthcheck`).

### Offline / air-gapped

```bash
# On a connected machine
docker build -t kosh:1.0.0 . && docker save kosh:1.0.0 | gzip > kosh-1.0.0.tar.gz
# On the offline machine
docker load < kosh-1.0.0.tar.gz
```

To build from source offline, run `make vendor` once on a connected machine and copy the whole
folder. `go build` then uses `./vendor`.

## Option B: bare metal (Linux, macOS, FreeBSD)

```bash
make release                      # dist/kosh-<ver>-<os>-<arch>
sudo install -m 0755 dist/kosh-*-linux-amd64 /usr/local/bin/kosh
sudo useradd --system --home /var/lib/kosh --shell /usr/sbin/nologin kosh
sudo cp deploy/kosh.service /etc/systemd/system/
sudo systemctl enable --now kosh
```

On macOS, run `kosh serve` from a `launchd` plist, or simply in a terminal.

## Option C: Windows

```powershell
.\kosh-windows-amd64.exe serve --data-dir C:\ProgramData\LekhaMint
```

To run it as a Windows service, use [NSSM](https://nssm.cc) or WinSW:
`nssm install LekhaMint C:\LekhaMint\kosh.exe serve --data-dir C:\ProgramData\LekhaMint`.

## Configuration

Flags override environment variables. Run `kosh serve -h` for the full list.

| Variable | Default | Meaning |
|---|---|---|
| `KOSH_ADDR` | `:8080` | Listen address |
| `KOSH_DATA_DIR` | `./data` | SQLite database and backups |
| `KOSH_DB_DRIVER` | `sqlite` | `sqlite` or `postgres` |
| `KOSH_DB_DSN` | `<data>/kosh.db` | SQLite path or PostgreSQL URL |
| `KOSH_ALLOW_SIGNUP` | `false` | Open registration. The first user can always register. Add others with `kosh user add`. |
| `KOSH_CORS_ORIGINS` | *(none)* | Comma-separated browser origins allowed to use the API (for the web app) |
| `KOSH_SESSION_TTL` | `2160h` (90 days) | Idle time before a device is signed out |
| `KOSH_TRUST_PROXY` | `false` | Read the client IP from `X-Forwarded-For`. Enable **only** behind a proxy. |
| `KOSH_BACKUP_INTERVAL` | `24h` | Scheduled backups (`0` turns them off) |
| `KOSH_BACKUP_DIR` | `<data>/backups` | Where scheduled backups go. Put this on another disk. |
| `KOSH_BACKUP_KEEP` | `14` | How many scheduled backups to keep |
| `KOSH_MAX_IMPORT_MB` | `256` | Largest ledger import accepted |
| `KOSH_WEB_DIR` | *(built-in)* | Serve the web app from this folder instead of the copy embedded in the binary |
| `KOSH_NO_WEB` | `false` | API only, no web app |
| `KOSH_LOG_LEVEL` / `KOSH_LOG_FORMAT` | `info` / `text` | `debug`…`error`; `text` or `json` |

## HTTPS

LekhaMint speaks plain HTTP. Put a TLS-terminating reverse proxy in front of it. Caddy is the
simplest choice:

```
kosh.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Then set `KOSH_TRUST_PROXY=true`. On a LAN with no domain, Caddy's `tls internal` gives you a
local certificate.

## Operations

| Task | Command |
|---|---|
| Add a user | `KOSH_PASSWORD='…' kosh user add --email a@b.c --name Anita` |
| Reset a password | `echo '…' \| kosh user passwd --email a@b.c` (signs out all devices) |
| Backup now | `kosh backup` (SQLite snapshot) or `kosh backup --format json` (portable) |
| Restore | Stop the server, then `kosh restore --from file.db` or `--from dump.ndjson.gz` |
| Upgrade | Replace the binary or image. Migrations run on start. Take a backup first. |

## The web app

The server binary includes the web app: open `http://<server>:8080/` and sign in. The first
account can always be created there; later accounts need `KOSH_ALLOW_SIGNUP=true` or
`kosh user add`.

To rebuild it after changing `web/` (needs Node.js 20+ on a connected machine):

```bash
cd server && make web     # npm ci, tests, build, copy into internal/webui/dist
make build                # binary now carries the new web app
```

For development, run `npm run dev` in `web/`; it proxies `/api` to a server on `:8080`.

The web app is an installable PWA (manifest and a small service worker). It opens offline, but
changes need a connection; the API is never cached.

## The Android app

Build with JDK 17 and the Android SDK (API 36):

```bash
cd android
./gradlew testDebugUnitTest assembleRelease
# app/build/outputs/apk/release/app-release.apk (about 4 MB)
```

**Release signing.** Create `android/keystore.properties` (ignored by git):

```properties
storeFile=release.jks
storePassword=…
keyAlias=kosh
keyPassword=…
```

`storeFile` is relative to `android/`. In GitHub, add the repository secrets `ANDROID_KEYSTORE_BASE64`
(`base64 -w0 release.jks`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`;
CI decodes the keystore and builds a signed release APK. Pushing a `v*` tag publishes a GitHub release
with the APK and server binaries for every platform. Without either, the release build is signed with
the debug key so it still installs for testing.

Instrumented tests (Room migrations, repository writes) run on an emulator or device:
`./gradlew connectedDebugAndroidTest`.

Phones reach a home server over the LAN (`http://192.168.x.x:8080`) or HTTPS. Plain HTTP is
allowed for self-hosting; the app warns when a non-local address uses it. Private CAs (for
example Caddy `tls internal`) work if the CA certificate is installed on the phone.
