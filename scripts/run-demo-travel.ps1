param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$PlatformApiKey = "dev-key-change-in-production"
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

& $pwsh -NoProfile -ExecutionPolicy Bypass -File $prepare -BaseUrl $BaseUrl -PlatformApiKey $PlatformApiKey
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $pwsh -NoProfile -ExecutionPolicy Bypass -File $smoke -BaseUrl $BaseUrl -PlatformApiKey $PlatformApiKey
exit $LASTEXITCODE
