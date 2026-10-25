#!/bin/sh
# LekhaMint server installer for Linux and macOS.
#
#   curl -fsSL https://raw.githubusercontent.com/anand34577/lekhamint/main/install.sh | sh
#
# Options (after `sh -s --`):
#   --docker          run it in Docker instead (installs Docker on Linux if missing)
#   --port N          listen on port N (default 8080)
#   --version X.Y.Z   install a specific release (default: latest)
#   --uninstall       stop and remove the service (your data is kept)
#   --yes             do not ask questions
#
# Set LEKHAMINT_DOWNLOAD_BASE to download the release files from a mirror instead of GitHub.
#
# Running it again upgrades to the latest release and keeps your data and settings.
# Docs: https://github.com/anand34577/lekhamint/wiki/Installation

set -eu

REPO="anand34577/lekhamint"
RAW="${LEKHAMINT_RAW_BASE:-https://raw.githubusercontent.com/$REPO/main}"
PORT=8080
VERSION=""
MODE="binary"
ACTION="install"
ASSUME_YES=0

say()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --docker) MODE="docker" ;;
    --port) [ $# -ge 2 ] || die "--port needs a number"; PORT="$2"; shift ;;
    --port=*) PORT="${1#*=}" ;;
    --version) [ $# -ge 2 ] || die "--version needs a value"; VERSION="${2#v}"; shift ;;
    --version=*) VERSION="${1#*=}"; VERSION="${VERSION#v}" ;;
    --uninstall) ACTION="uninstall" ;;
    --yes|-y) ASSUME_YES=1 ;;
    -h|--help) sed -n '2,17p' "$0" 2>/dev/null || true; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
  shift
done

case "$PORT" in ''|*[!0-9]*) die "--port must be a number" ;; esac
if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then die "--port must be between 1 and 65535"; fi

OS=$(uname -s | tr '[:upper:]' '[:lower:]')
case "$OS" in
  linux|darwin) ;;
  freebsd) [ "$MODE" = binary ] || die "Docker mode is not supported on FreeBSD" ;;
  *) die "unsupported system: $OS. On Windows use: irm https://raw.githubusercontent.com/$REPO/main/install.ps1 | iex" ;;
esac
case "$(uname -m)" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  armv7l|armv7|armv6l) ARCH=arm ;;
  *) die "unsupported CPU: $(uname -m)" ;;
esac

# Commands that need root go through sudo when we are not root.
SUDO=""
if [ "$(id -u)" -ne 0 ] && [ "$OS" != darwin ]; then
  if have sudo; then SUDO="sudo"; else die "run as root or install sudo"; fi
fi

confirm() {
  [ "$ASSUME_YES" = 1 ] && return 0
  [ -r /dev/tty ] || return 0
  printf '%s [Y/n] ' "$1" > /dev/tty
  read -r reply < /dev/tty || reply=""
  case "$reply" in [nN]*) return 1 ;; esac
  return 0
}

download() { # url dest
  if have curl; then curl -fsSL --retry 3 -o "$2" "$1"
  elif have wget; then wget -q -O "$2" "$1"
  else die "curl or wget is required"; fi
}

lan_ip() {
  ip=""
  if have hostname && hostname -I >/dev/null 2>&1; then ip=$(hostname -I | awk '{print $1}'); fi
  if [ -z "$ip" ] && have ipconfig; then ip=$(ipconfig getifaddr en0 2>/dev/null || true); fi
  echo "${ip:-localhost}"
}

wait_healthy() {
  i=0
  while [ $i -lt 40 ]; do
    if have curl && curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then return 0; fi
    if ! have curl && wget -q -O /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null; then return 0; fi
    i=$((i + 1)); sleep 1
  done
  return 1
}

finish() {
  ip=$(lan_ip)
  echo
  say "LekhaMint is running."
  echo "    On this computer:   http://localhost:$PORT"
  if [ "$ip" != localhost ] && [ "${LEKHAMINT_BIND:-127.0.0.1}" = "0.0.0.0" ]; then echo "    On your network:    http://$ip:$PORT"; fi
  echo
  echo "    Open it and create your account (the first account becomes the admin)."
  echo "    In the Android app: More -> Sync, then enter the address above."
  echo "    For access from outside your home, put it behind HTTPS:"
  echo "    https://github.com/$REPO/wiki/Reverse-Proxy"
  echo
}

