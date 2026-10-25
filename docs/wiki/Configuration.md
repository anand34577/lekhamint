# Configuration

LekhaMint works with no configuration at all. Every setting is an environment variable starting with `LEKHAMINT_`, and each also exists as a command-line flag (`--addr`, `--allow-signup`…), which wins over the variable.

**Where to put settings**

| Installed with | File | Apply changes with |
|---|---|---|
| `install.sh` on Linux | `/etc/lekhamint/lekhamint.env` | `sudo systemctl restart lekhamint` |
| `install.sh` on macOS | `~/Library/LaunchAgents/app.lekhamint.server.plist` (`EnvironmentVariables`) | `launchctl unload` then `load` that file |
| `install.ps1` on Windows | `settings.env` in `C:\ProgramData\LekhaMint` (or `%LOCALAPPDATA%\LekhaMint`) | `Stop-ScheduledTask LekhaMint; Start-ScheduledTask LekhaMint` |
| Docker Compose | `.env` next to `docker-compose.yml` (the compose file passes the common ones through) | `docker compose up -d` |

A value that cannot be understood (`LEKHAMINT_ALLOW_SIGNUP=yes please`, a port of `80800`) **stops the server with a message naming every bad setting**, rather than quietly using a default.

## Settings

| Variable | Default | What it does |
|---|---|---|
| `LEKHAMINT_ADDR` | `:8080` | Address and port to listen on. `127.0.0.1:8080` accepts connections from this machine only (use that behind a reverse proxy on the same machine). |
| `LEKHAMINT_DATA_DIR` | `./data` | Folder for the SQLite database and backups. |
| `LEKHAMINT_DB_DRIVER` | `sqlite` | `sqlite` or `postgres`. |
| `LEKHAMINT_DB_DSN` | `<data dir>/lekhamint.db` | SQLite file, or a PostgreSQL URL such as `postgres://lekhamint:secret@db:5432/lekhamint?sslmode=disable`. Required for PostgreSQL. |
| `LEKHAMINT_ALLOW_SIGNUP` | `false` | Let anyone who can reach the server create an account. The very first account can always be created. Leave this off on a server reachable from the internet and add people with [`lekhamint user add`](Command-Line). |
| `LEKHAMINT_SESSION_TTL` | `2160h` (90 days) | How long a device stays signed in without being used. |
| `LEKHAMINT_TRUST_PROXY` | `false` | Read the visitor's address from `X-Forwarded-For`. Turn on **only** behind a reverse proxy, otherwise anyone could fake their address and dodge the sign-in rate limit. |
| `LEKHAMINT_CORS_ORIGINS` | *(none)* | Comma-separated web origins allowed to call the API from a browser. Not needed for the built-in web app. |
| `LEKHAMINT_BACKUP_INTERVAL` | `24h` | Time between automatic backups (`12h`, `168h`…). `0` turns them off. |
| `LEKHAMINT_BACKUP_DIR` | `<data dir>/backups` | Where automatic backups go. Best on a different disk. |
| `LEKHAMINT_BACKUP_KEEP` | `14` | How many automatic backups to keep. |
| `LEKHAMINT_MAX_IMPORT_MB` | `256` | Largest book import accepted. |
| `LEKHAMINT_LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error`. |
| `LEKHAMINT_LOG_FORMAT` | `text` (`json` in Docker) | `text` for people, `json` for log collectors. |
| `LEKHAMINT_LOG_FILE` | *(none)* | Also write logs to this file. It is rotated at 10 MB and three old files are kept. The Windows and macOS installers set this. |
| `LEKHAMINT_WEB_DIR` | *(built in)* | Serve the web app from this folder instead of the copy inside the program. |
| `LEKHAMINT_NO_WEB` | `false` | Serve only the API, no web app. |
| `LEKHAMINT_PUBLIC_URL` | *(none)* | Address people use to reach the server, e.g. `https://money.example.com`. Needed for single sign-on. |
| `LEKHAMINT_OIDC_ISSUER` / `_CLIENT_ID` / `_CLIENT_SECRET` | *(none)* | Single sign-on through any OpenID Connect provider (Keycloak, Authentik, Authelia…). Empty turns it off. |
| `LEKHAMINT_OIDC_NAME` | `Single sign-on` | Text on the sign-in button. |
| `LEKHAMINT_OIDC_SCOPES` | `openid email profile` | Scopes to request. |
| `LEKHAMINT_OIDC_AUTO_CREATE` | `true` | Create an account the first time someone signs in through the provider. |
| `LEKHAMINT_OIDC_ONLY` | `false` | Turn off password sign-in. Make sure single sign-on works first. |
| `LEKHAMINT_OIDC_ALLOW_UNVERIFIED_EMAIL` | `false` | Accept providers that never send `email_verified`. |
| `LEKHAMINT_SMTP_HOST` / `_PORT` | *(none)* | Mail server for email reminders. Port defaults to 587, 465 with `tls`, 25 with `none`. |
| `LEKHAMINT_SMTP_USER` / `_PASSWORD` | *(none)* | Login, if the server needs one. |
| `LEKHAMINT_SMTP_FROM` | *(none)* | Sender, e.g. `LekhaMint <no-reply@example.com>`. Required when mail is on. |
| `LEKHAMINT_SMTP_SECURITY` | `starttls` | `starttls`, `tls` (implicit TLS) or `none` for a trusted SMTP relay. With `none`, a relay that offers no login is used without one. |
| `LEKHAMINT_SMTP_SKIP_VERIFY` | `false` | Accept a self-signed or mismatched mail server certificate (internal relays). |
| `LEKHAMINT_GOTIFY_URL` | *(none)* | Gotify server for push notifications. |
| `LEKHAMINT_REMINDER_HOUR` | `8` | Hour of day (server time, 0-23) for bill and budget reminders. `-1` turns them off. |
| `LEKHAMINT_AI_BASE_URL` / `_API_KEY` / `_MODEL` | *(none)* | Assistant through the server: any OpenAI-compatible API. See the assistant guide. |
| `LEKHAMINT_AI_TIMEOUT` / `_CONNECT_ORIGINS` | `2m` / *(none)* | Time limit per assistant request; extra origins the web app may call directly. |

Durations use Go syntax: `90m`, `24h`, `2160h`.

## Logs

Every request is logged with its method, path, status, size, duration and a request ID (also sent back in the `X-Request-ID` header), and unexpected errors are logged with their cause. Passwords, tokens and request bodies are never logged.

| Installed with | Read the logs |
|---|---|
| Linux (systemd) | `sudo journalctl -u lekhamint -f` |
| macOS | `~/Library/Logs/LekhaMint/lekhamint.log` |
| Windows | `logs\lekhamint.log` in the install folder |
| Docker | `docker compose logs -f` |

## Health checks

`GET /healthz` answers 200 `{"status":"ok"}` when the process is up; `GET /readyz` also checks the database. Use them in uptime monitors and load balancers. The Docker image runs its own health check.
