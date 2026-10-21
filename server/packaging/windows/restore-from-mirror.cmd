@echo off
rem Builds a data folder on a new PC from the backup mirror (the second disk): the settings and the certificate, every
rem snapshot and the newest verified one as the live database, the calendar, the purge journal and the media, each file
rem checked. The mirror is only read. Run as Administrator, then install-service.cmd with the same data folder.
rem Usage: restore-from-mirror.cmd <mirror folder> [data folder] [media folder]
rem Defaults: data folder %ProgramData%\Benbow; media inside it.
setlocal
set "MIRROR=%~1"
set "DATA=%~2"
set "MEDIA=%~3"
if "%MIRROR%"=="" (
  echo Usage: restore-from-mirror.cmd ^<mirror folder^> [data folder] [media folder]
  exit /b 1
)
if "%DATA%"=="" set "DATA=%ProgramData%\Benbow"

net session >nul 2>&1
if errorlevel 1 (
  echo Run this script as Administrator.
  exit /b 1
)
sc query BenbowServer | find "RUNNING" >nul 2>&1
if not errorlevel 1 (
  echo Benbow Server is running on this PC. This script builds a new data folder; to put a backup back into the
  echo running server use restore-backup.cmd. Stop the old server for good before standing it up again from a mirror.
  exit /b 1
)
set "MEDIAARG="
if not "%MEDIA%"=="" set MEDIAARG=--media "%MEDIA%"
"%~dp0runtime\bin\java.exe" -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -cp "%~dp0lib\*" app.benbow.server.MainKt --data "%DATA%" --restore-from-mirror "%MIRROR%" %MEDIAARG%
if errorlevel 1 (
  echo Restore failed; the mirror was not changed. See the message above and %DATA%\logs.
  exit /b 1
)
echo.
echo Data folder %DATA% is ready. Next: install-service.cmd "%DATA%"
endlocal