# Files the installer downloads (compose file, .env example, systemd unit) come
# from a release tag, not the moving main branch, so a push to main cannot
# change what future installs run. Binary downloads are checksum-verified too.
STABLE_REF=""
resolve_ref() {
  [ -n "$STABLE_REF" ] && { echo "$STABLE_REF"; return; }
  if [ -n "$VERSION" ]; then STABLE_REF="v$VERSION"
  else
    tag=""
    if have curl; then tag=$(curl -fsSL --max-time 10 "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1)
    elif have wget; then tag=$(wget -q -T 10 -O - "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1)
    fi
    case "$tag" in v[0-9]*) STABLE_REF="$tag" ;;
      *) warn "could not resolve the latest release; using the main branch" ; STABLE_REF="main" ;;
    esac
  fi
  echo "$STABLE_REF"
}

random_hex() { # n bytes as hex
  if have openssl; then openssl rand -hex "$1" 2>/dev/null && return; fi
  head -c "$1" /dev/urandom 2>/dev/null | od -An -tx1 | tr -d ' \n'
}

# ---------------------------------------------------------------- Docker mode
install_docker_mode() {
  if ! have docker; then
    [ "$OS" = linux ] || die "install Docker Desktop first: https://docs.docker.com/desktop/"
    confirm "Docker is not installed. Install it now with get.docker.com?" || die "Docker is required for --docker"
    say "Installing Docker"
    download https://get.docker.com /tmp/get-docker.sh
    $SUDO sh /tmp/get-docker.sh
    rm -f /tmp/get-docker.sh
    $SUDO systemctl enable --now docker 2>/dev/null || true
  fi
  DOCKER="docker"
  docker info >/dev/null 2>&1 || DOCKER="$SUDO docker"
  $DOCKER compose version >/dev/null 2>&1 || die "Docker Compose v2 is required (docker compose)"

  ref=$(resolve_ref)
  say "Using files from release $ref"
  RAW_TAG="https://raw.githubusercontent.com/$REPO/$ref"
  if [ "$(id -u)" -eq 0 ] || [ "$OS" = linux ]; then DIR=/opt/lekhamint; else DIR="$HOME/lekhamint"; fi
  say "Setting up $DIR"
  $SUDO mkdir -p "$DIR"
  tmp=$(mktemp -d)
  download "$RAW_TAG/docker-compose.yml" "$tmp/docker-compose.yml"
  $SUDO cp "$tmp/docker-compose.yml" "$DIR/docker-compose.yml"
  if [ ! -f "$DIR/.env" ]; then
    download "$RAW_TAG/.env.example" "$tmp/.env"
    pg=$(random_hex 24)
    sed -e "s/^LEKHAMINT_PORT=.*/LEKHAMINT_PORT=$PORT/" -e "s/^LEKHAMINT_VERSION=.*/LEKHAMINT_VERSION=${ref#v}/" \
        -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$pg|" \
        -e "s|^TZ=.*|TZ=$(cat /etc/timezone 2>/dev/null || echo UTC)|" "$tmp/.env" > "$tmp/.env.new"
    $SUDO cp "$tmp/.env.new" "$DIR/.env"
    $SUDO chmod 0640 "$DIR/.env"
  else
    PORT=$($SUDO sed -n 's/^LEKHAMINT_PORT=//p' "$DIR/.env" | head -1); PORT=${PORT:-8080}
    [ -n "$VERSION" ] && $SUDO sed -i.bak "s/^LEKHAMINT_VERSION=.*/LEKHAMINT_VERSION=$VERSION/" "$DIR/.env"
    say "Keeping your settings in $DIR/.env"
  fi
  rm -rf "$tmp"
  say "Starting LekhaMint"
  (cd "$DIR" && $DOCKER compose pull && $DOCKER compose up -d)
  wait_healthy || die "the container did not become healthy; see: cd $DIR && docker compose logs"
  finish
  echo "    Settings: $DIR/.env   Logs: cd $DIR && docker compose logs -f"
  echo "    Upgrade:  run this installer again, or: cd $DIR && docker compose pull && docker compose up -d"
  echo
}

