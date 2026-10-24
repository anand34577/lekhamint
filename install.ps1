# LekhaMint server installer for Windows (PowerShell 5.1 or later).
#
#   irm https://raw.githubusercontent.com/anand34577/lekhamint/main/install.ps1 | iex
#
# With options:
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/anand34577/lekhamint/main/install.ps1))) -Port 9090
#
#   -Port N        listen on port N (default 8080)
#   -Version X.Y.Z install a specific release (default: latest)
#   -Docker        run it with Docker Desktop instead
#   -Uninstall     stop and remove it (your data is kept)
#
# Set LEKHAMINT_DOWNLOAD_BASE to download the release files from a mirror instead of GitHub.
#
# Run as Administrator to install for all users: it then starts with Windows (before anyone
# logs in) and opens the port in the firewall for your phone. Otherwise it is installed for
# you only and starts when you log in. Running it again upgrades and keeps your data.
# Docs: https://github.com/anand34577/lekhamint/wiki/Installation

[CmdletBinding()]
param(
  [ValidateRange(1, 65535)][int]$Port = 8080,
  [string]$Version = "",
  [switch]$Docker,
  [switch]$Uninstall
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is much faster without the progress bar
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Repo = "anand34577/lekhamint"
$Raw = if ($env:LEKHAMINT_RAW_BASE) { $env:LEKHAMINT_RAW_BASE.TrimEnd("/") } else { "https://raw.githubusercontent.com/$Repo/main" }
$TaskName = "LekhaMint"
$Version = $Version.TrimStart("v")

function Say($msg) { Write-Host "==> $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "error: $msg" -ForegroundColor Red; throw $msg }

$IsAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($IsAdmin) { $Root = Join-Path $env:ProgramData "LekhaMint" } else { $Root = Join-Path $env:LOCALAPPDATA "LekhaMint" }
$Exe = Join-Path $Root "lekhamint.exe"
$Data = Join-Path $Root "data"
$Logs = Join-Path $Root "logs"
$Settings = Join-Path $Root "settings.env"

function Wait-Healthy([int]$p) {
  for ($i = 0; $i -lt 40; $i++) {
    try { if ((Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 "http://127.0.0.1:$p/healthz").StatusCode -eq 200) { return $true } } catch { }
    Start-Sleep -Seconds 1
  }
  return $false
}

function Show-Done([int]$p) {
  $ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" -and $_.PrefixOrigin -ne "WellKnown" } |
    Select-Object -First 1).IPAddress
  Write-Host ""
  Say "LekhaMint is running."
  Write-Host "    On this computer:  http://localhost:$p"
  if ($ip) { Write-Host "    On your network:   http://${ip}:$p" }
  Write-Host ""
  Write-Host "    Open it and create your account (the first account becomes the admin)."
  Write-Host "    In the Android app: More -> Sync, then enter the address above."
  Write-Host "    For access from outside your home: https://github.com/$Repo/wiki/Reverse-Proxy"
  Write-Host ""
}

# ------------------------------------------------------------------ Docker mode
if ($Docker) {
  $Dir = Join-Path $HOME "lekhamint"
  if ($Uninstall) {
    if (Test-Path (Join-Path $Dir "docker-compose.yml")) { Push-Location $Dir; docker compose down; Pop-Location }
    Say "Stopped. Your data is in the Docker volume 'lekhamint_lekhamint-data'."
    return
  }
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Fail "Docker Desktop is not installed. Get it from https://docs.docker.com/desktop/setup/install/windows-install/ (or: winget install Docker.DockerDesktop), start it, then run this again."
  }
  docker info *> $null
  if ($LASTEXITCODE -ne 0) { Fail "Docker is installed but not running. Start Docker Desktop and run this again." }
  New-Item -ItemType Directory -Force $Dir | Out-Null
  Invoke-WebRequest -UseBasicParsing "$Raw/docker-compose.yml" -OutFile (Join-Path $Dir "docker-compose.yml")
  $envFile = Join-Path $Dir ".env"
  if (-not (Test-Path $envFile)) {
    $text = (Invoke-WebRequest -UseBasicParsing "$Raw/.env.example").Content
    $tag = if ($Version) { $Version } else { "latest" }
    $text = $text -replace "(?m)^LEKHAMINT_PORT=.*", "LEKHAMINT_PORT=$Port" -replace "(?m)^LEKHAMINT_VERSION=.*", "LEKHAMINT_VERSION=$tag" -replace "(?m)^TZ=.*", "TZ=UTC"
    [IO.File]::WriteAllText($envFile, $text)
  } else {
    $m = Select-String -Path $envFile -Pattern "^LEKHAMINT_PORT=(\d+)" | Select-Object -First 1
    if ($m) { $Port = [int]$m.Matches[0].Groups[1].Value }
    Say "Keeping your settings in $envFile"
  }
  Push-Location $Dir
  try {
    docker compose pull; if ($LASTEXITCODE -ne 0) { Fail "docker compose pull failed" }
    docker compose up -d; if ($LASTEXITCODE -ne 0) { Fail "docker compose up failed" }
  } finally { Pop-Location }
  if (-not (Wait-Healthy $Port)) { Fail "the container did not become healthy; see: cd $Dir; docker compose logs" }
  Show-Done $Port
  Write-Host "    Settings: $envFile   Logs: cd $Dir; docker compose logs -f"
  return
}

