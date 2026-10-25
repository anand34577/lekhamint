# Sign-in and Notifications

Everything here is optional. Settings are environment variables; see [Configuration](Configuration) for where to put them.

## Single sign-on

Works with Keycloak, Authentik, Authelia, Zitadel, Google and any OpenID Connect provider.

1. Set `LEKHAMINT_PUBLIC_URL` to the address people use, for example `https://money.example.com`.
2. At the provider, create a client with the redirect URI `<PUBLIC_URL>/api/v1/auth/oidc/callback`.
3. Set `LEKHAMINT_OIDC_ISSUER`, `LEKHAMINT_OIDC_CLIENT_ID` and, if you use one, `LEKHAMINT_OIDC_CLIENT_SECRET`.
4. Restart. A sign-in button appears on the login page.

People are matched by email. `LEKHAMINT_OIDC_ONLY=true` turns off passwords; check single sign-on works first.

## Email

| Setting | Meaning |
|---|---|
| `LEKHAMINT_SMTP_HOST`, `LEKHAMINT_SMTP_PORT` | Mail server or relay |
| `LEKHAMINT_SMTP_USER`, `LEKHAMINT_SMTP_PASSWORD` | Login, only if the server needs one |
| `LEKHAMINT_SMTP_FROM` | Sender, such as `LekhaMint <no-reply@example.com>` |
| `LEKHAMINT_SMTP_SECURITY` | `starttls` (port 587), `tls` (port 465) or `none` (port 25) |
| `LEKHAMINT_SMTP_SKIP_VERIFY` | `true` for a relay with a self-signed certificate |

**SMTP relay without encryption:** set `LEKHAMINT_SMTP_SECURITY=none` and leave the user and password empty. A relay that offers no login is used as it is even if a user is set.

## Gotify

Set `LEKHAMINT_GOTIFY_URL`. Each person pastes their own application token in **Settings → Notifications**.

## What is sent

Each person picks channels and events in **Settings → Notifications** and can send a test message: sign-in and password alerts, bills due within 3 days, and budgets reaching their warning level or limit. Reminders go out once a day at `LEKHAMINT_REMINDER_HOUR` (server time, default 8; `-1` turns them off).
