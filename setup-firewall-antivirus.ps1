#Requires -RunAsAdministrator
<#
  .SYNOPSIS
    Настраивает Windows Defender Firewall и Windows Defender Antivirus так,
    чтобы не было блокировок для Tailscale, opencode и сервера Colt Express.

  .DESCRIPTION
    Создаёт (идемпотентно) входящие разрешающие правила для:
      - opencode          TCP 4096 (сервер opencode, --server)
      - Colt Express      TCP 8080 (Ktor-сервер игры, WebSocket)
      - Tailscale         tailscaled.exe (все профили), UDP/TCP 41641 (WireGuard)

    И добавляет исключения Windows Defender Antivirus (path + process) для:
      - opencode.exe (winget и ~\.opencode\bin)
      - каталогов данных opencode
      - C:\Program Files\Tailscale (+ tailscaled.exe / tailscale.exe)
      - каталога текущего проекта (сервер + сборки Gradle)
      - каталога кэша Gradle (~\.gradle)

    Скрипт безопасен для повторного запуска: старые правила LocalDev-* удаляются,
    исключения антивируса не дублируются.

  .EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\setup-firewall-antivirus.ps1
#>

$ErrorActionPreference = 'Stop'

# Общий префикс, чтобы отличать наши правила от правил Tailscale/системных.
$Prefix = 'LocalDev - '

Write-Host '== Windows Defender Firewall ==' -ForegroundColor Cyan

function Add-FirewallRule {
    param(
        [string] $Name,
        [string] $Program = '',
        [string] $Protocol = '',
        [string] $Port = '',
        [string[]] $Profile = @('Domain', 'Private', 'Public')
    )
    $full = "$Prefix$Name"
    $old = Get-NetFirewallRule -DisplayName $full -PolicyStore ActiveStore -ErrorAction SilentlyContinue
    if ($old) { Remove-NetFirewallRule -DisplayName $full -ErrorAction Stop }

    $params = @{
        DisplayName = $full
        Direction   = 'Inbound'
        Action      = 'Allow'
        Profile     = $Profile
        Enabled     = 'True'
        ErrorAction = 'Stop'
    }
    if ($Program) {
        $params.Program = $Program
        Write-Host "  [+] $full  (Программа: $Program)"
    }
    else {
        $params.Protocol = $Protocol
        if ($Port) { $params.LocalPort = $Port }
        Write-Host "  [+] $full  ($Protocol/$Port)"
    }
    New-NetFirewallRule @params | Out-Null
}

# --- opencode: правила по процессу (все порты) + служебный TCP 4096 ----------
$opencodeExes = @()
$cmdOpen = (Get-Command opencode -ErrorAction SilentlyContinue).Source
if ($cmdOpen) { $opencodeExes += $cmdOpen }
$wingetOpen = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -Filter 'SST.opencode*' -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName 'opencode.exe' }
$opencodeExes += $wingetOpen
$legacyOpen = Join-Path $env:USERPROFILE '.opencode\bin\opencode.exe'
if (Test-Path -LiteralPath $legacyOpen) { $opencodeExes += $legacyOpen }
$opencodeExes = $opencodeExes | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Sort-Object -Unique

if ($opencodeExes) {
    # удалить возможный старый вариант правила без суффикса
    $legacyRule = Get-NetFirewallRule -DisplayName "$Prefix$('opencode.exe')" -PolicyStore ActiveStore -ErrorAction SilentlyContinue
    if ($legacyRule) { Remove-NetFirewallRule -DisplayName $legacyRule.DisplayName -ErrorAction Stop }

    $i = 0
    foreach ($exe in $opencodeExes) {
        $i++
        $label = if ($exe -match 'WinGet') { 'opencode.exe (winget)' }
                 elseif ($exe -match '\.opencode\\bin') { 'opencode.exe (local)' }
                 else { "opencode.exe ($i)" }
        Add-FirewallRule -Name $label -Program $exe
    }
}
Add-FirewallRule -Name 'opencode web 4096' -Protocol 'TCP' -Port '4096'

# --- Сервер Colt Express: TCP 8080 -----------------------------------------
Add-FirewallRule -Name 'Colt Express Server' -Protocol 'TCP' -Port '8080'

