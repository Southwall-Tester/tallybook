param([string]$Serial)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectDir
if (!$Serial) {
    $targetPath = Join-Path $projectDir '.tools\phone-target.json'
    if (!(Test-Path -LiteralPath $targetPath)) {
        throw 'Choose your phone first: scripts/run-device.ps1 -Serial PHONE_SERIAL -Remember -Check'
    }
    $Serial = [string](Get-Content -LiteralPath $targetPath -Raw -Encoding UTF8 | ConvertFrom-Json).serial
}
if (!$Serial -or $Serial -like 'emulator-*') { throw 'A physical phone must be explicitly selected.' }
Write-Output 'Keep this companion open. Start a query in Tallybook, then open the WeChat bill list.'
& python -X utf8 (Join-Path $PSScriptRoot 'query-wechat-bills.py') --serial $Serial --watch-app
if ($LASTEXITCODE -ne 0) { throw "The companion stopped with exit code $LASTEXITCODE." }
