param([string]$PythonExe, [string]$ReferenceRoot, [string]$AdbDirectory)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$repoRoot=Split-Path -Parent $PSScriptRoot
if (-not [Environment]::Is64BitOperatingSystem -or $env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { throw 'This setup requires Intel/AMD Windows x64.' }
if ($PSScriptRoot -match '[^\x00-\x7F]') { throw 'Clone into an ASCII path, for example C:\VeriVidService.' }
if (Test-Path -LiteralPath "$PSScriptRoot/runtime-data/processes.json") {
    $previous=Get-Content -Raw -LiteralPath "$PSScriptRoot/runtime-data/processes.json" | ConvertFrom-Json
    foreach($role in @('api','worker')) {
        $running=Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$previous.$role)" -ErrorAction SilentlyContinue
        if ($running -and $running.ExecutablePath -ieq $previous.executable) { throw 'Stop this exhibition server before running setup again.' }
    }
}
if (-not (Get-Command git.exe -ErrorAction SilentlyContinue)) { throw 'Install Git for Windows first: https://git-scm.com/downloads/win' }
$downloadRoot=Join-Path $PSScriptRoot '.downloads'
New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null
[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12

function Invoke-Checked([string]$Exe,[string[]]$Arguments) {
    & $Exe @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Exe (exit $LASTEXITCODE). Correct the issue and rerun setup; records were preserved." }
}
function Copy-SourceTree([string]$Source,[string]$Destination) {
    & robocopy.exe $Source $Destination /E /XD .git __pycache__ /XF *.pyc /NFL /NDL /NJH /NJS /NP | Out-Host
    if ($LASTEXITCODE -ge 8) { throw "Copy failed: $Source" }
}
function Get-TeamCheckout([string]$Name,[string]$Url,[string]$Commit,[string]$SparsePath='') {
    $path=Join-Path $downloadRoot $Name
    if ($ReferenceRoot) { $path=Join-Path (Resolve-Path -LiteralPath $ReferenceRoot).Path $Name }
    if (-not (Test-Path -LiteralPath (Join-Path $path '.git'))) {
        if ($ReferenceRoot) { throw "Reference checkout missing: $path" }
        if (Test-Path -LiteralPath $path) { throw "Non-Git directory exists at $path; preserve it and choose a clean checkout location." }
        Invoke-Checked 'git.exe' @('init',$path)
        Invoke-Checked 'git.exe' @('-C',$path,'remote','add','origin',$Url)
    }
    $actual=(& git.exe -C $path rev-parse --verify -q HEAD) -join ''
    if ($LASTEXITCODE -ne 0 -or -not $actual) {
        if ($ReferenceRoot) { throw "Reference checkout is incomplete: $path" }
        $origin=(& git.exe -C $path remote get-url origin) -join ''
        if ($LASTEXITCODE -ne 0 -or $origin.Trim() -ne $Url) { throw 'Incomplete checkout has an unexpected origin; no files were reset.' }
        if ($SparsePath) {
            Invoke-Checked 'git.exe' @('-C',$path,'sparse-checkout','init','--cone')
            Invoke-Checked 'git.exe' @('-C',$path,'sparse-checkout','set',$SparsePath)
        }
        Invoke-Checked 'git.exe' @('-C',$path,'fetch','--depth','1','--filter=blob:none','origin',$Commit)
        Invoke-Checked 'git.exe' @('-C',$path,'checkout','--detach','FETCH_HEAD')
    }
    $actual=(& git.exe -C $path rev-parse HEAD) -join ''
    if ($LASTEXITCODE -ne 0 -or $actual.Trim() -ne $Commit) { throw "Unexpected $Name revision. Expected $Commit. No existing checkout was reset." }
    $dirty=@(& git.exe -C $path status --porcelain --untracked-files=no)
    if ($dirty.Count) { throw "Tracked source changes found in $Name; no files were overwritten." }
    return $path
}

$venvPython=Join-Path $PSScriptRoot 'runtime/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) {
    if (-not $PythonExe) {
        $launcher=Get-Command py.exe -ErrorAction SilentlyContinue
        if ($launcher) {
            $candidate=(& $launcher.Source -3.12 -c 'import sys; print(sys.executable)' 2>$null) -join ''
            if ($LASTEXITCODE -eq 0) { $PythonExe=$candidate.Trim() }
        }
        if (-not $PythonExe) {
            $candidate=Get-Command python.exe -ErrorAction SilentlyContinue
            if ($candidate) { $PythonExe=$candidate.Source }
        }
    }
    if (-not $PythonExe) { throw 'Install Python 3.12 x64 first: https://www.python.org/downloads/release/python-31210/' }
    Invoke-Checked $PythonExe @('-c','import sys; assert sys.version_info[:2]==(3,12) and sys.maxsize>2**32')
    Invoke-Checked $PythonExe @('-m','venv',(Join-Path $PSScriptRoot 'runtime'))
}
Invoke-Checked $venvPython @('-c','import sys; assert sys.version_info[:2]==(3,12) and sys.maxsize>2**32')
Write-Output 'Installing fixed Python dependencies (first setup needs internet and several GB of disk space)...'
Invoke-Checked $venvPython @('-m','pip','install','--disable-pip-version-check','--only-binary=:all:','--index-url','https://pypi.org/simple','-r',(Join-Path $PSScriptRoot 'runtime-requirements.txt'))

