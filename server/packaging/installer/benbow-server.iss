; Benbow Server for Windows as one setup.exe in Russian (ADR-042, 7.19 I2). Built by `gradlew :server:installer`
; from the package `packageServer` makes; it does what install-service.cmd, update-service.cmd and
; uninstall-service.cmd do, in the same places: the program in "%ProgramFiles%\Benbow Server", the service
; BenbowServer, the firewall rule "Benbow Server". The data folder is chosen once and never touched again: not on
; an update, not on removal.
; Silent use (tests, a friend's helper): setup.exe /VERYSILENT /SUPPRESSMSGBOXES [/DATADIR="D:\Benbow"]

#ifndef AppVersion
  #error Pass /DAppVersion=<benbow.version> /DSourceDir=<package folder> /DOutputDir=<folder>
#endif

[Setup]
AppId={{6E1B8F0A-3C3D-4F8E-9B9A-2F1A7C0B5E21}
AppName=Benbow Server
AppVersion={#AppVersion}
AppVerName=Benbow Server {#AppVersion}
AppPublisher=Benbow
VersionInfoVersion={#AppVersion}
VersionInfoDescription=Benbow Server setup
; The scripts and the service config assume this folder; it is not asked.
DefaultDirName={autopf}\Benbow Server
DisableDirPage=yes
UsePreviousAppDir=no
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir={#OutputDir}
OutputBaseFilename=benbow-server-setup-{#AppVersion}
SetupIconFile=benbow.ico
UninstallDisplayIcon={app}\benbow.ico
UninstallDisplayName=Benbow — сервер
WizardStyle=modern
Compression=lzma2/ultra64
SolidCompression=yes
LZMAUseSeparateProcess=yes
; The service is stopped and started by the code below, not by the Restart Manager.
CloseApplications=no
RestartApplications=no
SetupLogging=yes

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Excludes: "benbow-server-service.xml,service-logs"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "benbow.ico"; DestDir: "{app}"; Flags: ignoreversion

; What a newer package no longer has must not stay on the class path (update-service.cmd mirrors for the same reason).
[InstallDelete]
Type: filesandordirs; Name: "{app}\lib"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\web"
Type: filesandordirs; Name: "{app}\android"

[INI]
Filename: "{app}\Benbow.url"; Section: "InternetShortcut"; Key: "URL"; String: "https://localhost:{code:Port}/"

[Icons]
Name: "{autoprograms}\Benbow — сервер"; Filename: "{app}\Benbow.url"; IconFilename: "{app}\benbow.ico"

[UninstallDelete]
Type: files; Name: "{app}\Benbow.url"
Type: files; Name: "{app}\benbow-server-service.xml"
Type: filesandordirs; Name: "{app}\service-logs"
; A folder install-service.cmd made is not one setup made, so it would stay behind empty.
Type: dirifempty; Name: "{app}"

[Run]
Filename: "https://localhost:{code:Port}/#places"; Description: "Открыть страницу сервера"; Flags: postinstall shellexec nowait skipifsilent; Check: Started

[Code]
const
  ServiceName = 'BenbowServer';
  Wrapper = 'benbow-server-service.exe';

var
  DataPage: TInputDirWizardPage;
  Updating: Boolean;
  DataDir: String;
  OldVersion: String;
  StartFailed: Boolean;

function GetDriveType(lpRootPathName: String): Cardinal;
  external 'GetDriveTypeW@kernel32.dll stdcall';

{ Runs a program hidden and waits; -1 when it couldn't be run. }
function RunHidden(const FileName, Params: String): Integer;
var
  Code: Integer;
begin
  Log('Run: ' + FileName + ' ' + Params);
  if Exec(FileName, Params, '', SW_HIDE, ewWaitUntilTerminated, Code) then Result := Code else Result := -1;
  Log('  exit ' + IntToStr(Result));
end;

function PowerShell(const Script: String): Integer;
begin
  Result := RunHidden(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'), '-NoProfile -NonInteractive -Command "' + Script + '"');
end;

function ProgramDir: String;
begin
  Result := ExpandConstant('{commonpf64}\Benbow Server');
end;

function ServiceExists: Boolean;
begin
  Result := RunHidden(ExpandConstant('{sys}\sc.exe'), 'query ' + ServiceName) = 0;
end;

{ The service must run from the folder this setup installs into (older installs ran from the package folder). }
function ServiceIsOurs: Boolean;
var
  Path: String;
begin
  Result := False;
  if RegQueryStringValue(HKLM, 'SYSTEM\CurrentControlSet\Services\' + ServiceName, 'ImagePath', Path) then begin
    StringChangeEx(Path, '"', '', True);
    Result := CompareText(Trim(Path), ProgramDir + '\' + Wrapper) = 0;
  end;
end;

{ The data folder of the installed service, from its config: --data "<folder>". }
function InstalledDataDir: String;
var
  Lines: TArrayOfString;
  I, P: Integer;
  Line: String;
begin
  Result := '';
  if not LoadStringsFromFile(ProgramDir + '\benbow-server-service.xml', Lines) then Exit;
  for I := 0 to GetArrayLength(Lines) - 1 do begin
    Line := Lines[I];
    P := Pos('--data "', Line);
    if P > 0 then begin
      Line := Copy(Line, P + 8, Length(Line));
      P := Pos('"', Line);
      if P > 0 then Result := Copy(Line, 1, P - 1);
      Exit;
    end;
  end;
end;

{ 0.13.3 -> 13003, like the app's versionCode; 0 when unknown. }
function VersionKey(const V: String): Integer;
var
  S: String;
  P, Part, Key: Integer;
begin
  S := Trim(V) + '.';
  Key := 0;
  for Part := 1 to 3 do begin
    P := Pos('.', S);
    if P = 0 then begin
      Result := 0;
      Exit;
    end;
    Key := Key * 1000 + StrToIntDef(Copy(S, 1, P - 1), 0);
    S := Copy(S, P + 1, Length(S));
  end;
  Result := Key;
end;

function InstalledVersion: String;
var
  Text: AnsiString;
begin
  Result := '';
  if LoadStringFromFile(ProgramDir + '\version.txt', Text) then Result := Trim(String(Text));
end;

{ The server's port: `port=` in the data folder's benbow.properties when it has one, else 8765. }
function Port(Param: String): String;
var
  Lines: TArrayOfString;
  I: Integer;
begin
  Result := '8765';
  if (DataDir <> '') and LoadStringsFromFile(AddBackslash(DataDir) + 'benbow.properties', Lines) then
    for I := 0 to GetArrayLength(Lines) - 1 do
      if Pos('port=', Trim(Lines[I])) = 1 then Result := Trim(Copy(Trim(Lines[I]), 6, 10));
end;

function Started: Boolean;
begin
  Result := not StartFailed;
end;

function InitializeSetup: Boolean;
begin
  Result := True;
  Updating := ServiceExists;
  if not Updating then Exit;
  if not ServiceIsOurs then begin
    SuppressibleMsgBox('Служба Benbow Server уже установлена, но из другой папки. Удалите её через uninstall-service.cmd ' +
      'из той папки (данные останутся) и запустите установку снова.', mbError, MB_OK, IDOK);
    Result := False;
    Exit;
  end;
  DataDir := InstalledDataDir;
  if DataDir = '' then begin
    SuppressibleMsgBox('Служба Benbow Server установлена, но в её настройках (' + ProgramDir + '\benbow-server-service.xml) ' +
      'не нашлась папка данных. Установка остановлена, чтобы ничего не перепутать.', mbError, MB_OK, IDOK);
    Result := False;
    Exit;
  end;
  OldVersion := InstalledVersion;
  if VersionKey(OldVersion) > VersionKey('{#AppVersion}') then begin
    SuppressibleMsgBox('Уже установлена более новая версия ' + OldVersion + '. Сервер версии {#AppVersion} не откроет ' +
      'её базу; установка остановлена.', mbError, MB_OK, IDOK);
    Result := False;
  end;
end;

procedure InitializeWizard;
begin
  DataPage := CreateInputDirPage(wpSelectDir, 'Папка данных',
    'Где сервер хранит базу, её снимки, настройки и сертификат',
    'Выберите пустую или новую папку на диске этого компьютера. Медиафайлы и второй диск для копий выбираются потом, ' +
    'на странице сервера «Где хранить». Если в папке уже есть данные Benbow (сервер удаляли), он продолжит с ними.',
    False, 'Benbow');
  DataPage.Add('');
  DataPage.Values[0] := ExpandConstant('{param:DATADIR|{commonappdata}\Benbow}');
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := (PageID = DataPage.ID) and Updating;
end;

function IsEmptyDir(const Dir: String): Boolean;
var
  Rec: TFindRec;
begin
  Result := True;
  if FindFirst(AddBackslash(Dir) + '*', Rec) then
    try
      repeat
        if (Rec.Name <> '.') and (Rec.Name <> '..') then begin
          Result := False;
          Exit;
        end;
      until not FindNext(Rec);
    finally
      FindClose(Rec);
    end;
end;

{ Why [Dir] can't be the data folder, or '' when it can. }
function DataDirProblem(const Dir: String): String;
begin
  Result := '';
  if (Length(Dir) < 4) or (Copy(Dir, 2, 2) <> ':\') then
    Result := 'Нужен полный путь к папке на диске этого компьютера, например C:\ProgramData\Benbow.'
  else if (Pos('&', Dir) > 0) or (Pos('<', Dir) > 0) or (Pos('>', Dir) > 0) or (Pos('"', Dir) > 0) then
    Result := 'В пути не должно быть знаков & < > ".'
  else if GetDriveType(Copy(Dir, 1, 3)) <> 3 then
    Result := 'Нужен постоянный диск этого компьютера: не съёмный и не сетевой.'
  else if Pos(Lowercase(ProgramDir) + '\', Lowercase(AddBackslash(Dir))) = 1 then
    Result := 'Данные нельзя держать в папке программы: её заменяет каждое обновление.'
  else if DirExists(Dir) and not IsEmptyDir(Dir) and not FileExists(AddBackslash(Dir) + 'benbow.properties') then
    Result := 'В папке уже что-то лежит, и это не данные Benbow. Выберите пустую папку или впишите новую.';
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  Problem: String;
begin
  Result := True;
  if CurPageID <> DataPage.ID then Exit;
  DataDir := RemoveBackslashUnlessRoot(Trim(DataPage.Values[0]));
  Problem := DataDirProblem(DataDir);
  if Problem <> '' then begin
    SuppressibleMsgBox(Problem, mbError, MB_OK, IDOK);
    Result := False;
  end else if FileExists(AddBackslash(DataDir) + 'benbow.properties') then
    Result := SuppressibleMsgBox('В этой папке уже есть данные Benbow. Сервер продолжит с ними. Продолжить?',
      mbConfirmation, MB_YESNO, IDYES) = IDYES;
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo, MemoComponentsInfo,
  MemoGroupInfo, MemoTasksInfo: String): String;
begin
  Result := 'Программа:' + NewLine + Space + ProgramDir + NewLine + NewLine + 'Данные:' + NewLine + Space + DataDir;
  if Updating then begin
    Result := Result + NewLine + NewLine + 'Обновление';
    if OldVersion <> '' then Result := Result + ' с версии ' + OldVersion;
    Result := Result + ' до {#AppVersion}:' + NewLine + Space + 'служба остановится на время установки и запустится снова;' +
      NewLine + Space + 'перед первым запуском новой версии сервер сам сделает проверенную копию базы.';
  end;
end;

{ Stops the service and waits until Java has let go of the program's files (as update-service.cmd does). }
function StopService: Boolean;
begin
  RunHidden(ProgramDir + '\' + Wrapper, 'stop');
  Result := PowerShell('$s = Get-Service ' + ServiceName + ' -ErrorAction SilentlyContinue; if ($s) { try { $s.WaitForStatus(''Stopped'', ''00:01:00'') } catch { exit 1 } }; ' +
    'Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like ''' + ProgramDir + '\*'' } | Wait-Process -Timeout 60 -ErrorAction SilentlyContinue; exit 0') = 0;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if Updating and not StopService then
    Result := 'Служба Benbow Server не остановилась за минуту. Ничего не изменено; попробуйте ещё раз или перезагрузите компьютер.';
end;

{ benbow-server-service.xml from the package's template, with the data folder (also on updates: a new template wins). }
function WriteServiceConfig: Boolean;
var
  Lines: TArrayOfString;
  I: Integer;
  Line: String;
begin
  Result := LoadStringsFromFile(ProgramDir + '\benbow-server-service.xml.template', Lines);
  if not Result then Exit;
  for I := 0 to GetArrayLength(Lines) - 1 do begin
    Line := Lines[I];
    StringChangeEx(Line, '@DATA_DIR@', DataDir, True);
    Lines[I] := Line;
  end;
  Result := SaveStringsToUTF8File(ProgramDir + '\benbow-server-service.xml', Lines, False);
end;

function StartService: Boolean;
begin
  RunHidden(ProgramDir + '\' + Wrapper, 'start');
  Result := PowerShell('try { (Get-Service ' + ServiceName + ').WaitForStatus(''Running'', ''00:01:00'') } catch { exit 1 }; ' +
    'for ($i = 0; $i -lt 60; $i++) { if (Get-NetTCPConnection -State Listen -LocalPort ' + Port('') + ' -ErrorAction SilentlyContinue) { exit 0 }; Start-Sleep 1 }; exit 2') = 0;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Netsh: String;
begin
  if CurStep <> ssPostInstall then Exit;
  if not WriteServiceConfig then begin
    StartFailed := True;
    SuppressibleMsgBox('Не удалось записать настройки службы (' + ProgramDir + '\benbow-server-service.xml).', mbError, MB_OK, IDOK);
    Exit;
  end;
  if not Updating then begin
    if not ForceDirectories(DataDir) then begin
      StartFailed := True;
      SuppressibleMsgBox('Не удалось создать папку данных ' + DataDir + '.', mbError, MB_OK, IDOK);
      Exit;
    end;
    if RunHidden(ProgramDir + '\' + Wrapper, 'install') <> 0 then begin
      StartFailed := True;
      SuppressibleMsgBox('Не удалось зарегистрировать службу Benbow Server.', mbError, MB_OK, IDOK);
      Exit;
    end;
    Netsh := ExpandConstant('{sys}\netsh.exe');
    if RunHidden(Netsh, 'advfirewall firewall show rule name="Benbow Server"') <> 0 then
      RunHidden(Netsh, 'advfirewall firewall add rule name="Benbow Server" dir=in action=allow protocol=TCP localport=' + Port('') + ' profile=private');
  end;
  if not StartService then begin
    StartFailed := True;
    SuppressibleMsgBox('Служба Benbow Server не запустилась. Что случилось, сказано в ' + AddBackslash(DataDir) + 'logs и ' +
      ProgramDir + '\service-logs.', mbError, MB_OK, IDOK);
  end;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if (CurPageID = wpFinished) and not StartFailed then
    WizardForm.FinishedLabel.Caption := 'Сервер Benbow {#AppVersion} работает. Данные: ' + DataDir + '.' + #13#10#13#10 +
      'Страница сервера: https://localhost:' + Port('') + ' (браузер один раз предупредит о сертификате). Начните с раздела ' +
      '«Где хранить»: там видно, куда положить медиафайлы и второй диск для копий.';
end;

function GetCustomSetupExitCode: Integer;
begin
  if StartFailed then Result := 8 else Result := 0;
end;

{ Removal: the service and the firewall rule go, the program folder goes, the data folder stays. }
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then begin
    DataDir := InstalledDataDir;
    StopService;
    RunHidden(ProgramDir + '\' + Wrapper, 'uninstall');
    RunHidden(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall delete rule name="Benbow Server"');
  end;
  if (CurUninstallStep = usPostUninstall) and (DataDir <> '') and not UninstallSilent then
    MsgBox('Сервер удалён. Данные остались в ' + DataDir + ': удалите эту папку сами, если они больше не нужны.',
      mbInformation, MB_OK);
end;
