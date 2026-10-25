# Single sign-on and notifications

Both are optional and off until you set the environment variables below (or the matching `--flags`).

## Single sign-on (OpenID Connect)

Works with Keycloak, Authentik, Authelia, Zitadel, Google and any provider that publishes
`/.well-known/openid-configuration`. LekhaMint uses the authorization-code flow with PKCE and reads the
person's email and name from the provider's userinfo endpoint.

| Variable | Meaning |
| --- | --- |
| `LEKHAMINT_PUBLIC_URL` | Address people use to reach LekhaMint, e.g. `https://money.example.com`. Required. |
| `LEKHAMINT_OIDC_ISSUER` | Issuer URL, e.g. `https://auth.example.com/realms/home` (Keycloak) |
| `LEKHAMINT_OIDC_CLIENT_ID` | Client ID registered at the provider |
| `LEKHAMINT_OIDC_CLIENT_SECRET` | Client secret; leave empty for a public client |
| `LEKHAMINT_OIDC_NAME` | Text for the button, e.g. `Keycloak` |
| `LEKHAMINT_OIDC_SCOPES` | Default `openid email profile` |
| `LEKHAMINT_OIDC_AUTO_CREATE` | Default `true`: first sign-in creates the account. `false` allows only existing emails. |
| `LEKHAMINT_OIDC_ONLY` | `true` turns off password sign-in and registration |

**Provider setup (Keycloak):** create an OpenID Connect client, set *Valid redirect URI* to
`<PUBLIC_URL>/api/v1/auth/oidc/callback`, enable *Client authentication* if you use a secret, and make sure
users have an email address (marked verified).

**How accounts match:** by email. A person who already has a password account is signed into the same account.
The first person to sign in to an empty server becomes the administrator. Accounts created through SSO have no
password; deleting one asks for the email address instead. A provider that reports `email_verified: false` is refused.

**Security notes:** the session token is never placed in a URL; the browser receives a one-time code (valid one
minute) and trades it for the token. State and PKCE are single-use and expire after 10 minutes. Behind a reverse
proxy, set `LEKHAMINT_TRUST_PROXY=true` so rate limits see real client addresses.

## Email (SMTP)

| Variable | Meaning |
| --- | --- |
| `LEKHAMINT_SMTP_HOST` / `LEKHAMINT_SMTP_PORT` | Mail server (port defaults to 587, or 465 with `tls`) |
| `LEKHAMINT_SMTP_USER` / `LEKHAMINT_SMTP_PASSWORD` | Login, if the server needs one |
| `LEKHAMINT_SMTP_FROM` | Sender, e.g. `LekhaMint <no-reply@example.com>` |
| `LEKHAMINT_SMTP_SECURITY` | `starttls` (default), `tls` (implicit TLS) or `none` (trusted local relay only) |
| `LEKHAMINT_SMTP_SKIP_VERIFY` | `true` accepts a self-signed or mismatched relay certificate (still encrypted). With `none`, a relay that offers no login is used without one even if a user is set; LOGIN is used when PLAIN is not offered |

## Gotify

Set `LEKHAMINT_GOTIFY_URL` to your Gotify server. Each person creates an application in Gotify and pastes its
token under *Settings > Notifications*; the token is stored on the server, never shown again, and sent in a header
(not the URL). The server address is fixed by the administrator, so people cannot point the server at other hosts.

## What gets sent

Each person chooses channels and events under *Settings > Notifications* and can send a test message.

- **Security alerts:** new sign-ins and password changes.
- **Bills due:** recurring items in "remind" mode due within 3 days (or overdue).
- **Budget limits:** a budget reaching its warning level, and going over its limit.

Reminders are checked once a day at `LEKHAMINT_REMINDER_HOUR` (server time, default 8; `-1` turns them off) and
each is sent once, even across restarts.
