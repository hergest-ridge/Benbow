@echo off
rem Builds a data folder on a new PC from the copy outside the house (ADR-043), when nothing at home is left: the
rem settings and the certificate, the snapshots and the newest verified one as the live database, the calendar, the
rem purge journal and the media, each file opened with the key and checked. The copy is only read. It asks for the
rem recovery code or the copy's passphrase (or reads the code's file given with --key-file). Run as Administrator,
rem then install-service.cmd (or setup.exe) with the same data folder.
rem Usage: restore-from-offsite.cmd <copy folder> [data folder] [media folder] [--key-file <the code's file>]
rem Defaults: data folder %ProgramData%\Benbow; media inside it.
setlocal
set "COPY=%~1"
set "DATA=%~2"
set "MEDIA=%~3"
set "KEYARG="
if /I "%~2"=="--key-file" (set "DATA=" & set "MEDIA=" & set KEYARG=--key-file "%~3")
if /I "%~3"=="--key-file" (set "MEDIA=" & set KEYARG=--key-file "%~4")
if /I "%~4"=="--key-file" set KEYARG=--key-file "%~5"
if "%COPY%"=="" (
  echo Usage: restore-from-offsite.cmd ^<copy folder^> [data folder] [media folder] [--key-file ^<the code's file^>]
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
  echo running server use restore-backup.cmd. Stop the old server for good before standing it up again from a copy.
  exit /b 1
)
set "MEDIAARG="
if not "%MEDIA%"=="" set MEDIAARG=--media "%MEDIA%"
chcp 65001 >nul
"%~dp0runtime\bin\java.exe" -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -cp "%~dp0lib\*" app.benbow.server.MainKt --data "%DATA%" --restore-from-offsite "%COPY%" %MEDIAARG% %KEYARG%
if errorlevel 1 (
  echo Restore failed; the copy was not changed. See the message above and %DATA%\logs.
  exit /b 1
)
echo.
echo Data folder %DATA% is ready. Next: install-service.cmd "%DATA%" or setup.exe with this data folder.
endlocal
