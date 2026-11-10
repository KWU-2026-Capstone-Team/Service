@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-Exhibition.ps1" %*
if errorlevel 1 echo FAILED. Read the message above; no other server was stopped.
pause
