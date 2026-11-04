param(
    [switch]$Setup,
    [switch]$Build,
    [int]$Port = 5173,
    [ValidateSet('demo','real')][string]$Mode = 'demo',
    [string]$ModelDir,
    [string]$WorkerPython
)
$ErrorActionPreference = 'Stop'
$serviceRoot = $PSScriptRoot
$backendRoot = Join-Path $serviceRoot 'deeptector-backend'
$servicePython = Join-Path $backendRoot '.venv\Scripts\python.exe'
if (!(Test-Path -LiteralPath $servicePython)) {
    & py -3.11 -m venv (Join-Path $backendRoot '.venv')
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.11 environment creation failed.' }
    $Setup = $true
}
if ($Setup) {
    & $servicePython -m pip install -r (Join-Path $backendRoot 'requirements-lock.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed.' }
}
if ($Setup -or $Build -or !(Test-Path (Join-Path $serviceRoot 'frontend\out\index.html'))) {
    Push-Location (Join-Path $serviceRoot 'frontend')
    try {
        if ($Setup -or !(Test-Path 'node_modules')) {
            & npm.cmd ci
            if ($LASTEXITCODE -ne 0) { throw 'npm ci failed. Check Node.js installation and lockfile.' }
        }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
}
$serviceArgs = @((Join-Path $backendRoot 'scripts\run_integrated.py'), '--port', "$Port", '--mode', $Mode)
if ($ModelDir) { $serviceArgs += @('--model-dir', $ModelDir) }
if ($WorkerPython) { $serviceArgs += @('--worker-python', $WorkerPython) }
& $servicePython @serviceArgs
exit $LASTEXITCODE
