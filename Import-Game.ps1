param([string]$Source)
$ErrorActionPreference = 'Stop'
$backendRoot = Join-Path $PSScriptRoot 'deeptector-backend'
$servicePython = Join-Path $backendRoot '.venv\Scripts\python.exe'
if (!(Test-Path -LiteralPath $servicePython)) { throw 'Run .\Start-Service.ps1 -Setup first to install Python dependencies.' }
if (!$Source) { $Source = Join-Path (Split-Path $PSScriptRoot -Parent) 'deepGame-reference' }
$gameReference = (Resolve-Path -LiteralPath $Source).Path
if (!(Test-Path -LiteralPath (Join-Path $gameReference 'src\data\videos.json'))) { throw 'Source must be a deepGame checkout containing the label catalog.' }
Push-Location $backendRoot
try {
    & $servicePython -m app.import_deepgame $gameReference --data-dir (Join-Path $backendRoot 'data\integrated')
    if ($LASTEXITCODE -ne 0) { throw 'Game import failed; inspect the reported validation error.' }
} finally { Pop-Location }
