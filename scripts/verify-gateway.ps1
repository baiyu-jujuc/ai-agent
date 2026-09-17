#
# Verify the AI gateway end to end with SIMULATED upstream failure.
#
# What it does:
#   1. loads secrets from .env into the process environment
#   2. starts the packaged jar with SPRING_AI_OPENAI_BASE_URL pointing at a
#      non-existent port (http://127.0.0.1:9) and resilience enabled
#   3. waits for /actuator/health
#   4. registers/logs in to get a JWT, then calls /api/chat/simple N times
#   5. prints per-call latency + response, and reads back:
#        - /actuator/prometheus  (custom llm_* metrics + breaker state) [requires JWT]
#        - /api/admin/usage      (ERROR / DEGRADED rows)                [requires ADMIN role]
#   6. stops the app
#
# IMPORTANT: this is a SIMULATED failure, not a production incident. Say it that
# way in the resume/interview: say it is a simulated upstream failure with a dead port.
#
# Usage: .\scripts\verify-gateway.ps1 [-Requests 8] [-Port 8090]
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 without a UTF-8 BOM as ANSI.
#
param(
    [int]$Requests = 8,
    [int]$Port = 8090,
    [string]$DeadUpstream = "http://127.0.0.1:9"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) {
    throw ".env not found (copy .env.example and fill in real keys)"
}
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
    }
}

$jar = "target\ai-agent-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path $jar)) {
    throw "$jar not found. Run: mvn -B verify"
}

# --- fault injection + resilience switches ---------------------------------
$env:SPRING_AI_OPENAI_BASE_URL = $DeadUpstream
$env:GATEWAY_RESILIENCE_ENABLED = "true"
$env:SERVER_PORT = "$Port"

# /api/admin/** needs the ADMIN role and /actuator/prometheus needs a JWT, so the
# verification user is put on the admin allowlist. A unique name per run avoids the
# "user already exists with role=user" trap when the script is run twice.
$verifyUser = "gateway-verify-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$env:ADMIN_USERNAMES = $verifyUser

$base = "http://localhost:$Port"
$outLog = "target\gateway-verify.log"
$errLog = "target\gateway-verify.err.log"
Remove-Item $outLog, $errLog -ErrorAction SilentlyContinue

Write-Host ("Starting app with dead upstream {0} on port {1} ..." -f $DeadUpstream, $Port) -ForegroundColor Cyan
$proc = Start-Process -FilePath "java" -ArgumentList @("-jar", $jar) `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden

try {
    $healthy = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 3
        try {
            $health = Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 5 -ErrorAction Stop
            if ($health.status -eq "UP") { $healthy = $true; break }
        } catch { }
    }
    if (-not $healthy) {
        throw "app did not become healthy; see $outLog"
    }
    Write-Host "App is UP" -ForegroundColor Green

    # --- credentials -------------------------------------------------------
    $apiKey = $env:AGENT_API_KEY
    if (-not $apiKey) { $apiKey = "dev-key-change-in-production" }

    $username = $verifyUser
    $password = "verify-123456"
    try {
        Invoke-RestMethod -Method Post -Uri "$base/api/auth/register" -ContentType "application/json" `
            -Body (@{ username = $username; password = $password } | ConvertTo-Json) -TimeoutSec 10 | Out-Null
        Write-Host "Registered verification user" -ForegroundColor DarkGray
    } catch {
        Write-Host "Verification user already exists (fine)" -ForegroundColor DarkGray
    }
    $login = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType "application/json" `
        -Body (@{ username = $username; password = $password } | ConvertTo-Json) -TimeoutSec 10
    $token = $login.token
    if (-not $token) { throw "login did not return a token" }
    $headers = @{ "X-API-Key" = $apiKey; "Authorization" = "Bearer $token" }

    # --- fire requests at the dead upstream --------------------------------
    Write-Host ""
    Write-Host ("Calling /api/chat/simple {0} times (upstream is dead) ..." -f $Requests) -ForegroundColor Cyan
    $results = @()
    for ($i = 1; $i -le $Requests; $i++) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        try {
            $body = @{ message = "circuit-breaker probe $i"; model = "deepseek-chat" } | ConvertTo-Json
            $response = Invoke-RestMethod -Method Post -Uri "$base/api/chat/simple" -Headers $headers `
                -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 60
            $answer = $response.response
        } catch {
            $answer = "[HTTP error] " + $_.Exception.Message
        }
        $sw.Stop()
        $ms = $sw.ElapsedMilliseconds
        $results += [pscustomobject]@{ Call = $i; Ms = $ms; Answer = $answer }
        Write-Host ("  call {0}: {1} ms -> {2}" -f $i, $ms, $answer)
    }

    # --- evidence: prometheus metrics --------------------------------------
    Write-Host ""
    Write-Host "=== /actuator/prometheus (llm_* and circuit breaker) ===" -ForegroundColor Cyan
    # First prove runtime data is NOT readable without a token (regression evidence
    # for the actuator hardening): expect an HTTP error here.
    try {
        Invoke-WebRequest -Uri "$base/actuator/prometheus" -TimeoutSec 20 -UseBasicParsing | Out-Null
        Write-Host "  [UNEXPECTED] prometheus was readable WITHOUT a token" -ForegroundColor Red
    } catch {
        Write-Host ("  without token -> blocked (" + $_.Exception.Response.StatusCode.value__ + ") as expected")
    }
    Write-Host "  GET /api/admin/usage as a non-admin user would return 403 (covered by AdminAccessSecurityTest)"
    try {
        $metrics = Invoke-WebRequest -Uri "$base/actuator/prometheus" -Headers $headers -TimeoutSec 20 -UseBasicParsing
        $lines = $metrics.Content -split "`n" | Where-Object { $_ -match "^llm_|^resilience4j_circuitbreaker" -and $_ -notmatch "^#" }
        if ($lines.Count -eq 0) {
            Write-Host "  no matching metric lines found" -ForegroundColor Yellow
        } else {
            $lines | Select-Object -First 25 | ForEach-Object { Write-Host ("  " + $_.Trim()) }
        }
    } catch {
        Write-Host ("  prometheus endpoint failed: " + $_.Exception.Message) -ForegroundColor Yellow
    }

    # --- evidence: usage records -------------------------------------------
    Write-Host ""
    Write-Host "=== /api/admin/usage (today) ===" -ForegroundColor Cyan
    try {
        $today = (Get-Date).ToString("yyyy-MM-dd")
        $usage = Invoke-RestMethod -Uri "$base/api/admin/usage?from=$today&to=$today" -Headers $headers -TimeoutSec 20
        Write-Host ("  calls={0}, errors={1}, degraded={2}" -f `
            $usage.summary.calls, $usage.summary.errors, $usage.summary.degraded)
        $usage.items | Select-Object -First 6 |
            Select-Object scene, modelId, routeType, outcome, promptTokens, costMicros, latencyMs |
            Format-Table -AutoSize | Out-String | Write-Host
    } catch {
        Write-Host ("  usage endpoint failed: " + $_.Exception.Message) -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "Done. NOTE: this was a SIMULATED upstream failure (dead port), not a real incident." -ForegroundColor Green
} finally {
    if ($proc -and -not $proc.HasExited) {
        Write-Host "Stopping app ..." -ForegroundColor DarkGray
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
}
