$ErrorActionPreference = 'Stop'
$processFile = Join-Path $PSScriptRoot 'local-runtime\processes.json'
if (-not (Test-Path -LiteralPath $processFile)) { Write-Output 'No recorded local server.'; return }
$record = Get-Content -Raw -LiteralPath $processFile | ConvertFrom-Json
foreach ($role in @('api', 'worker')) {
    $taskProcessId = [int]$record.$role
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$taskProcessId" -ErrorAction SilentlyContinue
    if (-not $processInfo) { continue }
    $expectedModule = if ($role -eq 'api') { 'uvicorn app.main:app' } else { 'app.worker' }
    if ($processInfo.ExecutablePath -ine $record.executable -or $processInfo.CommandLine -notlike "*$expectedModule*") {
        throw "PID $taskProcessId no longer matches this local model server. It was not stopped."
    }
    # Stop only the recorded server process tree, including a running inference child.
    & taskkill.exe /PID $taskProcessId /T /F
}
Write-Output 'Local model API and worker stopped. Model files and app data are preserved.'
