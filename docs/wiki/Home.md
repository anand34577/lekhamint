# LekhaMint

*Lekha* (लेखा) means "account" or "record". LekhaMint keeps a clear, exact record of your money: expenses, income, transfers, budgets, bills, loans to friends, and reports, across an Android app and a web app that you host yourself.

**Try it first:** the [online demo](https://anand34577.github.io/lekhamint/) runs entirely in your browser with a year of sample data. Sign in with `demo@example.com` / `demo1234`.

![LekhaMint web app](https://raw.githubusercontent.com/anand34577/lekhamint/main/docs/images/dashboard.png)

## Start here

| I want to… | Read |
|---|---|
| Install the server in one command | [Installation](Installation) |
| Run it with Docker or Docker Compose | [Docker](Docker) |
| Reach it from outside my home, with HTTPS | [Reverse Proxy](Reverse-Proxy) |
| Change a setting (port, sign-ups, backups, PostgreSQL) | [Configuration](Configuration) |
| Install the Android app and connect it | [Android App](Android-App) |
| Use the web app, shortcuts and quick add | [Web App](Web-App) |
| Share a book with family, use several devices | [Sync and Sharing](Sync-and-Sharing) |
| Back up and restore | [Backup and Restore](Backup-and-Restore) |
| Upgrade to a new version | [Upgrading](Upgrading) |
| Fix a problem | [Troubleshooting](Troubleshooting) · [FAQ](FAQ) |
| Manage users and backups from a terminal | [Command Line](Command-Line) |
| Build it myself or contribute | [Building from Source](Building-from-Source) |

## How the pieces fit

```
  Android app (works fully offline)          Web app (in any browser)
            │                                         │
            └────────── sync over HTTPS ──────────────┘
                              │
                 LekhaMint server (one small program)
                 SQLite file or PostgreSQL · daily backups
```

- **The Android app does not need a server.** It keeps everything on the phone, with encrypted automatic backups. Add a server when you want sync between devices, the web app, or family sharing.
- **The server is one program** for Linux, macOS, Windows and FreeBSD (or one Docker image). It serves the web app too, so there is nothing else to install.
- **Your data stays with you.** There is no cloud service, no account with anyone else, no telemetry.

## Requirements

- Any 64-bit or ARM computer: a Raspberry Pi, a NAS, an old laptop, a small VPS. It idles at about 20 MB of memory.
- No database to install: SQLite is built in. PostgreSQL is optional for large installs.
- Android 8.0 or newer for the app. Any recent browser for the web app.
