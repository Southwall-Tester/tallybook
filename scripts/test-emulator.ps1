$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectDir
. (Join-Path $projectDir '.tools\env.ps1')
$serial = 'emulator-5554'
$avd = & adb -s $serial emu avd name
if (!($avd -match '^tallybook_api35$')) { throw 'Refusing to run: use this project disposable tallybook_api35 emulator.' }
$boot = & adb -s $serial shell getprop sys.boot_completed
if ($boot.Trim() -ne '1') { throw 'Emulator is still booting. Retry when sys.boot_completed is 1.' }
& adb -s $serial install -r '.\app\build\outputs\apk\debug\app-debug.apk'
if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
& adb -s $serial install -r '.\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
$output = & adb -s $serial shell am instrument -w dev.tallybook.app.test/dev.tallybook.app.PrototypeInstrumentation
$output | ForEach-Object { Write-Output $_ }
New-Item -ItemType Directory -Force -Path '.\artifacts' | Out-Null
$output | Set-Content -LiteralPath '.\artifacts\instrumentation.txt' -Encoding UTF8
if (!($output -match 'TALLYBOOK_SMOKE_OK checks=16')) { throw 'Android integration checks failed.' }
$savedErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue' # Native stderr is the expected rejection in this check.
    $denied = & adb -s $serial shell content call --uri content://dev.tallybook.app.capture --method state 2>&1
} finally { $ErrorActionPreference = $savedErrorPreference }
if (!(($denied | Out-String) -match 'Caller not permitted')) { throw 'External UID was not rejected.' }
'Shell UID denied by CaptureProvider guard.' | Set-Content -LiteralPath '.\artifacts\provider-access-check.txt' -Encoding UTF8
& python '.\scripts\ui-smoke.py'
if ($LASTEXITCODE -ne 0) { throw 'UI smoke check failed.' }
