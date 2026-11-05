param([switch]$EvaluateAi)
$ErrorActionPreference = 'Stop'
$servicePath = Join-Path $PSScriptRoot 'Service\deeptector-backend'
$pythonPath = Join-Path $servicePath '.venv-model\Scripts\python.exe'
$referencePath = Join-Path $PSScriptRoot 'deepGame-reference'
$dataPath = Join-Path $PSScriptRoot 'local-runtime\data'
$modelPath = Join-Path $PSScriptRoot 'AI-Generated-Media-Detection\deepfake_detector'
if (-not (Test-Path -LiteralPath (Join-Path $referencePath 'src\data\videos.json'))) { throw 'Clone the team deepGame repo into integrations/deepGame-reference first.' }
$env:OMP_NUM_THREADS = '2'; $env:MKL_NUM_THREADS = '2'; $env:OPENBLAS_NUM_THREADS = '2'
$env:PYTHONUTF8 = '1'; $env:PYTHONUNBUFFERED = '1'; $env:DEEPTECTOR_JOB_TIMEOUT_SECONDS = '600'
Push-Location -LiteralPath $servicePath
try {
    & $pythonPath -m app.import_deepgame $referencePath --data-dir $dataPath
    if ($LASTEXITCODE -ne 0) { throw 'Game clip registration failed.' }
    if ($EvaluateAi) {
        Write-Output 'Evaluating 24 clips with real team weights. This can take several minutes. Existing scores are preserved.'
        & $pythonPath -m app.evaluate_games --model-dir $modelPath --data-dir $dataPath
        if ($LASTEXITCODE -ne 0) { throw 'Evaluation stopped. Successfully evaluated clips are preserved; rerun to resume.' }
    }
} finally { Pop-Location }
