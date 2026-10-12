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

"%DEST%\benbow-server-service.exe" stop
robocopy "%SRC%." "%DEST%" /MIR /XF benbow-server-service.xml /XD service-logs /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
  echo Copying failed; starting the previous version again.
  "%DEST%\benbow-server-service.exe" start
  exit /b 1
)
"%DEST%\benbow-server-service.exe" start || exit /b 1
echo Benbow Server updated and running. Check https://localhost:8765
endlocal
