# Installation

Pick one way. All of them give you the same server with the web app built in, listening on port **8080**.

| Your machine | Easiest way |
|---|---|
| Linux (Ubuntu, Debian, Fedora, Raspberry Pi OS…) | [One-line install](#linux-and-macos) or [Docker](Docker) |
| macOS | [One-line install](#linux-and-macos) |
| Windows 10 / 11 | [One-line install](#windows) |
| NAS (Synology, QNAP, Unraid, TrueNAS) | [Docker](Docker) |
| Anything with Docker | [Docker](Docker) |

## Linux and macOS

Open a terminal and run:

```sh
curl -fsSL https://raw.githubusercontent.com/anand34577/lekhamint/main/install.sh | sh
```

What it does:

1. Detects your system and CPU (x86-64, ARM64 or 32-bit ARM).
2. Downloads the latest release and **checks it against the published SHA-256 checksum**.
3. **Linux:** installs `/usr/local/bin/lekhamint`, creates a `lekhamint` system user, puts your settings in `/etc/lekhamint/lekhamint.env`, and starts a hardened systemd service that restarts on failure and starts at boot. Data lives in `/var/lib/lekhamint`.
   **macOS:** installs the program, keeps data in `~/Library/Application Support/LekhaMint`, logs in `~/Library/Logs/LekhaMint`, and starts it at login with launchd.
4. Waits until the server answers, then prints the address to open.

It asks for your password (sudo) on Linux, because it installs a system service.

Options go after `sh -s --`:

```sh
# Run it in Docker instead (installs Docker on Linux if it is missing)
curl -fsSL https://raw.githubusercontent.com/anand34577/lekhamint/main/install.sh | sh -s -- --docker

# Another port, or a specific version
curl -fsSL https://raw.githubusercontent.com/anand34577/lekhamint/main/install.sh | sh -s -- --port 9090 --version 1.1.0

# Remove the service (your data is kept)
curl -fsSL https://raw.githubusercontent.com/anand34577/lekhamint/main/install.sh | sh -s -- --uninstall
```

Run the same command again at any time to **upgrade**; your data and settings are kept.

> Prefer to read a script before running it? Download it first:
> `curl -fsSLO https://raw.githubusercontent.com/anand34577/lekhamint/main/install.sh`, read it, then `sh install.sh`.

## Windows

Open **PowerShell** and run:

```powershell
irm https://raw.githubusercontent.com/anand34577/lekhamint/main/install.ps1 | iex
```

- **Run PowerShell as Administrator** (right-click → *Run as administrator*) to install it for the whole computer. It then starts with Windows, even before anyone logs in, and the installer opens the port in Windows Firewall so your phone can connect. Files go to `C:\ProgramData\LekhaMint`.
- Without Administrator it installs for your account only (`%LOCALAPPDATA%\LekhaMint`) and starts when you log in. Windows will ask whether to allow network access the first time; allow it on **private** networks.

Settings are in `settings.env` in that folder, logs in `logs\`, and data in `data\`. It runs as a scheduled task called **LekhaMint** (Task Scheduler) that restarts it if it stops.

Options:

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/anand34577/lekhamint/main/install.ps1))) -Port 9090
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/anand34577/lekhamint/main/install.ps1))) -Docker      # with Docker Desktop
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/anand34577/lekhamint/main/install.ps1))) -Uninstall
```

## After installing

1. Open the address the installer printed, for example `http://localhost:8080` on the same computer or `http://192.168.1.20:8080` from another device on your network.
2. Click **Create an account**. The first account can always be created, and it becomes the admin. After that, sign-ups are closed unless you turn them on (see [Configuration](Configuration)); add family members with [`lekhamint user add`](Command-Line) instead.
3. Create your first book (a *ledger*), pick your currency, and add your accounts.
4. On your phone, install the [Android app](Android-App) and connect it under **More → Sync**.

To use it away from home, put it behind HTTPS: see [Reverse Proxy](Reverse-Proxy).

## Install by hand

Download the file for your system from the [latest release](https://github.com/anand34577/lekhamint/releases/latest):

| System | File |
|---|---|
| Linux x86-64 / ARM64 / 32-bit ARM (Raspberry Pi 2/3) | `lekhamint-linux-amd64`, `lekhamint-linux-arm64`, `lekhamint-linux-arm` |
| macOS Intel / Apple silicon | `lekhamint-darwin-amd64`, `lekhamint-darwin-arm64` |
| Windows x86-64 / ARM | `lekhamint-windows-amd64.exe`, `lekhamint-windows-arm64.exe` |
| FreeBSD | `lekhamint-freebsd-amd64` |

Check it against `SHA256SUMS.txt`, make it executable (`chmod +x`), and run:

```sh
./lekhamint-linux-amd64 serve --data-dir ./data
```

For a permanent Linux install, copy [`deploy/lekhamint.service`](https://github.com/anand34577/lekhamint/blob/main/deploy/lekhamint.service) to `/etc/systemd/system/`; the comments at the top show the three commands needed.

## Just trying it out?

`lekhamint demo` starts a throwaway server filled with a year of sample data and deletes everything when you stop it:

```sh
./lekhamint-linux-amd64 demo          # then open http://localhost:8080, demo@example.com / demo1234
```

Or use the [online demo](https://anand34577.github.io/lekhamint/) without installing anything.
