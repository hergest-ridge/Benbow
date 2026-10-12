@echo off
rem Stops and removes the Benbow Server service. Data is NOT touched. Run as Administrator.
setlocal
set "BASE=%~dp0"
net session >nul 2>&1
if errorlevel 1 (
  echo Run this script as Administrator.
  exit /b 1
)
"%BASE%benbow-server-service.exe" stop
"%BASE%benbow-server-service.exe" uninstall
netsh advfirewall firewall delete rule name="Benbow Server" >nul
echo Benbow Server service removed. Your data folder was left as is.
endlocal
