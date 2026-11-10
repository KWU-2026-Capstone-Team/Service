@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup-Exhibition.ps1" %*
if errorlevel 1 echo SETUP FAILED. Read the error above. Existing servers and records were not removed.
pause
