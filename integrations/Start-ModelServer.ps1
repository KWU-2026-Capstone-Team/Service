param([int]$Port = 8000)
$ErrorActionPreference = 'Stop'
$servicePath = Join-Path $PSScriptRoot 'Service\deeptector-backend'
$pythonPath = Join-Path $servicePath '.venv-model\Scripts\python.exe'
$modelPath = Join-Path $PSScriptRoot 'AI-Generated-Media-Detection\deepfake_detector'
$runtimePath = Join-Path $PSScriptRoot 'local-runtime'
if (-not (Test-Path -LiteralPath $pythonPath)) { throw 'Model environment is not installed.' }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is already in use. No processes were stopped." }
New-Item -ItemType Directory -Path $runtimePath -Force | Out-Null
$env:DEEPTECTOR_MODEL_DIR = (Resolve-Path -LiteralPath $modelPath).Path
$env:DEEPTECTOR_DATA_DIR = Join-Path $runtimePath 'data'
$env:DEEPTECTOR_INFERENCE_MODE = 'real'
$env:DEEPTECTOR_LOCAL_RUNTIME = '1'
$env:DEEPTECTOR_RUN_ID = [guid]::NewGuid().ToString()
$env:DEEPTECTOR_JOB_TIMEOUT_SECONDS = '600'
$env:OMP_NUM_THREADS = '2'
$env:MKL_NUM_THREADS = '2'
$env:OPENBLAS_NUM_THREADS = '2'
$env:PYTHONUTF8 = '1'
$env:PYTHONUNBUFFERED = '1'
$workerProcess = Start-Process -FilePath $pythonPath -ArgumentList '-m','app.worker' -WorkingDirectory $servicePath -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimePath 'worker.log') -RedirectStandardError (Join-Path $runtimePath 'worker-error.log')
try {
    $apiProcess = Start-Process -FilePath $pythonPath -ArgumentList '-m','uvicorn','app.main:app','--host','127.0.0.1','--port',"$Port" -WorkingDirectory $servicePath -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimePath 'api.log') -RedirectStandardError (Join-Path $runtimePath 'api-error.log')
} catch { Stop-Process -Id $workerProcess.Id -ErrorAction SilentlyContinue; throw }
[pscustomobject]@{ api = $apiProcess.Id; worker = $workerProcess.Id; port = $Port; executable = $pythonPath; started = (Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimePath 'processes.json') -Encoding utf8
Write-Output "Local REAL model API: http://127.0.0.1:$Port (API $($apiProcess.Id), worker $($workerProcess.Id)). CPU threads limited to 2."
Write-Output 'Stop with integrations\Stop-ModelServer.ps1. No external network interface is exposed.'
