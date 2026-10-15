@echo off
rem Puts a verified calendar backup back as the live calendar (ADR-027). Run as Administrator.
rem Usage: restore-calendar.cmd [latest | <snapshot file>] [data folder]
rem Defaults: the newest verified snapshot in backups\calendar; data folder %ProgramData%\Benbow.
rem The journal is not touched. Nothing is deleted: the replaced calendar moves to <data folder>\db\replaced-<time>.
rem Paired devices notice the restore and re-upload the calendar records the snapshot lacks.
setlocal
set "WHICH=%~1"
if "%WHICH%"=="" set "WHICH=latest"
set "DATA=%~2"
if "%DATA%"=="" set "DATA=%ProgramData%\Benbow"

net session >nul 2>&1
if errorlevel 1 (
  echo Run this script as Administrator.
  exit /b 1
)
set "SVC=%ProgramFiles%\Benbow Server\benbow-server-service.exe"
set "SERVICE=0"
sc query BenbowServer | find "RUNNING" >nul 2>&1
if not errorlevel 1 (
  set "SERVICE=1"
  echo Stopping Benbow Server...
  "%SVC%" stop
  powershell -NoProfile -Command "(Get-Service BenbowServer).WaitForStatus('Stopped', '00:01:00'); Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '%ProgramFiles%\Benbow Server\*' } | Wait-Process -Timeout 60 -ErrorAction SilentlyContinue"
)
"%~dp0runtime\bin\java.exe" -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -cp "%~dp0lib\*" app.benbow.server.MainKt --data "%DATA%" --restore-calendar "%WHICH%"
set "RESULT=%ERRORLEVEL%"
if "%SERVICE%"=="1" "%SVC%" start
if not "%RESULT%"=="0" (
  echo Restore failed; the calendar was not changed. See %DATA%\logs.
  exit /b 1
)
echo Restore done. Check https://localhost:8765
endlocal
