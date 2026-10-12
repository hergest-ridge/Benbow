@echo off
rem Stops and removes the Benbow Server service. The data folder is NOT touched. Run as Administrator.
setlocal
set "DEST=%ProgramFiles%\Benbow Server"
net session >nul 2>&1
if errorlevel 1 (
  echo Run this script as Administrator.
  exit /b 1
)
set "WRAPPER=%DEST%\benbow-server-service.exe"
if not exist "%WRAPPER%" set "WRAPPER=%~dp0benbow-server-service.exe"
"%WRAPPER%" stop
"%WRAPPER%" uninstall
netsh advfirewall firewall delete rule name="Benbow Server" >nul
echo Benbow Server service removed. Your data folder was left as is.
echo Program files remain in "%DEST%" and can be deleted.
endlocal
