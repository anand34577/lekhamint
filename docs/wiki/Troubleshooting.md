# Troubleshooting

Start with the logs ([where they are](Configuration#logs)) and the health check: `curl http://localhost:8080/healthz` on the server should print `{"status":"ok"}`.

## The server does not start

| Message | Fix |
|---|---|
| `invalid configuration: …` | A setting has a value the server cannot understand; the message names it. Fix it in your settings file ([Configuration](Configuration)). |
| `listen tcp :8080: bind: address already in use` | Another program uses the port. Pick another one: `LEKHAMINT_ADDR=:8090`, or re-run the installer with `--port 8090` / `-Port 8090`. |
| `cannot open the database` / `permission denied` | The data folder is not writable by the server. Linux: `sudo chown -R lekhamint:lekhamint /var/lib/lekhamint`. Docker with a host folder: `sudo chown -R 65532:65532 /path/to/data`. |
| `database schema version N is newer than this binary supports` | You started an older version on a database a newer version already upgraded. Install the newer version again. |
| `connect to postgres: …` | Check `LEKHAMINT_DB_DSN`, that PostgreSQL is running, and that the user and database exist. |

## I can open it on the server but not from my phone or another computer

1. Use the server's **network address**, not `localhost`: `http://192.168.1.20:8080`. The installers print it.
2. Check the firewall. Windows: run the installer as Administrator once (it adds the rule) or allow LekhaMint on *private* networks. Ubuntu with ufw: `sudo ufw allow 8080/tcp`.
3. Make sure `LEKHAMINT_ADDR` is not `127.0.0.1:…`, which only accepts local connections.
4. Phone and server must be on the same network (or use [Tailscale or a tunnel](Reverse-Proxy)).

## The app says it can't reach the server

- Open the same address in the phone's browser; if `…/healthz` does not show `{"status":"ok"}`, it is a network problem (see above).
- A public address needs HTTPS ([Reverse Proxy](Reverse-Proxy)). With a private certificate (Caddy `tls internal`), install the CA certificate on the phone.
- Behind a proxy, set `LEKHAMINT_TRUST_PROXY=true`.

## "Too many attempts; try again in a minute"

Sign-in is limited to 10 attempts per minute from one address, to stop password guessing. Wait a minute. If everyone behind your proxy shares one limit, set `LEKHAMINT_TRUST_PROXY=true`.

## I forgot my password

On the server: `lekhamint user passwd --email you@example.com` (Docker: `docker compose exec -it lekhamint /lekhamint user passwd --email you@example.com`). This also signs out all your devices.

## Sync shows a problem on the phone

**More → Sync** shows the error. Common causes: the server address changed (edit it), the session expired (sign in again), or the device's clock is far off (turn on automatic date and time). Nothing is lost: changes stay queued on the phone until sync works again.

## Still stuck?

[Open an issue](https://github.com/anand34577/lekhamint/issues/new/choose) with your version (`lekhamint version`), how you installed it, and the relevant log lines (remove anything private).
