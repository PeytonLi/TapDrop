#ifndef AppVersion
  #error AppVersion must be supplied with /DAppVersion
#endif
#ifndef PublishDir
  #error PublishDir must be supplied with /DPublishDir
#endif

[Setup]
AppId={{55407297-E375-469A-955B-C82331D2B28E}
AppName=TapDrop
AppVersion={#AppVersion}
VersionInfoVersion={#AppVersion}
AppPublisher=Peyton Li
AppPublisherURL=https://github.com/PeytonLi/TapDrop
PrivilegesRequired=lowest
DefaultDirName={autopf}\TapDrop
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
SetupIconFile=..\TapDrop.Tray\TapDrop.ico
UninstallDisplayIcon={app}\TapDrop.Tray.exe
UninstallDisplayName=TapDrop
OutputBaseFilename=TapDrop-Setup-{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
AppMutex=Local\TapDrop.Tray
MinVersion=10.0

[Files]
Source: "{#PublishDir}\TapDrop.Tray.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: ignoreversion; Excludes: "*.pdb,TapDrop.Tray.exe"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"; Flags: unchecked

[Icons]
Name: "{autoprograms}\TapDrop"; Filename: "{app}\TapDrop.Tray.exe"
Name: "{autodesktop}\TapDrop"; Filename: "{app}\TapDrop.Tray.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\TapDrop.Tray.exe"; Description: "Launch TapDrop"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: files; Name: "{usersendto}\TapDrop (phone).lnk"

[Code]
var
  WasRunning: Boolean;

function WaitForTapDropExit(TimeoutMs: Integer): Boolean;
var
  Elapsed: Integer;
begin
  Elapsed := 0;
  while (Elapsed < TimeoutMs) and CheckForMutexes('Local\TapDrop.Tray') do
  begin
    Sleep(250);
    Elapsed := Elapsed + 250;
  end;
  Result := not CheckForMutexes('Local\TapDrop.Tray');
end;

function CloseRunningTapDrop(ExePath: String): Boolean;
var
  ResultCode: Integer;
begin
  Result := CheckForMutexes('Local\TapDrop.Tray');
  if not Result then
    Exit;

  if FileExists(ExePath) then
    Exec(ExePath, '--quit', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

  if WaitForTapDropExit(5000) then
    Exit;

  Exec('taskkill.exe', '/F /IM TapDrop.Tray.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  WaitForTapDropExit(3000);
end;

function InitializeSetup(): Boolean;
var
  ExistingInstallDir: String;
begin
  ExistingInstallDir := ExpandConstant('{localappdata}\Programs\TapDrop\');
  if not RegQueryStringValue(
    HKCU,
    'Software\Microsoft\Windows\CurrentVersion\Uninstall\{55407297-E375-469A-955B-C82331D2B28E}_is1',
    'InstallLocation',
    ExistingInstallDir) then
    ExistingInstallDir := ExpandConstant('{localappdata}\Programs\TapDrop\');
  WasRunning := CloseRunningTapDrop(AddBackslash(ExistingInstallDir) + 'TapDrop.Tray.exe');
  Result := True;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if (CurStep = ssPostInstall) and WizardSilent and WasRunning then
    ExecAsOriginalUser(
      ExpandConstant('{app}\TapDrop.Tray.exe'),
      '',
      '',
      SW_SHOWNORMAL,
      ewNoWait,
      ResultCode);
end;

function InitializeUninstall(): Boolean;
begin
  CloseRunningTapDrop(ExpandConstant('{app}\TapDrop.Tray.exe'));
  Result := True;
end;

function ShouldDeleteUserData(): Boolean;
var
  Index: Integer;
begin
  Result := False;
  if not UninstallSilent then
    Result := MsgBox(
      'Also delete your TapDrop settings, pairing key and log in ' +
      ExpandConstant('{userappdata}\TapDrop') + '?',
      mbConfirmation,
      MB_YESNO or MB_DEFBUTTON2) = IDYES
  else
    for Index := 1 to ParamCount do
      if CompareText(ParamStr(Index), '/DELETEUSERDATA') = 0 then
      begin
        Result := True;
        Exit;
      end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  SendToShortcut: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'TapDrop');
    SendToShortcut := ExpandConstant('{usersendto}\TapDrop (phone).lnk');
    if FileExists(SendToShortcut) then
      DeleteFile(SendToShortcut);
    if ShouldDeleteUserData then
      DelTree(ExpandConstant('{userappdata}\TapDrop'), True, True, True);
  end;
end;
