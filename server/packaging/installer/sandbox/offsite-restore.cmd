@echo off
rem Runs inside Windows Sandbox (offsite-restore.wsb): the server stood up again from the copy outside the house alone
rem (ADR-043, E5). C:\pkg is a server package, C:\in holds setup-new.exe and code.txt (the copy's key file), C:\copy the
rem copy (a scratch copy of it: the restored server goes on writing there), C:\out the results.
setlocal EnableDelayedExpansion
set OUT=C:\out
set LOG=%OUT%\results.txt
set DATA=C:\ProgramData\Benbow
echo started %date% %time% > %LOG%

echo. >> %LOG%
echo ==== 1. a data folder from the copy, with the code's file >> %LOG%
call C:\pkg\restore-from-offsite.cmd C:\copy %DATA% --key-file C:\in\code.txt >> %LOG% 2>&1
echo restore exit !errorlevel! >> %LOG%
dir /b %DATA% >> %LOG%
findstr /b "backup.offsite backup.mirror media.root" %DATA%\benbow.properties >> %LOG%

echo. >> %LOG%
echo ==== 2. a wrong code changes nothing >> %LOG%
echo AGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ> %OUT%\wrong.txt
call C:\pkg\restore-from-offsite.cmd C:\copy C:\Other --key-file %OUT%\wrong.txt >> %LOG% 2>&1
echo restore exit !errorlevel! >> %LOG%
if exist C:\Other\db (echo SOMETHING WAS WRITTEN >> %LOG%) else (echo nothing written >> %LOG%)

echo. >> %LOG%
echo ==== 3. setup.exe goes on with that data folder >> %LOG%
C:\in\setup-new.exe /VERYSILENT /SUPPRESSMSGBOXES /DATADIR=%DATA% /LOG=%OUT%\setup.log
echo setup exit !errorlevel! >> %LOG%
rem The port the copy's settings carry (a test server's may not be 8765).
for /f "tokens=2 delims==" %%p in ('findstr /b "port=" %DATA%\benbow.properties') do set PORT=%%p
echo port !PORT! >> %LOG%
curl -sk https://localhost:!PORT!/api/v1/info >> %LOG% 2>&1
echo. >> %LOG%

echo. >> %LOG%
echo ==== 4. its first pass writes the same copy again >> %LOG%
for /l %%i in (1,1,150) do (
  if exist %DATA%\backups\offsite.json goto passed
  rem timeout needs a console the logon command doesn't have; ping waits as well.
  ping -n 3 127.0.0.1 > nul
)
echo NO PASS WITHIN 5 MINUTES >> %LOG%
:passed
ping -n 6 127.0.0.1 > nul
type %DATA%\backups\offsite.json >> %LOG% 2>&1
echo. >> %LOG%

echo finished %date% %time% >> %LOG%
echo done> %OUT%\done.txt
shutdown /s /t 10
