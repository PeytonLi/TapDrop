param(
    [Parameter(Mandatory = $true)]
    [string]$Installer,
    [string]$UpgradeInstaller
)

$ErrorActionPreference = 'Stop'

$installerPath = (Resolve-Path $Installer).Path
$upgradeInstallerPath = if ($UpgradeInstaller) { (Resolve-Path $UpgradeInstaller).Path } else { $null }
$verificationRoot = Join-Path ([IO.Path]::GetTempPath()) 'tapdrop-smoke'
New-Item -ItemType Directory -Path $verificationRoot -Force | Out-Null

$appDir = Join-Path $env:LOCALAPPDATA 'Programs\TapDrop'
$exePath = Join-Path $appDir 'TapDrop.Tray.exe'
$startMenuShortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\TapDrop.lnk'
$sendToShortcut = Join-Path $env:APPDATA 'Microsoft\Windows\SendTo\TapDrop (phone).lnk'
$runKeyPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$uninstallKeyName = '{55407297-E375-469A-955B-C82331D2B28E}_is1'
$uninstallKeyPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$uninstallKeyName"

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Get-SetupVersion([string]$Path) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($Path)
    if ($name -notmatch '^TapDrop-Setup-(?<Version>.+)$') {
        throw "Installer filename must be TapDrop-Setup-<version>.exe: $Path"
    }
    return $Matches.Version
}

function Get-ProductVersion {
    if (-not (Test-Path $exePath)) {
        throw "Installed executable is missing: $exePath"
    }
    return ([System.Diagnostics.FileVersionInfo]::GetVersionInfo($exePath).ProductVersion).Trim()
}

function Invoke-SilentInstaller([string]$Path, [string]$Operation) {
    $logPath = Join-Path $verificationRoot "$Operation-$(Get-SetupVersion $Path).log"
    $arguments = @(
        '/VERYSILENT',
        '/SUPPRESSMSGBOXES',
        '/CURRENTUSER',
        '/NORESTART',
        "/LOG=`"$logPath`""
    )
    $process = Start-Process -FilePath $Path -ArgumentList $arguments -Wait -PassThru
    Assert-Condition ($process.ExitCode -eq 0) "$Operation failed with exit code $($process.ExitCode). See $logPath"
    Write-Output "$Operation log: $logPath"
}

$expectedVersion = Get-SetupVersion $installerPath
Invoke-SilentInstaller $installerPath 'install'
Assert-Condition (Test-Path $exePath) "Installed executable was not created: $exePath"
Assert-Condition (Test-Path $startMenuShortcut) "Start Menu shortcut was not created: $startMenuShortcut"
Assert-Condition ((Get-ProductVersion) -eq $expectedVersion) "Installed ProductVersion does not match $expectedVersion."

if ($upgradeInstallerPath) {
    $previousVersion = Get-ProductVersion
    $expectedUpgradeVersion = Get-SetupVersion $upgradeInstallerPath
    Invoke-SilentInstaller $upgradeInstallerPath 'upgrade'
    $upgradedVersion = Get-ProductVersion
    Assert-Condition ($upgradedVersion -eq $expectedUpgradeVersion) "Upgraded ProductVersion does not match $expectedUpgradeVersion."
    Assert-Condition ($upgradedVersion -ne $previousVersion) "Upgrade did not change ProductVersion from $previousVersion."
    $uninstallEntries = @(
        Get-ChildItem 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall' -ErrorAction SilentlyContinue |
            Where-Object { $_.PSChildName -eq $uninstallKeyName }
    )
    Assert-Condition ($uninstallEntries.Count -eq 1) "Expected one TapDrop uninstall entry; found $($uninstallEntries.Count)."
}

$dataDir = Join-Path $env:APPDATA 'TapDrop'
$userDataExistedBeforeUninstall = Test-Path $dataDir
New-Item -Path $runKeyPath -Force | Out-Null
New-ItemProperty -Path $runKeyPath -Name TapDrop -Value "`"$exePath`"" -PropertyType String -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path $sendToShortcut) -Force | Out-Null
Set-Content -Path $sendToShortcut -Value 'smoke-test shortcut placeholder'

$uninstaller = Join-Path $appDir 'unins000.exe'
Assert-Condition (Test-Path $uninstaller) "Uninstaller was not created: $uninstaller"
$uninstallLog = Join-Path $verificationRoot 'uninstall.log'
$uninstallProcess = Start-Process -FilePath $uninstaller -ArgumentList @(
    '/VERYSILENT',
    '/SUPPRESSMSGBOXES',
    "/LOG=`"$uninstallLog`""
) -Wait -PassThru
Assert-Condition ($uninstallProcess.ExitCode -eq 0) "Uninstaller failed with exit code $($uninstallProcess.ExitCode). See $uninstallLog"

$deadline = [DateTime]::UtcNow.AddSeconds(60)
while ((Test-Path $uninstallKeyPath) -or (Test-Path $appDir)) {
    if ([DateTime]::UtcNow -ge $deadline) {
        throw "Uninstall did not finish within 60 seconds. See $uninstallLog"
    }
    Start-Sleep -Milliseconds 500
}

$runValue = Get-ItemProperty -Path $runKeyPath -Name TapDrop -ErrorAction SilentlyContinue
Assert-Condition ($null -eq $runValue) 'TapDrop Run key value still exists after uninstall.'
Assert-Condition (-not (Test-Path $sendToShortcut)) "Send To shortcut still exists: $sendToShortcut"
Assert-Condition (-not (Test-Path $startMenuShortcut)) "Start Menu shortcut still exists: $startMenuShortcut"
Assert-Condition (-not (Test-Path $exePath)) "Installed executable still exists: $exePath"
if ($userDataExistedBeforeUninstall) {
    Assert-Condition (Test-Path $dataDir) "User data was unexpectedly deleted: $dataDir"
}

Write-Output "Smoke test passed; user data existed before uninstall: $userDataExistedBeforeUninstall"
Write-Output "Uninstall log: $uninstallLog"
