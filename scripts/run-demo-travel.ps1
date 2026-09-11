param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$PlatformApiKey = "dev-key-change-in-production",
    [string]$AdminUsername = "demo_admin",
    [string]$AdminPassword = "Admin123!",
    [string]$ReaderUsername = "demo_reader",
    [string]$ReaderPassword = "Reader123!",
    [string]$SpaceName = ""
)

$ErrorActionPreference = "Stop"

$candidates = @()
$command = Get-Command pwsh.exe -ErrorAction SilentlyContinue
if ($command) {
    $candidates += $command.Source
}
$candidates += @(
    (Join-Path $env:ProgramFiles "PowerShell\7\pwsh.exe"),
    (Join-Path $env:LOCALAPPDATA "Microsoft\WindowsApps\pwsh.exe"),
    (Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe")
)

$pwsh = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $pwsh) {
    throw "PowerShell 7 was not found. Install it with: winget install --id Microsoft.PowerShell --source winget"
}

$prepare = Join-Path $PSScriptRoot "demo-prepare-travel.ps1"
$smoke = Join-Path $PSScriptRoot "demo-smoke-travel.ps1"
$common = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File")
$argsForPrepare = $common + @(
    $prepare,
    "-BaseUrl", $BaseUrl,
    "-PlatformApiKey", $PlatformApiKey,
    "-AdminUsername", $AdminUsername,
    "-AdminPassword", $AdminPassword,
    "-ReaderUsername", $ReaderUsername,
    "-ReaderPassword", $ReaderPassword
)
if ($SpaceName) {
    $argsForPrepare += @("-SpaceName", $SpaceName)
}
$argsForSmoke = $common + @(
    $smoke,
    "-BaseUrl", $BaseUrl,
    "-PlatformApiKey", $PlatformApiKey,
    "-AdminUsername", $AdminUsername,
    "-AdminPassword", $AdminPassword,
    "-ReaderUsername", $ReaderUsername,
    "-ReaderPassword", $ReaderPassword
)
if ($SpaceName) {
    $argsForSmoke += @("-SpaceName", $SpaceName)
}

& $pwsh @argsForPrepare
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $pwsh @argsForSmoke
exit $LASTEXITCODE
