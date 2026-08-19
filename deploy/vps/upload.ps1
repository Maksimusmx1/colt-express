<#
  .SYNOPSIS
    Загрузка собранного дистрибутива на VPS и установка сервера Colt Express.

  .DESCRIPTION
    Копирует по scp содержимое deploy\vps\dist\ на VPS в /tmp/colt-deploy/
    и запускает там install.sh (ставит JDK 17 при необходимости, создаёт
    пользователя colt, распаковывает сервер в /opt/colt/server, регистрирует
    systemd-сервис colt-express и стартует его).

    Перед запуском: у вас должен быть установлен OpenSSH Client (Windows
    Settings -> Apps -> Optional Features -> OpenSSH Client) и доступ к VPS
    по ключу или паролю.

  .PARAMETER Server
    IP-адрес или домен VPS, например: 5.188.120.10

  .PARAMETER User
    Пользователь для SSH. По умолчанию root.

  .PARAMETER DistDir
    Каталог с готовыми файлами. По умолчанию deploy\vps\dist.

  .EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\upload.ps1 -Server 5.188.120.10 -User root
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$Server,

    [string]$User = "root",

    [string]$DistDir
)

if (-not $DistDir) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    if (-not $scriptDir) {
        $scriptDir = "C:\Users\максим\OneDrive\Документы\Default Project\deploy\vps"
    }
    $DistDir = Join-Path $scriptDir "dist"
}

$ErrorActionPreference = "Stop"

$ssh = (Get-Command ssh.exe -ErrorAction SilentlyContinue).Source
$scp = (Get-Command scp.exe -ErrorAction SilentlyContinue).Source
if (-not $ssh -or -not $scp) {
    throw "OpenSSH Client not found (ssh.exe/scp.exe). Install it: Windows Settings -> Apps -> Optional Features -> OpenSSH Client."
}
if (-not (Test-Path -LiteralPath $DistDir)) {
    throw "Directory not found: $DistDir`nRun .\build.ps1 first."
}

$files = @(
    "colt-express-server.tar.gz",
    "app-debug.apk",
    "install.sh",
    "colt-express.service",
    "nginx-colt.conf",
    "setup_nginx.sh"
)
foreach ($f in $files) {
    if (-not (Test-Path -LiteralPath (Join-Path $DistDir $f))) {
        throw "Missing file: $f in $DistDir`nRun .\build.ps1 first."
    }
}
$webDir = Join-Path $DistDir "web"
if (-not (Test-Path -LiteralPath $webDir)) {
    throw "Missing web directory in $DistDir`nRun .\build.ps1 first."
}

$target   = "$User@$Server"
$remoteDir = "/tmp/colt-deploy"

Write-Host "==> Creating remote dir $target`:$remoteDir ..." -ForegroundColor Cyan
& $ssh $target "mkdir -p $remoteDir"
if ($LASTEXITCODE -ne 0) { throw "SSH failed. Check the address/key." }

Write-Host "==> Uploading files..." -ForegroundColor Cyan
$uploadFiles = $files | ForEach-Object { Join-Path $DistDir $_ }
& $scp $uploadFiles "${target}:${remoteDir}/"
if ($LASTEXITCODE -ne 0) { throw "scp failed." }

Write-Host "==> Uploading web client..." -ForegroundColor Cyan
& $scp -r $webDir "${target}:${remoteDir}/"
if ($LASTEXITCODE -ne 0) { throw "scp web failed." }

Write-Host "==> Running install.sh on VPS..." -ForegroundColor Cyan
& $ssh $target "chmod +x $remoteDir/install.sh && sudo $remoteDir/install.sh && sudo chmod +x /opt/colt/server/bin/colt-express-server"
if ($LASTEXITCODE -ne 0) { throw "install.sh failed on VPS" }

Write-Host ""
Write-Host "Установка завершена." -ForegroundColor Green
Write-Host "Проверка:  curl http://${Server}:8080/health" -ForegroundColor Green
Write-Host "В приложении укажите адрес:  ${Server}:8080" -ForegroundColor Green
