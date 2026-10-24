# Docker

Images are published for every release at **`ghcr.io/anand34577/lekhamint`** for `linux/amd64`, `linux/arm64` and `linux/arm/v7` (Raspberry Pi). Tags: `latest`, the exact version (`1.1.0`) and the minor version (`1.1`, which follows patch releases).

The image is small (distroless, no shell), runs as a non-root user, works with a read-only root filesystem, and has a built-in health check.

## Quick start

```sh
docker run -d --name lekhamint --restart unless-stopped \
  -p 8080:8080 -v lekhamint-data:/data \
  ghcr.io/anand34577/lekhamint:latest
```

Open `http://<this machine>:8080` and create your account.

No Docker yet? On Linux: `curl -fsSL https://get.docker.com | sh`. On Windows and macOS install [Docker Desktop](https://docs.docker.com/desktop/). Or let the installer do everything: `curl -fsSL https://raw.githubusercontent.com/anand34577/lekhamint/main/install.sh | sh -s -- --docker`.

## Docker Compose (recommended)

```sh
mkdir lekhamint && cd lekhamint
curl -fsSLO https://raw.githubusercontent.com/anand34577/lekhamint/main/docker-compose.yml
curl -fsSL  https://raw.githubusercontent.com/anand34577/lekhamint/main/.env.example -o .env
docker compose up -d
```

Edit `.env` to change the port, time zone, sign-ups or backups, then `docker compose up -d` again. All settings are on the [Configuration](Configuration) page.

### With PostgreSQL

SQLite is right for nearly everyone, including families with years of history. Use PostgreSQL if you already run it or host many users.

```sh
curl -fsSL https://raw.githubusercontent.com/anand34577/lekhamint/main/deploy/docker-compose.postgres.yml -o docker-compose.yml
# set POSTGRES_PASSWORD in .env to a long random value
docker compose --env-file .env up -d
```

### With automatic HTTPS (Caddy)

If the machine has a public address and a domain pointing at it:

```sh
curl -fsSL https://raw.githubusercontent.com/anand34577/lekhamint/main/deploy/docker-compose.caddy.yml -o docker-compose.yml
# set LEKHAMINT_DOMAIN=money.example.com in .env, open ports 80 and 443
docker compose --env-file .env up -d
```

Caddy gets and renews the certificate on its own. See [Reverse Proxy](Reverse-Proxy) for Nginx, Traefik, Cloudflare Tunnel and Tailscale.

## NAS and home-server dashboards

Use the image `ghcr.io/anand34577/lekhamint:latest` with:

| Setting | Value |
|---|---|
| Port | container `8080` → any free host port |
| Volume | a folder or volume → `/data` (database and backups) |
| Environment (optional) | `TZ=Asia/Kolkata`, `LEKHAMINT_TRUST_PROXY=true` behind a proxy |
| User | the image runs as uid `65532`; a host folder mounted at `/data` must be writable by it (`sudo chown 65532:65532 /path`) |

This works the same in Portainer, Synology Container Manager, Unraid, CasaOS, TrueNAS SCALE and similar tools.

## Everyday commands

```sh
docker compose logs -f                      # follow the logs (JSON lines)
docker compose pull && docker compose up -d # upgrade
docker compose exec lekhamint /lekhamint version
docker compose exec -T lekhamint /lekhamint user add --email asha@example.com --name Asha <<< 'a-long-password'
docker compose exec lekhamint /lekhamint backup --format json   # one-off backup into /data/backups
```

Backups are written to `/data/backups` every 24 hours by default. Copy them off the machine too: mount a second disk at `/data/backups`, as shown in the comments in `docker-compose.yml`. See [Backup and Restore](Backup-and-Restore).

## Building the image yourself

From a clone of the repository: `docker build -t lekhamint .` (the build compiles the web app and the server; nothing is downloaded at runtime). For an offline machine: `docker save lekhamint | gzip > lekhamint.tar.gz`, copy it over, and `docker load < lekhamint.tar.gz`.
