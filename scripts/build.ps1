param([switch]$SkipLint)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectDir
$envFile = Join-Path $projectDir '.tools\env.ps1'
if (!(Test-Path -LiteralPath $envFile)) {
    throw 'Run scripts/bootstrap.ps1 first to prepare the local Android toolchain.'
}
. $envFile
$tasks = @(':core:test', ':app:assembleDebug', ':app:assembleDebugAndroidTest')
if (!$SkipLint) { $tasks += ':app:lintDebug' }
& gradle.bat @tasks --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
$artifactDir = Join-Path $projectDir 'artifacts'
New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectDir 'app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $artifactDir 'tallybook-0.1.0-debug.apk') -Force
$apk = Join-Path $artifactDir 'tallybook-0.1.0-debug.apk'
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $artifactDir 'tallybook-0.1.0-debug.apk.sha256'), "$hash  tallybook-0.1.0-debug.apk`n", [Text.UTF8Encoding]::new($false))
Write-Output "APK: $apk"
Write-Output "SHA256: $hash"