$backend=Join-Path $repoRoot 'deeptector-backend'
if (-not (Test-Path -LiteralPath "$backend/app/main.py")) { throw 'Clone the full Service branch, not just the exhibition folder.' }
Copy-SourceTree "$backend/app" "$PSScriptRoot/backend/app"
Copy-SourceTree "$backend/migrations" "$PSScriptRoot/backend/migrations"
$model=Get-TeamCheckout 'AI-Generated-Media-Detection' 'https://github.com/KWU-2026-Capstone-Team/AI-Generated-Media-Detection.git' '4d2582f1eb9af83b86bcd8ffee96c5670af29e0b' 'deepfake_detector'
$modelSource=Join-Path $model 'deepfake_detector'
Copy-SourceTree "$modelSource/models" "$PSScriptRoot/model/models"
Copy-Item -LiteralPath "$modelSource/infer.py","$modelSource/xai.py","$modelSource/README.md" -Destination "$PSScriptRoot/model"
$game=Get-TeamCheckout 'deepGame-reference' 'https://github.com/KWU-2026-Capstone-Team/deepGame.git' '85d18621a8d82e7299036aa8725b150932b1a617'
New-Item -ItemType Directory -Force -Path "$PSScriptRoot/game-reference/src/data","$PSScriptRoot/game-reference/public/videos","$PSScriptRoot/android/platform-tools","$PSScriptRoot/third-party" | Out-Null
Copy-Item -LiteralPath "$game/src/data/videos.json" -Destination "$PSScriptRoot/game-reference/src/data"
Copy-Item -Path "$game/public/videos/*.mp4" -Destination "$PSScriptRoot/game-reference/public/videos"

if ($AdbDirectory) {
    foreach($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt')) {
        Copy-Item -LiteralPath (Join-Path $AdbDirectory $name) -Destination "$PSScriptRoot/android/platform-tools"
    }
} elseif (-not (Test-Path -LiteralPath "$PSScriptRoot/android/platform-tools/adb.exe")) {
    $toolsZip=Join-Path $downloadRoot 'platform-tools-windows.zip'
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $toolsZip -UseBasicParsing
    Expand-Archive -LiteralPath $toolsZip -DestinationPath "$downloadRoot/android-tools" -Force
    Copy-SourceTree "$downloadRoot/android-tools/platform-tools" "$PSScriptRoot/android/platform-tools"
}
$vcInstaller=Join-Path $PSScriptRoot 'third-party/VC_redist.x64.exe'
if (-not (Test-Path -LiteralPath $vcInstaller)) {
    Invoke-WebRequest 'https://aka.ms/vs/17/release/vc_redist.x64.exe' -OutFile $vcInstaller -UseBasicParsing
}
$signature=Get-AuthenticodeSignature -LiteralPath $vcInstaller
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Microsoft Corporation*') { throw 'Microsoft runtime installer signature validation failed; do not execute it.' }
$expectedApk=(Get-Content -Raw -LiteralPath "$PSScriptRoot/android/VeriVid-exhibition-debug.apk.sha256").Trim().Split(' ')[0]
if ((Get-FileHash -Algorithm SHA256 -LiteralPath "$PSScriptRoot/android/VeriVid-exhibition-debug.apk").Hash -ine $expectedApk) { throw 'APK checksum mismatch; fetch the branch again.' }
. "$PSScriptRoot/Exhibition.Common.ps1"
Set-ExhibitionEnvironment
Invoke-Checked $script:Python @("$PSScriptRoot/tools/prepare.py")
Write-Output 'SETUP COMPLETE. Next run Start-Exhibition.cmd, then Connect-Android.cmd.'
Write-Output 'No server has been started by setup. Do not upload generated model/game/runtime files.'
