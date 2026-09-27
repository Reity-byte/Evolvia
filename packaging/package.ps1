<#
.SYNOPSIS
    Builds the same packages as the CI release workflow, locally on Windows, as a local release.

.DESCRIPTION
    1. mvnw package -> target/evolvia.jar
    2. jpackage app images: dist/app/Evolvia (game, main class evolvia.Launch)
                            dist/app/EvolviaLauncher (launcher, main class evolvia.LauncherMain)
    3. zips with the release names: dist/release/Evolvia-windows.zip, EvolviaLauncher-windows.zip
       (PowerShell 5.1 Compress-Archive writes backslash entry names - the launcher handles them)
    4. dist/release/release.json in the shape of the GitHub "latest release" API, tagged
       v<pom version>-local.<timestamp>, so every build counts as newer than the previous one.

    With -Launch the packaged launcher is started with "--local dist\release" and data folder
    <repo>\run (EVOLVIA_HOME), so it installs and runs this build without touching %APPDATA%\Evolvia.

.PARAMETER SkipTests
    Skip unit tests during the Maven build.

.PARAMETER Launch
    Start the packaged launcher against the local release when done.

.EXAMPLE
    .\packaging\package.ps1 -SkipTests -Launch
#>
param(
    [switch]$SkipTests,
    [switch]$Launch
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$started = Get-Date

# JDK modules bundled by jpackage. Keep in sync with .github/workflows/release.yml.
$GameModules = 'java.base,java.sql,jdk.unsupported'
$LauncherModules = "$GameModules,java.desktop,java.net.http,jdk.crypto.ec"

if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME must point to a JDK 21 or newer (it provides jpackage).'
}
$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
if (-not (Test-Path $jpackage)) {
    throw "jpackage not found: $jpackage"
}

Write-Host '==> Building fat jar'
$mvnArgs = @('-B', '-q', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
& .\mvnw.cmd @mvnArgs
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }

$pomVersion = ([xml](Get-Content pom.xml)).project.version
$appVersion = $pomVersion -replace '-.*$', ''
$tag = "v$appVersion-local.$(Get-Date -Format yyyyMMddHHmmss)"

$dist = Join-Path $root 'dist'
if (Test-Path $dist) {
    try {
        Remove-Item -Recurse -Force $dist -ErrorAction Stop
    } catch {
        throw "Cannot delete $dist - close the launcher/game started from it and run again. ($($_.Exception.Message))"
    }
}
$inputDir = New-Item -ItemType Directory -Force (Join-Path $dist 'input')
$app = New-Item -ItemType Directory -Force (Join-Path $dist 'app')
$release = New-Item -ItemType Directory -Force (Join-Path $dist 'release')
Copy-Item target\evolvia.jar $inputDir

Write-Host "==> jpackage Evolvia ($tag)"
& $jpackage --type app-image --name Evolvia --input $inputDir --main-jar evolvia.jar `
    --main-class evolvia.Launch --add-modules $GameModules --app-version $appVersion --dest $app `
    --java-options '--enable-native-access=ALL-UNNAMED'
if ($LASTEXITCODE -ne 0) { throw 'jpackage (game) failed' }

Write-Host '==> jpackage EvolviaLauncher'
& $jpackage --type app-image --name EvolviaLauncher --input $inputDir --main-jar evolvia.jar `
    --main-class evolvia.LauncherMain --add-modules $LauncherModules --app-version $appVersion --dest $app
if ($LASTEXITCODE -ne 0) { throw 'jpackage (launcher) failed' }

Write-Host '==> Zipping'
Compress-Archive -Path (Join-Path $app 'Evolvia') -DestinationPath (Join-Path $release 'Evolvia-windows.zip')
Compress-Archive -Path (Join-Path $app 'EvolviaLauncher') -DestinationPath (Join-Path $release 'EvolviaLauncher-windows.zip')

$assets = @(Get-ChildItem (Join-Path $release '*.zip') | ForEach-Object {
    [ordered]@{
        name                 = $_.Name
        size                 = $_.Length
        browser_download_url = ([Uri]$_.FullName).AbsoluteUri
    }
})
$json = [ordered]@{ tag_name = $tag; assets = $assets } | ConvertTo-Json -Depth 4
# UTF-8 without BOM (Set-Content -Encoding UTF8 in PowerShell 5.1 would add one)
[IO.File]::WriteAllText((Join-Path $release 'release.json'), $json, (New-Object Text.UTF8Encoding $false))

$seconds = [int]((Get-Date) - $started).TotalSeconds
Write-Host "==> Local release $tag ready in $release ($seconds s)"

if ($Launch) {
    $env:EVOLVIA_HOME = Join-Path $root 'run'
    Write-Host "==> Starting launcher (data folder $env:EVOLVIA_HOME)"
    & (Join-Path $app 'EvolviaLauncher\EvolviaLauncher.exe') --local $release
}
