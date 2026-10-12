@echo off
rem Installs Benbow Server as a Windows service. Run as Administrator.
rem Copies this package to "%ProgramFiles%\Benbow Server" and runs the service from there, so
rem rebuilding or cleaning the source folder never touches the running server.
rem Usage: install-service.cmd [data folder]   (default: %ProgramData%\Benbow)
setlocal
set "SRC=%~dp0"
set "DEST=%ProgramFiles%\Benbow Server"
set "DATA=%~1"
if "%DATA%"=="" set "DATA=%ProgramData%\Benbow"

net session >nul 2>&1
if errorlevel 1 (
  echo Run this script as Administrator.
  exit /b 1
)
sc query BenbowServer >nul 2>&1
if not errorlevel 1 (
  echo Benbow Server is already installed. Use update-service.cmd to update it.
  exit /b 1
)
if not exist "%SRC%benbow-server-service.exe" (
  echo WinSW is missing: put WinSW.NET461.exe into server\packaging\winsw and rebuild, see README.txt.
  exit /b 1
)

robocopy "%SRC%." "%DEST%" /MIR /XF benbow-server-service.xml /XD service-logs /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
  echo Copying to "%DEST%" failed.
  exit /b 1
)
powershell -NoProfile -Command "(Get-Content -Raw '%DEST%\benbow-server-service.xml.template').Replace('@DATA_DIR@', '%DATA%') | Set-Content -Encoding UTF8 '%DEST%\benbow-server-service.xml'" || exit /b 1
"%DEST%\benbow-server-service.exe" install || exit /b 1
netsh advfirewall firewall add rule name="Benbow Server" dir=in action=allow protocol=TCP localport=8765 profile=private >nul
"%DEST%\benbow-server-service.exe" start || exit /b 1

echo.
echo Benbow Server is installed in "%DEST%" and running.
echo Data folder: %DATA%
echo Status page: https://localhost:8765  (self-signed certificate: accept the browser warning once)
endlocal
