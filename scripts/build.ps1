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
& (Join-Path $projectDir 'gradlew.bat') @tasks --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
$artifactDir = Join-Path $projectDir 'artifacts'
New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
$outputDir = Join-Path $projectDir 'app\build\outputs\apk\debug'
$metadata = Get-Content -LiteralPath (Join-Path $outputDir 'output-metadata.json') -Raw | ConvertFrom-Json
$outputs = @($metadata.elements)
if ($outputs.Count -ne 1) { throw 'Expected one universal debug APK in output-metadata.json.' }
$version = [string]$outputs[0].versionName -replace '-prototype$', ''
if ($version -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw 'Invalid APK version name.' }
$outputFile = [string]$outputs[0].outputFile
if ([IO.Path]::GetFileName($outputFile) -ne $outputFile) { throw 'Expected an APK filename without directory components.' }
$builtApk = Join-Path $outputDir $outputFile
$filename = "tallybook-$version-debug.apk"
$apk = Join-Path $artifactDir $filename
$builtHash = (Get-FileHash -LiteralPath $builtApk -Algorithm SHA256).Hash.ToLowerInvariant()
if (Test-Path -LiteralPath $apk) {
    $previousHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($previousHash -ne $builtHash) {
        $historyDir = Join-Path $artifactDir 'history'
        New-Item -ItemType Directory -Path $historyDir -Force | Out-Null
        $previousName = "tallybook-$version-debug-$previousHash.apk"
        Copy-Item -LiteralPath $apk -Destination (Join-Path $historyDir $previousName) -Force
        [IO.File]::WriteAllText((Join-Path $historyDir "$previousName.sha256"), "$previousHash  $previousName`n", [Text.UTF8Encoding]::new($false))
        Write-Output "Previous build preserved: $previousName"
    }
}
Copy-Item -LiteralPath $builtApk -Destination $apk -Force
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $artifactDir "$filename.sha256"), "$hash  $filename`n", [Text.UTF8Encoding]::new($false))
Write-Output "APK: $apk"
Write-Output "SHA256: $hash"
