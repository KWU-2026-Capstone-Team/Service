$ErrorActionPreference = 'Stop'
$script:PackageRoot = $PSScriptRoot
$script:Python = Join-Path $PSScriptRoot 'runtime/python.exe'
if (-not (Test-Path -LiteralPath $script:Python)) { $script:Python = Join-Path $PSScriptRoot 'runtime/Scripts/python.exe' }
$script:Adb = Join-Path $PSScriptRoot 'android/platform-tools/adb.exe'
$script:DataRoot = Join-Path $PSScriptRoot 'runtime-data'
$script:ProcessFile = Join-Path $script:DataRoot 'processes.json'

function Set-ExhibitionEnvironment {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'Windows x64 is required.' }
    if ($script:PackageRoot -match '[^\x00-\x7F]') { throw 'Use an ASCII path, for example C:\VeriVidService. Do not run inside a ZIP.' }
    if (-not (Test-Path -LiteralPath $script:Python)) { throw 'Python runtime is missing. For the GitHub version, run Setup-Exhibition.cmd first.' }
    New-Item -ItemType Directory -Path $script:DataRoot -Force | Out-Null
    $env:DEEPTECTOR_DATA_DIR = $script:DataRoot
    $env:DEEPTECTOR_DB_PATH = Join-Path $script:DataRoot 'deeptector.sqlite3'
    $env:DEEPTECTOR_STORAGE_DIR = Join-Path $script:DataRoot 'storage'
    $env:DEEPTECTOR_MODEL_DIR = Join-Path $script:PackageRoot 'model'
    $env:DEEPTECTOR_INFERENCE_MODE = 'real'
    $env:DEEPTECTOR_LOCAL_RUNTIME = '1'
    $env:DEEPTECTOR_JOB_TIMEOUT_SECONDS = '600'
    $env:OMP_NUM_THREADS = '2'
    $env:MKL_NUM_THREADS = '2'
    $env:OPENBLAS_NUM_THREADS = '2'
    $env:CUDA_VISIBLE_DEVICES = '-1'
    $env:HF_HUB_OFFLINE = '1'
    $env:TRANSFORMERS_OFFLINE = '1'
    $env:PYTHONUTF8 = '1'
    $env:PYTHONUNBUFFERED = '1'
    $env:PYTHONNOUSERSITE = '1'
}

function Stop-RecordedExhibition {
    if (-not (Test-Path -LiteralPath $script:ProcessFile)) { return }
    $record = Get-Content -Raw -LiteralPath $script:ProcessFile | ConvertFrom-Json
    if ($record.executable -ine $script:Python) { throw 'Process record belongs to another package location; no process was stopped.' }
    foreach ($role in @('api','worker')) {
        $taskProcessId = [int]$record.$role
        if ($taskProcessId -le 0) { continue }
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$taskProcessId" -ErrorAction SilentlyContinue
        if (-not $processInfo) { continue }
        $module = if ($role -eq 'api') { 'uvicorn app.main:app' } else { 'app.worker' }
        if ($processInfo.ExecutablePath -ine $script:Python -or $processInfo.CommandLine -notlike "*$module*") {
            throw "PID $taskProcessId does not match this server. It was not stopped."
        }
        & taskkill.exe /PID $taskProcessId /T /F | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Unable to stop PID $taskProcessId" }
    }
}