uninstall_docker_mode() {
  for DIR in /opt/lekhamint "$HOME/lekhamint"; do
    if [ -f "$DIR/docker-compose.yml" ]; then
      (cd "$DIR" && ($SUDO docker compose down || docker compose down))
      say "Stopped. Your data is in the Docker volume 'lekhamint_lekhamint-data'; remove it with: docker volume rm lekhamint_lekhamint-data"
      return
    fi
  done
  warn "no Docker install found"
}

# ---------------------------------------------------------------- binary mode
fetch_binary() {
  name="lekhamint-$OS-$ARCH"
  if [ -n "$VERSION" ]; then base="https://github.com/$REPO/releases/download/v$VERSION"
  else base="https://github.com/$REPO/releases/latest/download"; fi
  if [ -n "${LEKHAMINT_DOWNLOAD_BASE:-}" ]; then
    base="${LEKHAMINT_DOWNLOAD_BASE%/}" # a mirror
    case "$base" in
      http://*) die "LEKHAMINT_DOWNLOAD_BASE must start with https:// (plain http can be tampered with)" ;;
      https://*) ;;
      *) die "LEKHAMINT_DOWNLOAD_BASE must be an https:// URL" ;;
    esac
  fi
  tmp=$(mktemp -d)
  say "Downloading $name ${VERSION:+v$VERSION}"
  download "$base/$name" "$tmp/lekhamint" || die "download failed: $base/$name"
  download "$base/SHA256SUMS.txt" "$tmp/SHA256SUMS.txt" || die "could not download checksums"
  want=$(awk -v n="$name" '$2 == n || $2 == "*"n {print $1}' "$tmp/SHA256SUMS.txt")
  [ -n "$want" ] || die "no checksum for $name"
  if have sha256sum; then got=$(sha256sum "$tmp/lekhamint" | awk '{print $1}')
  else got=$(shasum -a 256 "$tmp/lekhamint" | awk '{print $1}'); fi
  [ "$want" = "$got" ] || die "checksum mismatch for $name (expected $want, got $got)"
  chmod 0755 "$tmp/lekhamint"
  "$tmp/lekhamint" version >/dev/null || die "the downloaded binary does not run on this system"
  BIN_TMP="$tmp"
}

install_linux() {
  have systemctl || die "systemd was not found. Use --docker, or run the binary yourself (see the wiki)"
  fetch_binary
  $SUDO install -m 0755 "$BIN_TMP/lekhamint" /usr/local/bin/lekhamint
  rm -rf "$BIN_TMP"
  id lekhamint >/dev/null 2>&1 || $SUDO useradd --system --home /var/lib/lekhamint --shell /usr/sbin/nologin lekhamint 2>/dev/null \
    || $SUDO adduser -S -H -h /var/lib/lekhamint -s /sbin/nologin lekhamint
  $SUDO mkdir -p /etc/lekhamint
  if [ ! -f /etc/lekhamint/lekhamint.env ]; then
    printf '# LekhaMint settings. Restart after changes: sudo systemctl restart lekhamint\n# All settings: https://github.com/%s/wiki/Configuration\nLEKHAMINT_ADDR=:%s\n#LEKHAMINT_ALLOW_SIGNUP=false\n#LEKHAMINT_TRUST_PROXY=true\n#LEKHAMINT_BACKUP_DIR=/mnt/backup/lekhamint\n' "$REPO" "$PORT" \
      | $SUDO tee /etc/lekhamint/lekhamint.env >/dev/null
    $SUDO chmod 0640 /etc/lekhamint/lekhamint.env
    $SUDO chgrp lekhamint /etc/lekhamint/lekhamint.env 2>/dev/null || true
  else
    # The file is readable only by root and the service, hence sudo.
    PORT=$($SUDO sed -n 's/^LEKHAMINT_ADDR=.*://p' /etc/lekhamint/lekhamint.env | head -1); PORT=${PORT:-8080}
    say "Keeping your settings in /etc/lekhamint/lekhamint.env"
  fi
  tmp=$(mktemp)
  download "$(resolve_ref | sed "s|^|https://raw.githubusercontent.com/$REPO/|")/deploy/lekhamint.service" "$tmp"
  $SUDO install -m 0644 "$tmp" /etc/systemd/system/lekhamint.service
  rm -f "$tmp"
  $SUDO systemctl daemon-reload
  $SUDO systemctl enable lekhamint >/dev/null 2>&1
  $SUDO systemctl restart lekhamint
  wait_healthy || die "LekhaMint did not start; see: sudo journalctl -u lekhamint -n 50"
  finish
  echo "    Settings: /etc/lekhamint/lekhamint.env   Data: /var/lib/lekhamint"
  echo "    Logs:     sudo journalctl -u lekhamint -f"
  echo "    Upgrade:  run this installer again"
  echo
}

