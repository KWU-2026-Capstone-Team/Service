param([switch]$Full)
. "$PSScriptRoot/Exhibition.Common.ps1"
Set-ExhibitionEnvironment
& $script:Python "$PSScriptRoot/tools/prepare.py" --check-only
if ($LASTEXITCODE -ne 0) { throw 'Runtime check failed. Read the error; DLL errors may require third-party/VC_redist.x64.exe.' }
if ($Full) {
    if (-not (Test-Path -LiteralPath "$PSScriptRoot/package-manifest.json")) { throw 'The full file manifest is provided only with the offline ZIP. GitHub setup verifies model/video integrity and the APK checksum separately.' }
    & $script:Python "$PSScriptRoot/tools/prepare.py" --verify-all
    if ($LASTEXITCODE -ne 0) { throw 'Package checksum verification failed.' }
}
Get-CimInstance Win32_OperatingSystem | Select-Object Caption,OSArchitecture,@{Name='RAM_GB';Expression={[math]::Round($_.TotalVisibleMemorySize/1MB,1)}},@{Name='Free_RAM_GB';Expression={[math]::Round($_.FreePhysicalMemory/1MB,1)}} | Format-List
Write-Output 'Package checks passed. This does NOT benchmark accuracy or guarantee target-laptop inference speed.'
