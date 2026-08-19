<#
  .SYNOPSIS
    Сборка сервера и APK-клиента и упаковка готового дистрибутива для загрузки на VPS.

  .DESCRIPTION
    Запускается на вашей Windows-машине (где стоит проект). Собирает
    installDist сервера и debug-APK клиента, затем кладёт в deploy\vps\dist\:
      - colt-express-server.tar.gz  — дистрибутив сервера (bin + lib)
      - app-debug.apk              — клиент, который сервер раздаёт через /apk
      - install.sh                 — скрипт установки на VPS
      - colt-express.service       — unit systemd
      - nginx-colt.conf            — опциональный reverse proxy (TLS)

  .EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\build.ps1
#>

$ErrorActionPreference = "Stop"

$root  = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$dist  = Join-Path $PSScriptRoot "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null

Write-Host "==> [1/4] Building server installDist..." -ForegroundColor Cyan
Push-Location (Join-Path $root "server")
try { & .\gradlew.bat installDist --no-daemon } finally { Pop-Location }

Write-Host "==> [2/4] Building Android APK..." -ForegroundColor Cyan
Push-Location (Join-Path $root "android")
try { & .\gradlew.bat assembleDebug --no-daemon } finally { Pop-Location }

Write-Host "==> [3/4] Packing server distribution..." -ForegroundColor Cyan
if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) {
    throw "tar.exe not found. Need Windows 10 1803+ (built-in bsdtar) or install tar for Windows."
}
$installDir = Join-Path $root "server\build\install"
Push-Location $installDir
try { & tar.exe -czf (Join-Path $dist "colt-express-server.tar.gz") "colt-express-server" } finally { Pop-Location }

Write-Host "==> [4/4] Copying APK, web client and helper files..." -ForegroundColor Cyan
Copy-Item (Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk") (Join-Path $dist "app-debug.apk") -Force
$webDist = Join-Path $dist "web"
New-Item -ItemType Directory -Force -Path $webDist | Out-Null
Copy-Item (Join-Path $root "web\index.html") (Join-Path $webDist "index.html") -Force
Copy-Item (Join-Path $PSScriptRoot "install.sh")            (Join-Path $dist "install.sh") -Force
Copy-Item (Join-Path $PSScriptRoot "colt-express.service")  (Join-Path $dist "colt-express.service") -Force
Copy-Item (Join-Path $PSScriptRoot "nginx-colt.conf")        (Join-Path $dist "nginx-colt.conf") -Force
Copy-Item (Join-Path $PSScriptRoot "setup_nginx.sh")         (Join-Path $dist "setup_nginx.sh") -Force

Write-Host ""
Write-Host "Готово. Дистрибутив в: $dist" -ForegroundColor Green
Write-Host "Дальше: .\upload.ps1 -Server <IP_ИЛИ_ДОМЕН_VPS> -User root" -ForegroundColor Green
