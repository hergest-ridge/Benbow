@echo off
rem Runs inside Windows Sandbox (benbow-installer.wsb): the installer's ways through, one after another (ADR-042, I2).
rem C:\in holds setup-new.exe and setup-old.exe (an older AppVersion), C:\pkg an older package folder, C:\out the results.
setlocal EnableDelayedExpansion
set OUT=C:\out
set LOG=%OUT%\results.txt
set APP=C:\Program Files\Benbow Server
set KEY=HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{6E1B8F0A-3C3D-4F8E-9B9A-2F1A7C0B5E21}_is1
echo started %date% %time% > %LOG%

echo. >> %LOG%
echo ==== 1. an older server installed by the scripts, as on the owner's PC >> %LOG%
call C:\pkg\install-service.cmd >> %LOG% 2>&1
call :wait
call :info

echo. >> %LOG%
echo ==== 2. the new setup over it >> %LOG%
C:\in\setup-new.exe /VERYSILENT /SUPPRESSMSGBOXES /LOG=%OUT%\2-update.log
echo setup exit !errorlevel! >> %LOG%
call :info
type "%APP%\version.txt" >> %LOG%
echo. >> %LOG%
reg query "%KEY%" /v DisplayVersion >> %LOG% 2>&1
findstr /c:"--data" "%APP%\benbow-server-service.xml" >> %LOG%
dir /b "%ProgramData%\Microsoft\Windows\Start Menu\Programs\Benbow*" >> %LOG% 2>&1

echo. >> %LOG%
echo ==== 3. an older setup is refused >> %LOG%
C:\in\setup-old.exe /VERYSILENT /SUPPRESSMSGBOXES /LOG=%OUT%\3-downgrade.log
echo setup exit !errorlevel! >> %LOG%
call :info

echo. >> %LOG%
echo ==== 4. removal keeps the data >> %LOG%
"%APP%\unins000.exe" /VERYSILENT /SUPPRESSMSGBOXES /LOG=%OUT%\4-uninstall.log
echo uninstall exit !errorlevel! >> %LOG%
call :gone
sc query BenbowServer >> %LOG% 2>&1
netsh advfirewall firewall show rule name="Benbow Server" >> %LOG% 2>&1
if exist "%APP%" (echo program folder STILL THERE >> %LOG% & dir /a /s "%APP%" >> %LOG% 2>&1) else (echo program folder gone >> %LOG%)
if exist "%ProgramData%\Benbow\benbow.properties" (echo data folder kept >> %LOG%) else (echo DATA FOLDER GONE >> %LOG%)
reg query "%KEY%" > nul 2>&1 && (echo UNINSTALL ENTRY STILL THERE >> %LOG%) || (echo uninstall entry gone >> %LOG%)
if exist "%ProgramData%\Microsoft\Windows\Start Menu\Programs\Benbow*" (echo START MENU LINK STILL THERE >> %LOG%) else (echo start menu link gone >> %LOG%)

echo. >> %LOG%
echo ==== 5. a fresh install into a chosen folder >> %LOG%
C:\in\setup-new.exe /VERYSILENT /SUPPRESSMSGBOXES /DATADIR=C:\BenbowData /LOG=%OUT%\5-fresh.log
echo setup exit !errorlevel! >> %LOG%
call :info
netsh advfirewall firewall show rule name="Benbow Server" | findstr /i "LocalPort Profiles Enabled" >> %LOG%
dir /b C:\BenbowData >> %LOG%
"%APP%\unins000.exe" /VERYSILENT /SUPPRESSMSGBOXES /LOG=%OUT%\5-uninstall.log
call :gone

echo. >> %LOG%
echo ==== 6. installed again over the first data folder: the same store >> %LOG%
C:\in\setup-new.exe /VERYSILENT /SUPPRESSMSGBOXES /DATADIR=C:\ProgramData\Benbow /LOG=%OUT%\6-again.log
echo setup exit !errorlevel! >> %LOG%
call :info
"%APP%\unins000.exe" /VERYSILENT /SUPPRESSMSGBOXES /LOG=%OUT%\6-uninstall.log
call :gone

echo. >> %LOG%
echo ==== 7. a folder of other things is refused >> %LOG%
mkdir C:\Other
echo x> C:\Other\a.txt
C:\in\setup-new.exe /VERYSILENT /SUPPRESSMSGBOXES /DATADIR=C:\Other /LOG=%OUT%\7-other.log
echo setup exit !errorlevel! >> %LOG%
sc query BenbowServer >> %LOG% 2>&1
dir /b C:\Other >> %LOG%

echo. >> %LOG%
echo finished %date% %time% >> %LOG%
echo done> %OUT%\done.txt
shutdown /s /t 10
exit /b

:wait
for /l %%i in (1,1,90) do (
  curl -sk -o nul https://localhost:8765/api/v1/info && exit /b
  timeout /t 1 /nobreak > nul
)
echo SERVER DID NOT ANSWER >> %LOG%
exit /b

:gone
for /l %%i in (1,1,60) do (
  if not exist "%APP%" exit /b
  timeout /t 1 /nobreak > nul
)
exit /b

:info
curl -sk https://localhost:8765/api/v1/info >> %LOG% 2>&1 || echo NO ANSWER >> %LOG%
echo. >> %LOG%
sc query BenbowServer | findstr STATE >> %LOG% 2>&1
exit /b