install_macos() {
  fetch_binary
  BIN_DIR=/usr/local/bin
  if [ -w "$BIN_DIR" ]; then
    install -m 0755 "$BIN_TMP/lekhamint" "$BIN_DIR/lekhamint"
  else
    BIN_DIR="$HOME/.local/bin"; mkdir -p "$BIN_DIR"; install -m 0755 "$BIN_TMP/lekhamint" "$BIN_DIR/lekhamint"
  fi
  rm -rf "$BIN_TMP"
  DATA="$HOME/Library/Application Support/LekhaMint"
  LOGS="$HOME/Library/Logs/LekhaMint"
  PLIST="$HOME/Library/LaunchAgents/app.lekhamint.server.plist"
  mkdir -p "$DATA" "$LOGS" "$(dirname "$PLIST")"
  cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>app.lekhamint.server</string>
  <key>ProgramArguments</key><array><string>$BIN_DIR/lekhamint</string><string>serve</string></array>
  <key>EnvironmentVariables</key><dict>
    <key>LEKHAMINT_DATA_DIR</key><string>$DATA</string>
    <key>LEKHAMINT_ADDR</key><string>:$PORT</string>
    <key>LEKHAMINT_LOG_FILE</key><string>$LOGS/lekhamint.log</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardErrorPath</key><string>$LOGS/stderr.log</string>
</dict></plist>
PLIST
  launchctl unload "$PLIST" 2>/dev/null || true
  launchctl load -w "$PLIST"
  wait_healthy || die "LekhaMint did not start; see $LOGS/lekhamint.log"
  finish
  echo "    Data: $DATA   Logs: $LOGS"
  echo "    It starts when you log in. Upgrade: run this installer again."
  echo
}

uninstall_binary() {
  if [ "$OS" = darwin ]; then
    PLIST="$HOME/Library/LaunchAgents/app.lekhamint.server.plist"
    launchctl unload "$PLIST" 2>/dev/null || true
    rm -f "$PLIST"
    say "Stopped. Data kept in ~/Library/Application Support/LekhaMint"
  else
    $SUDO systemctl disable --now lekhamint 2>/dev/null || true
    $SUDO rm -f /etc/systemd/system/lekhamint.service
    $SUDO systemctl daemon-reload 2>/dev/null || true
    say "Stopped. Data kept in /var/lib/lekhamint, settings in /etc/lekhamint"
  fi
  $SUDO rm -f /usr/local/bin/lekhamint 2>/dev/null || true
  rm -f "$HOME/.local/bin/lekhamint"
}

if [ "$ACTION" = uninstall ]; then
  if [ "$MODE" = docker ]; then uninstall_docker_mode; else uninstall_binary; fi
  exit 0
fi
say "Installing the LekhaMint server ($OS/$ARCH, $MODE)"
if [ "$MODE" = docker ]; then install_docker_mode
elif [ "$OS" = darwin ]; then install_macos
else install_linux; fi
