@echo off
rem Installs Benbow Server as a Windows service. Run as Administrator.
rem Usage: install-service.cmd [data folder]   (default: %ProgramData%\Benbow)
setlocal
set "BASE=%~dp0"
set "DATA=%~1"
if "%DATA%"=="" set "DATA=%ProgramData%\Benbow"

net session >nul 2>&1
if errorlevel 1 (
  echo Run this script as Administrator.
  exit /b 1
)
if not exist "%BASE%benbow-server-service.exe" (
  echo WinSW is missing: put WinSW.NET461.exe into server\packaging\winsw and rebuild, see README.txt.
  exit /b 1
)

powershell -NoProfile -Command "(Get-Content -Raw '%BASE%benbow-server-service.xml.template').Replace('@DATA_DIR@', '%DATA%') | Set-Content -Encoding UTF8 '%BASE%benbow-server-service.xml'" || exit /b 1
"%BASE%benbow-server-service.exe" install || exit /b 1
netsh advfirewall firewall add rule name="Benbow Server" dir=in action=allow protocol=TCP localport=8765 profile=private >nul
"%BASE%benbow-server-service.exe" start || exit /b 1

echo.
echo Benbow Server is installed and running.
echo Data folder: %DATA%
echo Status page: https://localhost:8765  (self-signed certificate: accept the browser warning once)
endlocal
