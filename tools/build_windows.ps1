param([string]$PythonExe = 'python', [string]$DistRoot = '', [switch]$ReuseColmapWorker)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:PYTHONPATH = Join-Path $projectRoot '.python-tools'
$desktopRoot = Join-Path $projectRoot 'desktop'
Set-Location -LiteralPath $desktopRoot
if (!$DistRoot) { $DistRoot = Join-Path $desktopRoot 'dist' }
$DistRoot = [IO.Path]::GetFullPath($DistRoot)
if (!$DistRoot.StartsWith([IO.Path]::GetFullPath($desktopRoot) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Build output must stay inside the desktop workspace.' }
if (!$ReuseColmapWorker) {
    & $PythonExe -m PyInstaller --noconfirm --onefile --name colmap-worker --collect-all pycolmap --collect-all numpy --distpath tools --workpath build/colmap --specpath build colmap_worker.py
    if ($LASTEXITCODE -ne 0) { throw 'COLMAP worker packaging failed.' }
} elseif (!(Test-Path -LiteralPath (Join-Path $desktopRoot 'tools/colmap-worker.exe'))) { throw 'Existing CPU worker is missing.' }
& $PythonExe -m PyInstaller --noconfirm --onedir --windowed --name RoomSplat --collect-submodules scipy._external --distpath $DistRoot --workpath build/gui --specpath build launch.py
if ($LASTEXITCODE -ne 0) { throw 'RoomSplat packaging failed.' }
$portableRoot = Join-Path $DistRoot 'RoomSplat'
Copy-Item -LiteralPath (Join-Path $desktopRoot 'tools') -Destination $portableRoot -Recurse -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'tools/Pull-Captures.ps1') -Destination $portableRoot -Force
Write-Output (Join-Path $portableRoot 'RoomSplat.exe')
