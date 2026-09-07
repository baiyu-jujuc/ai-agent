#
# WSL2 Docker start/stop script
# Usage:
#   .\scripts\docker-start.ps1            Start Docker
#   .\scripts\docker-start.ps1 stop        Stop Docker and shutdown WSL2
#   .\scripts\docker-start.ps1 status       Check status
#

param([string]$Action = "start")

$checkScript = 'if pgrep -x dockerd > /dev/null; then echo YES; else echo NO; fi'

switch ($Action.ToLower()) {
    "start" {
        Write-Host "[1/3] Starting WSL2 Debian..." -ForegroundColor Cyan
        wsl -d Debian -- echo "WSL2 started"
        Write-Host "[2/3] Starting Docker daemon..." -ForegroundColor Cyan
        $running = (wsl -d Debian -- bash -c $checkScript 2>&1) -join ""
        if ($running -match "YES") {
            Write-Host "  Docker daemon already running" -ForegroundColor Yellow
        } else {
            wsl -d Debian -- bash -c 'nohup dockerd --host=unix:///var/run/docker.sock > /dev/null 2>&1 &'
            Start-Sleep 4
        }
        Write-Host "[3/3] Verifying Docker..." -ForegroundColor Cyan
        wsl -d Debian -- docker ps 2>&1
        Write-Host ""
        Write-Host "Docker started!" -ForegroundColor Green
        Write-Host "  In WSL2:      docker <command>"
        Write-Host "  From Windows: wsl -d Debian -- docker <command>"
    }
    "stop" {
        Write-Host "Stopping Docker daemon..." -ForegroundColor Yellow
        wsl -d Debian -- bash -c 'pkill dockerd 2>/dev/null; echo done'
        Start-Sleep 2
        Write-Host "Shutting down WSL2 (releases memory)..." -ForegroundColor Yellow
        wsl --shutdown
        Write-Host "Docker stopped, WSL2 shutdown, memory released" -ForegroundColor Green
    }
    "status" {
        $wslRunning = (wsl -l --running 2>&1) -join ""
        if ($wslRunning -match "Debian") {
            Write-Host "WSL2 Debian:   [RUNNING]" -ForegroundColor Green
            $dockerRunning = (wsl -d Debian -- bash -c $checkScript 2>&1) -join ""
            if ($dockerRunning -match "YES") {
                Write-Host "Docker daemon: [RUNNING]" -ForegroundColor Green
                wsl -d Debian -- docker info 2>&1 | Select-String "Server Version|Containers:|Images:"
            } else {
                Write-Host "Docker daemon: [STOPPED]" -ForegroundColor Yellow
            }
        } else {
            Write-Host "WSL2 Debian:   [STOPPED]" -ForegroundColor Yellow
            Write-Host "Docker daemon: [STOPPED]" -ForegroundColor Yellow
        }
    }
    default {
        Write-Host "Usage: docker-start.ps1 [start|stop|status]" -ForegroundColor Yellow
    }
}
