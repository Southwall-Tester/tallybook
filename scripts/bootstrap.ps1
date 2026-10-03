param([switch]$SkipSdk)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.tools'
$downloadRoot = Join-Path $toolsRoot 'downloads'
New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null

function Get-VerifiedArchive([string]$Url, [string]$Name, [string]$Sha256) {
    $destination = Join-Path $downloadRoot $Name
    if (!(Test-Path -LiteralPath $destination)) {
        Write-Host "Downloading $Name"
        & curl.exe --fail --location --retry 3 --silent --show-error --output $destination $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256.ToLowerInvariant()) {
        throw "SHA256 mismatch: $destination"
    }
    return $destination
}

$manifestPath = Join-Path $toolsRoot 'download-manifest.json'
if (Test-Path -LiteralPath $manifestPath) {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
} else {
    $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $manifest = [pscustomobject]@{
        jdk = $assets[0]
        gradle = @{url='https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip';sha256='f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'}
        cmdline = @{url='https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip';sha256='90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'}
    }
    $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
}

$jdkZip = Get-VerifiedArchive $manifest.jdk.binary.package.link $manifest.jdk.binary.package.name $manifest.jdk.binary.package.checksum
$gradleZip = Get-VerifiedArchive $manifest.gradle.url 'gradle-8.11.1-bin.zip' $manifest.gradle.sha256
$cliZip = Get-VerifiedArchive $manifest.cmdline.url 'commandlinetools-win-15859902_latest.zip' $manifest.cmdline.sha256
$jdkRoot = Join-Path $toolsRoot $manifest.jdk.release_name
$gradleRoot = Join-Path $toolsRoot 'gradle-8.11.1'
$sdkRoot = Join-Path $toolsRoot 'android-sdk'
if (!(Test-Path -LiteralPath (Join-Path $jdkRoot 'bin/java.exe'))) { Expand-Archive -LiteralPath $jdkZip -DestinationPath $toolsRoot -Force }
if (!(Test-Path -LiteralPath (Join-Path $gradleRoot 'bin/gradle.bat'))) { Expand-Archive -LiteralPath $gradleZip -DestinationPath $toolsRoot -Force }
$cliRoot = Join-Path $sdkRoot 'cmdline-tools/latest'
if (!(Test-Path -LiteralPath (Join-Path $cliRoot 'bin/sdkmanager.bat'))) {
    New-Item -ItemType Directory -Path $cliRoot -Force | Out-Null
    $cliStaging = Join-Path $toolsRoot 'cli-unpacked'
    Expand-Archive -LiteralPath $cliZip -DestinationPath $cliStaging -Force
    Get-ChildItem -LiteralPath (Join-Path $cliStaging 'cmdline-tools') -Force | Copy-Item -Destination $cliRoot -Recurse -Force
}

$paths = [ordered]@{javaHome=$jdkRoot;gradleHome=$gradleRoot;androidSdk=$sdkRoot;gradleUserHome=(Join-Path $toolsRoot 'gradle-home');androidUserHome=(Join-Path $toolsRoot 'android-user')}
$paths | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $toolsRoot 'paths.json') -Encoding UTF8
@'
$toolPaths = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'paths.json') -Raw | ConvertFrom-Json
$env:JAVA_HOME = $toolPaths.javaHome
$env:ANDROID_HOME = $toolPaths.androidSdk
$env:ANDROID_SDK_ROOT = $toolPaths.androidSdk
$env:GRADLE_USER_HOME = $toolPaths.gradleUserHome
$env:ANDROID_USER_HOME = $toolPaths.androidUserHome
$env:ANDROID_AVD_HOME = Join-Path $toolPaths.androidUserHome 'avd'
$env:PATH = "$($toolPaths.javaHome)\bin;$($toolPaths.gradleHome)\bin;$($toolPaths.androidSdk)\platform-tools;$($toolPaths.androidSdk)\cmdline-tools\latest\bin;$env:PATH"
'@ | Set-Content -LiteralPath (Join-Path $toolsRoot 'env.ps1') -Encoding UTF8
. (Join-Path $toolsRoot 'env.ps1')
if (!$SkipSdk) {
    # Android SDK licenses are accepted for this project-local development install.
    1..100 | ForEach-Object { 'y' } | & (Join-Path $cliRoot 'bin/sdkmanager.bat') --sdk_root=$sdkRoot --licenses | Out-File -LiteralPath (Join-Path $toolsRoot 'sdk-licenses.log') -Encoding UTF8
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK license step failed.' }
    & (Join-Path $cliRoot 'bin/sdkmanager.bat') --sdk_root=$sdkRoot 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK install failed.' }
}
Write-Host "Toolchain ready. Dot-source $toolsRoot\env.ps1 in the current PowerShell process."
