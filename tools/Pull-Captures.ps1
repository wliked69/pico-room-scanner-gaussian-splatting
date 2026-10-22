param(
    [string]$DeviceId,
    [string]$Destination = (Join-Path $PSScriptRoot '../captures')
)
$ErrorActionPreference = 'Stop'
$platformTools = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools'
if (Test-Path -LiteralPath $platformTools) { $env:PATH = "$platformTools;$env:PATH" }
if (-not (Get-Command pico-cli -ErrorAction SilentlyContinue)) { throw 'pico-cli is not installed.' }
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$remoteRoot = '/sdcard/Android/data/com.example.roomsplat/files/RoomSplat'
$discoverArgs = @('shell', "find $remoteRoot -maxdepth 1 -type d -name 'exports*' -print")
if ($DeviceId) { $discoverArgs += @('--device', $DeviceId) }
$discovered = @(& pico-cli @discoverArgs)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read headset exports. Check USB debugging authorization.' }
$directories = @($discovered | ForEach-Object { $_.Trim() } | Where-Object {
    $_ -match '^/sdcard/Android/data/com\.example\.roomsplat/files/RoomSplat/exports(?:-app(?:-[0-9a-f]{8})?)?$'
} | Sort-Object -Unique)
if ($directories.Count -eq 0) { throw 'No export directory found. Package a scan on the headset first.' }
# Keep every earlier transfer. A new run uses its own directory and never replaces a local archive.
$runDirectory = Join-Path $Destination ('headset-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString().Substring(0,8))
New-Item -ItemType Directory -Path $runDirectory | Out-Null
foreach ($remote in $directories) {
    $local = Join-Path $runDirectory ($remote.Substring($remote.LastIndexOf('/') + 1))
    $cliArgs = @('files', 'pull', $remote, $local)
    if ($DeviceId) { $cliArgs += @('--device', $DeviceId) }
    & pico-cli @cliArgs
    if ($LASTEXITCODE -ne 0) { throw "Headset transfer failed: $remote" }
}
Get-ChildItem -LiteralPath $Destination -Recurse -Filter '*.zip' | Select-Object FullName,Length
