# Reverse Proxy and HTTPS

On your home network you can use LekhaMint over plain `http://192.168.x.x:8080`. To use it **away from home**, put it behind HTTPS. Pick the option that matches your setup, and in every case set:

```
LEKHAMINT_TRUST_PROXY=true
```

If the proxy runs on the same machine, also set `LEKHAMINT_ADDR=127.0.0.1:8080` so the plain-HTTP port is not reachable from outside.

## No open ports: Tailscale or Cloudflare Tunnel

These work even when your internet provider does not let you open ports (CGNAT).

**Tailscale** (private, only your devices): install Tailscale on the server and your phone, then open `http://<server-name>:8080`. For HTTPS inside your tailnet run `tailscale serve --bg 8080` and use the `https://<server-name>.<tailnet>.ts.net` address it prints.

**Cloudflare Tunnel** (a public address on your domain): in the Cloudflare dashboard go to *Zero Trust → Networks → Tunnels*, create a tunnel, run the `cloudflared` command it gives you on the server, and add a public hostname `money.example.com` → `http://localhost:8080`. Consider adding a Cloudflare Access policy so only your family's email addresses can reach it.

## Caddy (automatic certificates)

Point a domain at your server and open ports 80 and 443. `/etc/caddy/Caddyfile`:

```
money.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

On a LAN without a public domain, `tls internal` makes Caddy issue a local certificate; install Caddy's root certificate on your phone so the app trusts it.

With Docker, use [`deploy/docker-compose.caddy.yml`](https://github.com/anand34577/lekhamint/blob/main/deploy/docker-compose.caddy.yml) (see [Docker](Docker)).

## Nginx

```nginx
server {
    listen 443 ssl http2;
    server_name money.example.com;
    ssl_certificate     /etc/letsencrypt/live/money.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/money.example.com/privkey.pem;

    client_max_body_size 256m;          # book imports; match LEKHAMINT_MAX_IMPORT_MB

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 600s;        # large imports on slow links
    }
}
```

Get the certificate with `sudo certbot --nginx -d money.example.com`. **Nginx Proxy Manager**: add a proxy host for `money.example.com` → `http://<server>:8080`, request a Let's Encrypt certificate on the SSL tab, and enable *Force SSL*.

## Traefik (Docker labels)

```yaml
services:
  lekhamint:
    image: ghcr.io/anand34577/lekhamint:latest
    environment: { LEKHAMINT_TRUST_PROXY: "true" }
    volumes: [lekhamint-data:/data]
    labels:
      - traefik.enable=true
      - traefik.http.routers.lekhamint.rule=Host(`money.example.com`)
      - traefik.http.routers.lekhamint.entrypoints=websecure
      - traefik.http.routers.lekhamint.tls.certresolver=letsencrypt
      - traefik.http.services.lekhamint.loadbalancer.server.port=8080
```

## Check it

Open `https://money.example.com/healthz`; it should show `{"status":"ok"}`. Then sign in from the web app and, on your phone, change the server address under **More → Sync** to the `https://` address.
