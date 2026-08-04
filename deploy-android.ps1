$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$adb = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $candidates = @(
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    )
    $adb = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $adb) { throw "adb.exe not found. Install Android SDK platform-tools." }
$apk = Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk"
$package = "com.coltexpress.client"
$activity = "$package/.MainActivity"

if (-not (Test-Path $apk)) {
    Write-Host "APK not found, building..."
    Push-Location (Join-Path $root "android")
    try { & .\gradlew.bat assembleDebug } finally { Pop-Location }
}

$devices = & $adb devices | Select-String "^emulator-\d+\s+device" | ForEach-Object { ($_ -split "\s+")[0] }
if (-not $devices) {
    throw "No connected emulators found. Start the emulators first."
}

foreach ($dev in $devices) {
    Write-Host "==> $dev : waiting for boot"
    & $adb -s $dev wait-for-device | Out-Null
    do {
        Start-Sleep -Seconds 2
        $booted = (& $adb -s $dev shell getprop sys.boot_completed 2>$null).Trim()
    } while ($booted -ne "1")
    Write-Host "==> $dev : booted, installing"
    & $adb -s $dev install -r $apk | Out-Null
    Write-Host "==> $dev : launching $activity"
    & $adb -s $dev shell am start -n $activity | Out-Null
    Write-Host "==> $dev : done"
}

Write-Host "Deployed on: $($devices -join ', ')"
