param([string]$Serial, [int]$Port = 8000, [switch]$SkipInstall)
. "$PSScriptRoot/Exhibition.Common.ps1"
Set-ExhibitionEnvironment
if (-not (Test-Path -LiteralPath $script:Adb)) { throw 'Bundled Android platform-tools are missing.' }
if (-not (Test-Path -LiteralPath $script:ProcessFile)) { throw 'Start this package server first.' }
$record=Get-Content -Raw -LiteralPath $script:ProcessFile | ConvertFrom-Json
if ($record.executable -ine $script:Python -or [int]$record.port -ne $Port) { throw 'Server record/port does not match this package.' }
$workerHealth=Invoke-RestMethod "http://127.0.0.1:$Port/health/worker" -TimeoutSec 3
if ($workerHealth.ready -ne $true -or $workerHealth.mode -ne 'real') { throw 'Start the REAL server first.' }
$deviceList = @(& $script:Adb devices)
if ($LASTEXITCODE -ne 0) { throw 'ADB could not start.' }
$devices = @($deviceList | Where-Object {$_ -match '^\S+\s+device$'} | ForEach-Object {($_ -split '\s+')[0]})
if (-not $Serial) {
    if ($devices.Count -eq 1) { $Serial=$devices[0] }
    elseif ($devices.Count -gt 1) {
        for($i=0;$i -lt $devices.Count;$i++) { Write-Output "$($i+1). $($devices[$i])" }
        $choice=Read-Host 'Device number'
        $number=0
        if(-not [int]::TryParse($choice,[ref]$number) -or $number -lt 1 -or $number -gt $devices.Count) { throw 'Invalid device number.' }
        $Serial=$devices[$number-1]
    } else { $deviceList | Out-Host; throw 'No authorized Android device. Start an emulator or enable USB debugging and accept the phone prompt.' }
}
if ($Serial -notin $devices) { throw 'Selected device is not authorized/online.' }
& $script:Adb -s $Serial reverse tcp:8000 "tcp:$Port"
if ($LASTEXITCODE -ne 0) { throw 'USB/emulator port forwarding failed.' }
if (-not $SkipInstall) {
    & $script:Adb -s $Serial install -r "$PSScriptRoot/android/VeriVid-exhibition-debug.apk"
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. Do not uninstall blindly: a different signing key may require team coordination to preserve records.' }
}
& $script:Adb -s $Serial shell am start -W -n com.verivid.app/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Output "READY: $Serial -> USB/ADB -> 127.0.0.1:$Port. Keep USB connected; rerun this script after reconnect/reboot."
Write-Output 'For a second device, run Connect-Android.cmd again and choose it.'
