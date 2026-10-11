$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$trayProject = Join-Path $repoRoot 'windows\TapDrop.Tray\TapDrop.Tray.csproj'
$publishDir = Join-Path $repoRoot 'windows\TapDrop.Tray\bin\Release\net8.0-windows10.0.19041.0\win-x64\publish'
$installerScript = Join-Path $PSScriptRoot 'TapDrop.iss'
$outputDir = Join-Path $PSScriptRoot 'out'

$versionOutput = & dotnet msbuild $trayProject -getProperty:Version
if ($LASTEXITCODE -ne 0) {
    throw "dotnet msbuild failed with exit code $LASTEXITCODE."
}
$version = ($versionOutput | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($version)) {
    throw 'Could not read the Windows application version.'
}

& dotnet publish $trayProject -c Release -r win-x64 --self-contained -p:PublishSingleFile=true
if ($LASTEXITCODE -ne 0) {
    throw "dotnet publish failed with exit code $LASTEXITCODE."
}

$iscc = $env:ISCC
if (-not $iscc -or -not (Test-Path $iscc)) {
    $candidates = @(
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe')
    )
    $iscc = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $iscc) {
    $command = Get-Command iscc -ErrorAction SilentlyContinue
    if ($command) {
        $iscc = $command.Source
    }
}
if (-not $iscc) {
    throw 'Inno Setup 6 compiler not found. Install it with: choco install innosetup'
}

New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
& $iscc "/DAppVersion=$version" "/DPublishDir=$publishDir" "/O$outputDir" $installerScript
if ($LASTEXITCODE -ne 0) {
    throw "ISCC failed with exit code $LASTEXITCODE."
}

$installerPath = Join-Path $outputDir "TapDrop-Setup-$version.exe"
if (-not (Test-Path $installerPath)) {
    throw "ISCC completed without creating $installerPath."
}

if ($env:GITHUB_OUTPUT) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::AppendAllText(
        $env:GITHUB_OUTPUT,
        "version=$version`ninstaller=$installerPath`n",
        $utf8NoBom)
}

Write-Output $installerPath
