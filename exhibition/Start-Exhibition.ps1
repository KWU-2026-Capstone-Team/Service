param([int]$Port = 8000)
. "$PSScriptRoot/Exhibition.Common.ps1"
Set-ExhibitionEnvironment
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Port must be 1024..65535.' }
if (Test-Path -LiteralPath $script:ProcessFile) {
    $previous = Get-Content -Raw -LiteralPath $script:ProcessFile | ConvertFrom-Json
    foreach ($role in @('api','worker')) {
        $previousProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$previous.$role)" -ErrorAction SilentlyContinue
        if ($previousProcess -and $previousProcess.ExecutablePath -ieq $script:Python) {
            throw 'This package still has a running process. Run Stop-Exhibition.cmd before starting it again.'
        }
    }
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port is already in use. No existing server was changed. Use Stop-Exhibition.cmd for this package or choose another port."
}
& $script:Python "$PSScriptRoot/tools/prepare.py"
if ($LASTEXITCODE -ne 0) { throw 'Preparation failed. Run Check-Exhibition.cmd. If DLL loading fails, install third-party/VC_redist.x64.exe.' }
$env:DEEPTECTOR_RUN_ID = [guid]::NewGuid().ToString()
$record = [ordered]@{api=0;worker=0;port=$Port;executable=$script:Python;started=(Get-Date).ToString('o')}
try {
    $worker = Start-Process -FilePath $script:Python -ArgumentList '-m','app.worker' -WorkingDirectory "$PSScriptRoot/backend" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$script:DataRoot/worker.log" -RedirectStandardError "$script:DataRoot/worker-error.log"
    $record.worker = $worker.Id
    $record | ConvertTo-Json | Set-Content -LiteralPath $script:ProcessFile -Encoding UTF8
    $api = Start-Process -FilePath $script:Python -ArgumentList '-m','uvicorn','app.main:app','--host','127.0.0.1','--port',"$Port" -WorkingDirectory "$PSScriptRoot/backend" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$script:DataRoot/api.log" -RedirectStandardError "$script:DataRoot/api-error.log"
    $record.api = $api.Id
    $record | ConvertTo-Json | Set-Content -LiteralPath $script:ProcessFile -Encoding UTF8
    $ready = $false
    for ($attempt=0; $attempt -lt 45; $attempt++) {
        $worker.Refresh(); $api.Refresh()
        if ($worker.HasExited -or $api.HasExited) { throw 'A server process exited; inspect runtime-data/*-error.log.' }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$Port/health/ready" -TimeoutSec 2
            $workerHealth = Invoke-RestMethod "http://127.0.0.1:$Port/health/worker" -TimeoutSec 2
            if ($workerHealth.ready -eq $true -and $workerHealth.mode -eq 'real') { $ready=$true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Server readiness timed out. Check runtime-data logs.' }
    Write-Output "READY: REAL model server at http://127.0.0.1:$Port"
    Write-Output 'CPU mode; no internet or GPU required for bundled assets. First analysis may be slow.'
    Write-Output 'Next: open your Android emulator, or connect an Android phone by USB, then run Connect-Android.cmd.'
    Write-Output 'Server stays running after this window closes. Stop with Stop-Exhibition.cmd.'
} catch {
    Stop-RecordedExhibition
    throw
}
