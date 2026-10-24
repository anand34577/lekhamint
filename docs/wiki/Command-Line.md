# Command Line

The server program is `lekhamint` (`lekhamint.exe` on Windows, `/lekhamint` inside the Docker image). Every command reads the same settings as the server ([Configuration](Configuration)), so point it at the same data folder or database, and run it as the same user.

```
lekhamint serve       run the server (the default)
lekhamint demo        a throwaway server with a year of sample data; nothing is saved
lekhamint user add    create an account
lekhamint user passwd set a new password (signs out that person's devices)
lekhamint backup      write a backup now
lekhamint restore     restore a backup (stop the server first)
lekhamint migrate     apply database upgrades and exit
lekhamint healthcheck exit 0 if the local server is healthy
lekhamint version     print the program and database versions
```

`lekhamint <command> -h` lists a command's options.

## Examples

```sh
# Linux install: run as the service user, against the service's data folder
sudo -u lekhamint lekhamint user add --data-dir /var/lib/lekhamint --email asha@example.com --name Asha

# The password is asked for (hidden), or read from standard input, or from LEKHAMINT_PASSWORD
echo 'a-long-password' | lekhamint user add --email asha@example.com --name Asha

# Docker
docker compose exec -it lekhamint /lekhamint user add --email asha@example.com --name Asha
docker compose exec -it lekhamint /lekhamint user passwd --email asha@example.com

# Backups
lekhamint backup                          # SQLite snapshot into <data dir>/backups
lekhamint backup --format json --out ~/lekhamint.ndjson.gz
lekhamint restore --from ~/lekhamint.ndjson.gz

# Try it without touching your data
lekhamint demo --addr :9000
```

## API

Everything the apps do goes through a JSON HTTP API under `/api/v1`, documented in [docs/07-api.md](https://github.com/anand34577/lekhamint/blob/main/docs/07-api.md). Sign in with `POST /api/v1/auth/login` and send the token as `Authorization: Bearer …`.
