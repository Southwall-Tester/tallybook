param([switch]$Rebuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
try {
    $envFile = Join-Path $projectRoot '.tools\env.ps1'
    if (!(Test-Path -LiteralPath $envFile)) { & (Join-Path $PSScriptRoot 'bootstrap.ps1') }
    . $envFile
    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if ($Rebuild -or !(Test-Path -LiteralPath $apk)) { & (Join-Path $PSScriptRoot 'build.ps1') }
    $sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
    $avdManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\avdmanager.bat'
    $emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
    $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    $imageDir = Join-Path $env:ANDROID_HOME 'system-images\android-35\default\x86_64'
    if (!(Test-Path -LiteralPath $emulator) -or !(Test-Path -LiteralPath $imageDir)) {
        & $sdkManager --sdk_root=$env:ANDROID_HOME 'emulator' 'system-images;android-35;default;x86_64'
        if ($LASTEXITCODE -ne 0) { throw 'Could not install emulator. Check your network and Android SDK licenses.' }
    }
    $avdName = 'tallybook_preview'
    $avdPath = Join-Path $env:ANDROID_AVD_HOME "$avdName.avd"
    New-Item -ItemType Directory -Force -Path $env:ANDROID_AVD_HOME | Out-Null
    if (!(Test-Path -LiteralPath (Join-Path $avdPath 'config.ini'))) {
        'no' | & $avdManager create avd --name $avdName --package 'system-images;android-35;default;x86_64' --device 'pixel_7' --path $avdPath
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the preview device.' }
        $configFile = Join-Path $avdPath 'config.ini'
        $config = Get-Content -LiteralPath $configFile -Raw
        $config = $config -replace '(?m)^hw.lcd.width=.*$', 'hw.lcd.width=720'
        $config = $config -replace '(?m)^hw.lcd.height=.*$', 'hw.lcd.height=1280'
        $config = $config -replace '(?m)^hw.lcd.density=.*$', 'hw.lcd.density=280'
        $config = $config -replace '(?m)^showDeviceFrame=.*$', 'showDeviceFrame=no'
        [IO.File]::WriteAllText($configFile, $config, [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $avdPath 'emulator-user.ini'), "window.x = 120`nwindow.y = 60`nwindow.scale = 0.65`n", [Text.UTF8Encoding]::new($false))
    }
    $serial = 'emulator-5556'
    $devices = & $adb devices
    if (($devices | Out-String) -match '(?m)^emulator-5556\s+') {
        $currentName = & $adb -s $serial emu avd name
        if (!($currentName -match '^tallybook_preview$')) { throw 'Port 5556 belongs to another emulator. Close it before starting this preview.' }
    } else {
        # The user requested an interactive desktop preview, so this emulator window is visible.
        Start-Process -FilePath $emulator -ArgumentList @('-avd',$avdName,'-port','5556','-no-audio','-no-boot-anim','-no-snapshot','-no-metrics','-gpu','swiftshader','-memory','2048','-cores','2','-timezone','Asia/Shanghai') -WindowStyle Normal -RedirectStandardOutput (Join-Path $projectRoot '.tools\preview.stdout.log') -RedirectStandardError (Join-Path $projectRoot '.tools\preview.stderr.log') | Out-Null
    }
    $ready = $false
    $deadline = (Get-Date).AddMinutes(4)
    while ((Get-Date) -lt $deadline) {
        $devices = & $adb devices
        if (($devices | Out-String) -match '(?m)^emulator-5556\s+device') {
            $boot = & $adb -s $serial shell getprop sys.boot_completed
            if ($boot -match '^1$') { $ready = $true; break }
        }
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'Emulator did not finish booting. See .tools\preview.stderr.log.' }
    & $adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. Existing preview data was not cleared.' }
    & $adb -s $serial shell am start -n dev.tallybook.app/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw 'Could not open Tallybook.' }
    Write-Host 'Tallybook desktop preview is ready. Closing the emulator saves its local app data.'
} catch {
    $message = $_.Exception.Message
    $message | Set-Content -LiteralPath (Join-Path $projectRoot '.tools\preview-error.txt') -Encoding UTF8
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show($message, 'Tallybook preview') | Out-Null
    throw
}
