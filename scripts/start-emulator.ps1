param([switch]$Install)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $projectRoot '.tools/env.ps1')
$sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/sdkmanager.bat'
$avdManager = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/avdmanager.bat'
$emulator = Join-Path $env:ANDROID_HOME 'emulator/emulator.exe'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$avdName = 'tallybook_api35'
$avdPath = Join-Path $env:ANDROID_AVD_HOME "$avdName.avd"
if ($Install) {
    & $sdkManager --sdk_root=$env:ANDROID_HOME 'emulator' 'system-images;android-35;default;x86_64'
    if ($LASTEXITCODE -ne 0) { throw 'Emulator SDK packages could not be installed.' }
}
if (!(Test-Path -LiteralPath $emulator)) { throw 'Run start-emulator.ps1 -Install first.' }
New-Item -ItemType Directory -Force -Path $env:ANDROID_AVD_HOME | Out-Null
if (!(Test-Path -LiteralPath (Join-Path $avdPath 'config.ini'))) {
    'no' | & $avdManager create avd --name $avdName --package 'system-images;android-35;default;x86_64' --device 'pixel_7' --path $avdPath
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the project-local AVD.' }
}
$existing = & $adb devices
if ($existing -match '^emulator-5554\s+device') { Write-Host 'Emulator on port 5554 is already running.'; return }
$logRoot = Join-Path $projectRoot '.tools'
$process = Start-Process -FilePath $emulator -ArgumentList @('-avd',$avdName,'-port','5554','-no-window','-no-audio','-no-boot-anim','-no-snapshot','-no-metrics','-gpu','swiftshader','-memory','2048','-cores','2') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'emulator.stdout.log') -RedirectStandardError (Join-Path $logRoot 'emulator.stderr.log')
$process.Id | Set-Content -LiteralPath (Join-Path $logRoot 'emulator.pid') -Encoding ASCII
Write-Host "Started headless emulator PID $($process.Id). Check: adb -s emulator-5554 shell getprop sys.boot_completed"
