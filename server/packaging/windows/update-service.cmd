@echo off
rem Updates the installed Benbow Server with this package. Run as Administrator.
rem The server backs up the database (verified) before its first start on a new version.
setlocal
set "SRC=%~dp0"
set "DEST=%ProgramFiles%\Benbow Server"

net session >nul 2>&1
if errorlevel 1 (
  echo Run this script as Administrator.
  exit /b 1
)
sc query BenbowServer >nul 2>&1
if errorlevel 1 (
  echo Benbow Server is not installed. Use install-service.cmd.
  exit /b 1
)
if /I "%SRC%"=="%DEST%\" (
  echo Run this script from the new package folder, not from the installed copy.
  exit /b 1
)
rem robocopy /MIR deletes what the source lacks: both ends must be what they claim to be.
if not exist "%SRC%lib\server.jar" (
  echo This folder is not a Benbow Server package ^(no lib\server.jar^). Run the script from inside the package.
  exit /b 1
)
if not exist "%DEST%\benbow-server-service.exe" (
  echo "%DEST%" does not look like an installed Benbow Server; nothing was changed.
  exit /b 1
)

echo Stopping Benbow Server...
"%DEST%\benbow-server-service.exe" stop
rem The wrapper returns before Java has exited; its files stay locked until then.
powershell -NoProfile -Command "(Get-Service BenbowServer).WaitForStatus('Stopped', '00:01:00'); Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '%DEST%\*' } | Wait-Process -Timeout 60 -ErrorAction SilentlyContinue"

echo Copying the new version...
robocopy "%SRC%." "%DEST%" /MIR /XF benbow-server-service.xml /XD service-logs /R:10 /W:3 /NFL /NDL /NJH /NJS /NP
if errorlevel 8 (
  echo Copying failed ^(see above^); starting the previous version again.
  "%DEST%\benbow-server-service.exe" start
  exit /b 1
)

echo Starting Benbow Server...
"%DEST%\benbow-server-service.exe" start
powershell -NoProfile -Command "try { (Get-Service BenbowServer).WaitForStatus('Running', '00:01:00') } catch { exit 1 }"
if errorlevel 1 (
  echo The service did not start. See %ProgramData%\Benbow\logs and "%DEST%\service-logs".
  exit /b 1
)
echo Benbow Server updated and running. Check https://localhost:8765
endlocal