# ------------------------------------------------------------------ binary mode
function Stop-LekhaMint {
  if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
  }
  Get-Process lekhamint -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $Exe } | Stop-Process -Force -ErrorAction SilentlyContinue
  Start-Sleep -Milliseconds 500
}

if ($Uninstall) {
  Stop-LekhaMint
  Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
  if ($IsAdmin) { Remove-NetFirewallRule -DisplayName "LekhaMint" -ErrorAction SilentlyContinue }
  Remove-Item $Exe -Force -ErrorAction SilentlyContinue
  Say "Removed. Your data and settings are kept in $Root"
  return
}

$Arch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "arm64" } else { "amd64" }
$Name = "lekhamint-windows-$Arch.exe"
$Base = if ($Version) { "https://github.com/$Repo/releases/download/v$Version" } else { "https://github.com/$Repo/releases/latest/download" }
if ($env:LEKHAMINT_DOWNLOAD_BASE) { $Base = $env:LEKHAMINT_DOWNLOAD_BASE.TrimEnd("/") }   # a mirror, or a folder of release files

Say "Installing the LekhaMint server (windows/$Arch$(if ($IsAdmin) { ', all users' } else { ', this user only' }))"
New-Item -ItemType Directory -Force $Root, $Data, $Logs | Out-Null
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("lekhamint-" + [Guid]::NewGuid())
New-Item -ItemType Directory $tmp | Out-Null
try {
  Say "Downloading $Name $(if ($Version) { "v$Version" })"
  Invoke-WebRequest -UseBasicParsing "$Base/$Name" -OutFile (Join-Path $tmp $Name)
  Invoke-WebRequest -UseBasicParsing "$Base/SHA256SUMS.txt" -OutFile (Join-Path $tmp "SHA256SUMS.txt")
  $want = (Get-Content (Join-Path $tmp "SHA256SUMS.txt") | Where-Object { ($_ -split "\s+")[1] -replace "^\*", "" -eq $Name } | ForEach-Object { ($_ -split "\s+")[0] }) | Select-Object -First 1
  if (-not $want) { Fail "no checksum for $Name" }
  $got = (Get-FileHash -Algorithm SHA256 (Join-Path $tmp $Name)).Hash.ToLower()
  if ($got -ne $want.ToLower()) { Fail "checksum mismatch for $Name" }
  Stop-LekhaMint
  Copy-Item (Join-Path $tmp $Name) $Exe -Force
} finally { Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }

if (-not (Test-Path $Settings)) {
  @(
    "# LekhaMint settings, one per line. Restart after changes: Stop-ScheduledTask LekhaMint; Start-ScheduledTask LekhaMint",
    "# All settings: https://github.com/$Repo/wiki/Configuration",
    "LEKHAMINT_ADDR=:$Port",
    "LEKHAMINT_DATA_DIR=$Data",
    "LEKHAMINT_LOG_FILE=$(Join-Path $Logs 'lekhamint.log')",
    "#LEKHAMINT_ALLOW_SIGNUP=false",
    "#LEKHAMINT_BACKUP_DIR=D:\Backups\LekhaMint"
  ) | Set-Content -Encoding ASCII $Settings
} else {
  $m = Select-String -Path $Settings -Pattern "^LEKHAMINT_ADDR=.*:(\d+)\s*$" | Select-Object -First 1
  if ($m) { $Port = [int]$m.Matches[0].Groups[1].Value }
  Say "Keeping your settings in $Settings"
}

# The scheduled task runs a tiny launcher that loads settings.env into the environment.
$Launcher = Join-Path $Root "start.ps1"
@'
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
foreach ($line in Get-Content (Join-Path $root "settings.env")) {
  if ($line -match '^\s*([A-Z0-9_]+)\s*=\s*(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), "Process") }
}
& (Join-Path $root "lekhamint.exe") serve 2>> (Join-Path $root "logs\stderr.log")
'@ | Set-Content -Encoding ASCII $Launcher

$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$Launcher`"" -WorkingDirectory $Root
$settingsObj = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
  -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
if ($IsAdmin) {
  $trigger = New-ScheduledTaskTrigger -AtStartup
  $principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
} else {
  $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
  $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
}
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settingsObj -Principal $principal `
  -Description "LekhaMint money manager server (https://github.com/$Repo)" -Force | Out-Null

if ($IsAdmin) {
  Remove-NetFirewallRule -DisplayName "LekhaMint" -ErrorAction SilentlyContinue
  New-NetFirewallRule -DisplayName "LekhaMint" -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private, Domain | Out-Null
}

Start-ScheduledTask -TaskName $TaskName
if (-not (Wait-Healthy $Port)) { Fail "LekhaMint did not start; see $Logs" }
Show-Done $Port
Write-Host "    Settings: $Settings"
Write-Host "    Data:     $Data"
Write-Host "    Logs:     $Logs"
if (-not $IsAdmin) { Write-Host "    It starts when you log in. Run as Administrator to start it with Windows and allow phones through the firewall." }
Write-Host "    Upgrade:  run this installer again"
Write-Host ""