# --- Tailscale: процесс + порты WireGuard ----------------------------------
$tailscaled = Join-Path $env:ProgramFiles 'Tailscale\tailscaled.exe'
if (Test-Path -LiteralPath $tailscaled) {
    Add-FirewallRule -Name 'Tailscale process' -Program $tailscaled
}
Add-FirewallRule -Name 'Tailscale WireGuard UDP' -Protocol 'UDP' -Port '41641'
Add-FirewallRule -Name 'Tailscale WireGuard TCP' -Protocol 'TCP' -Port '41641'

# FIXED-PORTS (постоянный порт tailscale, если задан)
$prefs = & "$env:ProgramFiles\Tailscale\tailscale.exe" debug prefs 2>$null | Out-String
if ($prefs -match '(?im)port\s*[:=]\s*(\d+)') {
    Add-FirewallRule -Name 'Tailscale WireGuard UDP fixed' -Protocol 'UDP' -Port $Matches[1]
    Add-FirewallRule -Name 'Tailscale WireGuard TCP fixed' -Protocol 'TCP' -Port $Matches[1]
}

Write-Host ''
Write-Host '== Windows Defender Antivirus: исключения ==' -ForegroundColor Cyan

function Add-AvExclusion {
    param([string] $Kind, [string] $Value)
    if (-not $Value) { return }
    $existing = @()
    if ($Kind -eq 'Path') { $existing = @((Get-MpPreference).ExclusionPath) }
    else { $existing = @((Get-MpPreference).ExclusionProcess) }

    $value2 = $Value.Trim()
    $match = $existing | Where-Object { $_.Trim() -ieq $value2 } | Select-Object -First 1
    if (-not $match) {
        if ($Kind -eq 'Path') { Add-MpPreference -ExclusionPath $value2 -ErrorAction Stop }
        else { Add-MpPreference -ExclusionProcess $value2 -ErrorAction Stop }
        Write-Host "  [+] $Kind : $value2"
    }
    else {
        Write-Host "  [=] $Kind : $value2 (уже есть)"
    }
}

$projectRoot = $PSScriptRoot

# opencode (процессы и каталоги)
foreach ($exe in $opencodeExes) { Add-AvExclusion -Kind 'Process' -Value $exe }
Add-AvExclusion -Kind 'Path' -Value (Join-Path $env:USERPROFILE '.opencode')
Add-AvExclusion -Kind 'Path' -Value (Join-Path $env:USERPROFILE '.local\share\opencode')

# opencode via winget — исключаем каталог пакета
foreach ($pkg in (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -Filter 'SST.opencode*' -ErrorAction SilentlyContinue)) {
    Add-AvExclusion -Kind 'Path' -Value $pkg.FullName
}

# Tailscale
Add-AvExclusion -Kind 'Path' -Value (Join-Path $env:ProgramFiles 'Tailscale')
Add-AvExclusion -Kind 'Process' -Value $tailscaled

# Сервер игры / проект
Add-AvExclusion -Kind 'Path' -Value $projectRoot
Add-AvExclusion -Kind 'Path' -Value (Join-Path $projectRoot 'server\build')

# Кэш Gradle (сервер и Android)
Add-AvExclusion -Kind 'Path' -Value (Join-Path $env:USERPROFILE '.gradle')

Write-Host ''
Write-Host 'Готово. Применённые правила Firewall:' -ForegroundColor Green
Get-NetFirewallRule -PolicyStore ActiveStore -DisplayName "$Prefix*" |
    ForEach-Object {
        $f = Get-NetFirewallApplicationFilter -AssociatedNetFirewallRule $_ -ErrorAction SilentlyContinue
        $p = Get-NetFirewallPortFilter -AssociatedNetFirewallRule $_ -ErrorAction SilentlyContinue
        [pscustomobject]@{
            Name     = $_.DisplayName
            Action   = $_.Action
            Direction= $_.Direction
            Program  = if ($f) { $f.Program } else { '' }
            Protocol = if ($p) { $p.Protocol } else { 'Any' }
            Port     = if ($p) { ($p.LocalPort -join ',') } else { 'Any' }
        }
    } | Format-Table -AutoSize

Write-Host 'Исключения Windows Defender Antivirus:' -ForegroundColor Green
$mp = Get-MpPreference
Write-Host "  Paths   :"
$mp.ExclusionPath | ForEach-Object { Write-Host "    - $_" }
Write-Host "  Processes:"
$mp.ExclusionProcess | ForEach-Object { Write-Host "    - $_" }