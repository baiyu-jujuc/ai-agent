#
# WSL2 Docker start/stop script
# Usage:
#   .\scripts\docker-start.ps1            Start Docker
#   .\scripts\docker-start.ps1 stop        Stop Docker and shutdown WSL2
#   .\scripts\docker-start.ps1 status       Check status
#   .\scripts\docker-start.ps1 compose up -d
#   .\scripts\docker-start.ps1 compose ps
#

param(
    [string]$Action = "start",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CommandArgs
)

$env:WSL_UTF8 = "1"
try {
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
} catch {
}

$wslNoisePattern = '(?i)(WSLONAT|localhost.*(代理|proxy)|NAT.*WSL|WSL.*NAT|不支持.*代理|not mirrored)'

function Show-WslOutput {
    param([object[]]$Output)

    $clean = @($Output | Where-Object { $_ -and ($_.ToString() -notmatch $wslNoisePattern) })
    if ($clean.Count -gt 0) {
        $clean | ForEach-Object { Write-Host $_ }
    }
}

function Get-WslRepoPath {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $drive = $repoRoot.Substring(0, 1).ToLowerInvariant()
    $remaining = $repoRoot.Substring(2).Replace('\', '/')
    return "/mnt/$drive$remaining"
}

$checkScript = 'if pgrep -x dockerd > /dev/null; then echo YES; else echo NO; fi'
$keepAlivePidFile = Join-Path $env:TEMP "ai-agent-wsl-keepalive.pid"

function Start-WslKeepAlive {
    if (Test-Path $keepAlivePidFile) {
        $existingPid = Get-Content $keepAlivePidFile -ErrorAction SilentlyContinue
        if ($existingPid -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) {
            Write-Host "  WSL keep-alive already running (PID $existingPid)" -ForegroundColor Yellow
            return
        }
    }

    $keepAlive = Start-Process -FilePath "wsl.exe" `
        -ArgumentList @("-d", "Debian", "--", "sleep", "infinity") `
        -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $keepAlivePidFile -Value $keepAlive.Id -Encoding ascii
    Write-Host "  WSL keep-alive started (PID $($keepAlive.Id))" -ForegroundColor Green
}

function Stop-WslKeepAlive {
    if (Test-Path $keepAlivePidFile) {
        $keepAlivePid = Get-Content $keepAlivePidFile -ErrorAction SilentlyContinue
        if ($keepAlivePid) {
            Stop-Process -Id $keepAlivePid -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $keepAlivePidFile -Force -ErrorAction SilentlyContinue
    }
}

switch ($Action.ToLower()) {
    "start" {
        Write-Host "[1/3] Starting WSL2 Debian..." -ForegroundColor Cyan
        $wslStarted = wsl -d Debian -- echo "WSL2 started" 2>&1
        Show-WslOutput -Output $wslStarted
        Write-Host "[2/3] Starting Docker daemon..." -ForegroundColor Cyan
        $running = (wsl -d Debian -- bash -c $checkScript 2>&1) -join ""
        if ($running -match "YES") {
            Write-Host "  Docker daemon already running" -ForegroundColor Yellow
        } else {
            wsl -d Debian -- bash -c 'nohup dockerd --host=unix:///var/run/docker.sock > /dev/null 2>&1 &'
            Start-Sleep 4
        }
        Write-Host "[3/3] Verifying Docker..." -ForegroundColor Cyan
        Show-WslOutput -Output (wsl -d Debian -- docker ps 2>&1)
        Start-WslKeepAlive
        Write-Host ""
        Write-Host "Docker started!" -ForegroundColor Green
        Write-Host "  In WSL2:      docker <command>"
        Write-Host "  From Windows: .\scripts\docker-start.ps1 compose <command>"
    }
    "stop" {
        Write-Host "Stopping Docker daemon..." -ForegroundColor Yellow
        Show-WslOutput -Output (wsl -d Debian -- bash -c 'pkill dockerd 2>/dev/null; echo done' 2>&1)
        Stop-WslKeepAlive
        Start-Sleep 2
        Write-Host "Shutting down WSL2 (releases memory)..." -ForegroundColor Yellow
        Show-WslOutput -Output (wsl --shutdown 2>&1)
        Write-Host "Docker stopped, WSL2 shutdown, memory released" -ForegroundColor Green
    }
    "status" {
        $wslRunning = (wsl -l --running 2>&1) -join ""
        if ($wslRunning -match "Debian") {
            Write-Host "WSL2 Debian:   [RUNNING]" -ForegroundColor Green
            $dockerRunning = (wsl -d Debian -- bash -c $checkScript 2>&1) -join ""
            if ($dockerRunning -match "YES") {
                Write-Host "Docker daemon: [RUNNING]" -ForegroundColor Green
                $dockerInfo = wsl -d Debian -- docker info 2>&1
                $cleanInfo = @($dockerInfo | Where-Object { $_ -and ($_.ToString() -notmatch $wslNoisePattern) })
                $cleanInfo | Select-String "Server Version|Containers:|Images:"
            } else {
                Write-Host "Docker daemon: [STOPPED]" -ForegroundColor Yellow
            }
            if (Test-Path $keepAlivePidFile) {
                $keepAlivePid = Get-Content $keepAlivePidFile -ErrorAction SilentlyContinue
                if ($keepAlivePid -and (Get-Process -Id $keepAlivePid -ErrorAction SilentlyContinue)) {
                    Write-Host "WSL keep-alive: [RUNNING] (PID $keepAlivePid)" -ForegroundColor Green
                } else {
                    Write-Host "WSL keep-alive: [STOPPED]" -ForegroundColor Yellow
                }
            } else {
                Write-Host "WSL keep-alive: [STOPPED]" -ForegroundColor Yellow
            }
        } else {
            Write-Host "WSL2 Debian:   [STOPPED]" -ForegroundColor Yellow
            Write-Host "Docker daemon: [STOPPED]" -ForegroundColor Yellow
        }
    }
    "compose" {
        if (-not $CommandArgs -or $CommandArgs.Count -eq 0) {
            Write-Host "Usage: docker-start.ps1 compose <docker compose arguments>" -ForegroundColor Yellow
            exit 1
        }

        $repoPath = Get-WslRepoPath
        $composeOutput = wsl -d Debian --cd $repoPath -- docker compose @CommandArgs 2>&1
        $composeExitCode = $LASTEXITCODE
        Show-WslOutput -Output $composeOutput
        exit $composeExitCode
    }
    default {
        Write-Host "Usage: docker-start.ps1 [start|stop|status|compose <args>]" -ForegroundColor Yellow
    }
}
