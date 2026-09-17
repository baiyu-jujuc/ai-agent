#
# Run the offline evaluation locally.
#
# NOTE (why this file is English-only): Windows PowerShell 5.1 reads .ps1 files
# without a UTF-8 BOM as ANSI, so non-ASCII comments/messages break the parser.
# Keep this script ASCII-only; Chinese explanations live in eval/README.md.
#
# Usage:
#   .\scripts\run-eval-local.ps1                          # full run (67 cases), tag=current
#   .\scripts\run-eval-local.ps1 -Tag post-upgrade -Limit 10
#   .\scripts\run-eval-local.ps1 -Tag baseline -NoSeed    # spaces/docs already seeded
#
# It loads secrets from .env into the process environment (never printed, never on
# the command line), starts the app with --spring.main.web-application-type=none so
# the process exits after the run, and writes logs to target/eval-run.log.
#
param(
    [string]$Tag = "current",
    [int]$Limit = 0,
    [switch]$NoSeed
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) {
    throw ".env not found (copy .env.example and fill in real keys)"
}

$loadedKeys = @()
Get-Content -Encoding UTF8 $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        $name = $matches[1]
        $value = $matches[2].Trim()
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
        $loadedKeys += $name
    }
}
Write-Host ("Loaded {0} variables from .env (values not printed)" -f $loadedKeys.Count) -ForegroundColor Cyan

$arguments = @("--eval.run=true", "--eval.tag=$Tag", "--spring.main.web-application-type=none")
if ($Limit -gt 0) { $arguments += "--eval.limit=$Limit" }
if ($NoSeed) { $arguments += "--eval.no-seed=true" }
$argString = $arguments -join " "

Write-Host ("Starting evaluation: {0}" -f $argString) -ForegroundColor Cyan
Write-Host "Log: target/eval-run.log ; reports: eval/reports/" -ForegroundColor DarkGray

New-Item -ItemType Directory -Force -Path "target" | Out-Null
mvn -B spring-boot:run "-Dspring-boot.run.arguments=$argString" *> target\eval-run.log
$exitCode = $LASTEXITCODE

Write-Host ("Maven exit code: {0}" -f $exitCode) -ForegroundColor Cyan
Get-ChildItem "eval\reports" -ErrorAction SilentlyContinue | Select-Object Name, Length, LastWriteTime
exit $exitCode
