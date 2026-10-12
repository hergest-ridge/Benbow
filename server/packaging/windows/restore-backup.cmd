@echo off
rem Puts a verified backup back as the live database. Run as Administrator.
rem Usage: restore-backup.cmd [latest | <snapshot file>] [data folder]
rem Defaults: the newest verified snapshot; data folder %ProgramData%\Benbow.
rem Nothing is deleted: the replaced database moves to <data folder>\db\replaced-<time>.
rem Paired devices notice the restore and re-upload what the snapshot lacks.
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
set "SVC=%ProgramFiles%\Benbow Serverenbow-server-service.exe"
set "SERVICE=0"
sc query BenbowServer | find "RUNNING" >nul 2>&1
if not errorlevel 1 (
  set "SERVICE=1"
  echo Stopping Benbow Server...
  "%SVC%" stop
)
"%~dp0runtime\bin\java.exe" -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -cp "%~dp0lib\*" app.benbow.server.MainKt --data "%DATA%" --restore "%WHICH%"
set "RESULT=%ERRORLEVEL%"
if "%SERVICE%"=="1" "%SVC%" start
if not "%RESULT%"=="0" (
  echo Restore failed; the database was not changed. See %DATA%\logs.
  exit /b 1
)
echo Restore done. Check https://localhost:8765
endlocal
