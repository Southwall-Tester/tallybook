[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$')][string]$Serial,
    [switch]$List,
    [switch]$Check,
    [switch]$Remember,
    [switch]$Watch
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$envFile = Join-Path $projectRoot '.tools\env.ps1'
if (!(Test-Path -LiteralPath $envFile)) { throw 'Run scripts/bootstrap.ps1 first.' }
. $envFile
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$targetFile = Join-Path $projectRoot '.tools\phone-target.json'

function Get-DeviceInventory {
    $lines = & $adb devices -l
    if ($LASTEXITCODE -ne 0) { throw 'ADB could not list devices.' }
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+(device|offline|unauthorized|recovery|sideload|bootloader)\b(.*)$') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2]; Details = $Matches[3].Trim() }
        }
    }
}

if ($List) {
    $inventory = @(Get-DeviceInventory)
    if (!$inventory.Count) { Write-Host 'No ADB devices found. Enable USB debugging on the experimental phone and allow this computer.' }
    else { $inventory | Format-Table -AutoSize }
    return
}
if (!$Serial -and (Test-Path -LiteralPath $targetFile)) {
    $Serial = [string](Get-Content -LiteralPath $targetFile -Raw | ConvertFrom-Json).serial
}
if (!$Serial) {
    throw 'No phone is selected. Enable USB debugging on the experimental phone and allow this computer. Use -List, then -Serial <phone-id> -Remember. Tablets are never selected automatically.'
}
if ($Serial -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$' -or $Serial -match '^emulator-') {
    throw 'This command requires an explicitly selected physical phone. Use preview.ps1 for the emulator.'
}
# Keep this exact target throughout the session, including reconnects and watch rebuilds.
$phoneSerial = $Serial

function Assert-PhoneReady {
    $matchesTarget = @(Get-DeviceInventory | Where-Object { $_.Serial -ceq $phoneSerial })
    if ($matchesTarget.Count -ne 1) { throw 'The selected phone is disconnected. Reconnect that phone; no other device will be used.' }
    if ($matchesTarget[0].State -eq 'unauthorized') { throw 'Unlock the selected phone and accept the Allow USB debugging dialog for this computer.' }
    if ($matchesTarget[0].State -ne 'device') { throw "Selected phone is $($matchesTarget[0].State). Reconnect it and retry." }
    $qemu = & $adb -s $phoneSerial shell getprop ro.kernel.qemu
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the selected phone.' }
    if (($qemu | Out-String).Trim() -eq '1') { throw 'The selected device is an emulator; use preview.ps1.' }
    $characteristics = & $adb -s $phoneSerial shell getprop ro.build.characteristics
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the selected phone type.' }
    if (($characteristics | Out-String) -match '(?i)\b(tablet|tv|watch|automotive)\b') {
        throw 'The selected device identifies as a tablet or another non-phone device. It has been left unchanged.'
    }
    $sdk = & $adb -s $phoneSerial shell getprop ro.build.version.sdk
    $api = 0
    if ($LASTEXITCODE -ne 0 -or ![int]::TryParse(($sdk | Out-String).Trim(), [ref]$api) -or $api -lt 26) {
        throw 'Tallybook requires Android 8.0 / API 26 or higher.'
    }
}

Assert-PhoneReady
$model = & $adb -s $phoneSerial shell getprop ro.product.model
if ($LASTEXITCODE -ne 0) { throw 'Could not read the selected phone model.' }
Write-Host "Selected phone: $(($model | Out-String).Trim()) ($phoneSerial)"
if ($Remember) {
    $target = @{ serial = $phoneSerial; model = ($model | Out-String).Trim() } | ConvertTo-Json
    [IO.File]::WriteAllText($targetFile, $target, [Text.UTF8Encoding]::new($false))
    Write-Host 'Remembered this phone locally. The selection is not committed to Git.'
}
if ($Check) { Write-Host 'Phone is ready. No app was installed or started.'; return }

function Invoke-PhoneDeployment {
    Assert-PhoneReady
    # Incremental development build only; the release checkpoint still uses build.ps1.
    & (Join-Path $projectRoot 'gradlew.bat') ':app:assembleDebug' '--console=plain' '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. The previous APK was not installed.' }
    Assert-PhoneReady
    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (!(Test-Path -LiteralPath $apk)) { throw 'The build did not produce an APK.' }
    & $adb -s $phoneSerial install -r $apk
    if ($LASTEXITCODE -ne 0) {
        throw 'Installation failed; the existing app was not removed. Check the phone confirmation and signing/version compatibility. No uninstall or data reset is attempted.'
    }
    $launch = & $adb -s $phoneSerial shell am start -S -W -n 'dev.tallybook.app/.MainActivity'
    $launchExit = $LASTEXITCODE
    $launch | ForEach-Object { Write-Host $_ }
    $launchText = $launch | Out-String
    if ($launchExit -ne 0 -or $launchText -match '(?im)^\s*Error\b' -or $launchText -notmatch '(?im)^\s*Status:\s*ok\s*$') {
        throw 'Installation succeeded but launching Tallybook failed or timed out.'
    }
    Write-Host "Updated and launched Tallybook on the selected phone at $(Get-Date -Format HH:mm:ss)."
}

function Get-SourceFingerprint {
    $files = @()
    foreach ($folder in @('app\src\main', 'core\src\main')) {
        $files += Get-ChildItem -LiteralPath (Join-Path $projectRoot $folder) -File -Recurse
    }
    foreach ($file in @('build.gradle','settings.gradle','gradle.properties','app\build.gradle','core\build.gradle','gradle\wrapper\gradle-wrapper.properties')) {
        $files += Get-Item -LiteralPath (Join-Path $projectRoot $file)
    }
    # Only source/configuration files are watched; generated builds, bills and tools are excluded.
    return (($files | Sort-Object FullName | ForEach-Object {
        $_.FullName + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }) -join "`n")
}

$attempted = Get-SourceFingerprint
if (!$Watch) { Invoke-PhoneDeployment; return }
try { Invoke-PhoneDeployment }
catch {
    Write-Warning $_.Exception.Message
    Assert-PhoneReady
    Write-Host 'Fix the source and save again, or restart this command to retry deployment.'
}
Write-Host 'Watching source changes. Each successful build replaces and restarts this app on the same phone. Ctrl+C stops watching.'
while ($true) {
    Start-Sleep -Seconds 2
    Assert-PhoneReady # Stop on disconnect instead of ever selecting another device.
    try { $current = Get-SourceFingerprint } catch { continue } # A file may be in the middle of an editor save.
    if ($current -eq $attempted) { continue }
    Start-Sleep -Seconds 2
    try { $stable = Get-SourceFingerprint } catch { continue }
    if ($stable -ne $current) { continue }
    $attempted = $stable # Capture before building, so edits during the build trigger a subsequent run.
    try { Invoke-PhoneDeployment }
    catch {
        Write-Warning $_.Exception.Message
        Assert-PhoneReady
        Write-Host 'Fix the source and save again, or restart this command to retry deployment.'
    }
}
