#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Docker verification: build image, start services, health check.
.DESCRIPTION
    Builds the Docker image and runs docker compose up with Qdrant + Redis.
    Waits for health checks before reporting success.
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "=== Docker Verification ===" -ForegroundColor Cyan

Write-Host "Step 1: Build image" -ForegroundColor Yellow
docker compose build
if ($LASTEXITCODE -ne 0) {
    Write-Host "FAILED: docker compose build" -ForegroundColor Red
    exit 1
}

Write-Host "Step 2: Start services" -ForegroundColor Yellow
docker compose up -d
if ($LASTEXITCODE -ne 0) {
    Write-Host "FAILED: docker compose up" -ForegroundColor Red
    exit 1
}

Write-Host "Step 3: Wait for health checks (max 60s)" -ForegroundColor Yellow
$maxWait = 60
$waited = 0
$healthy = $false
while ($waited -lt $maxWait) {
    Start-Sleep -Seconds 3
    $waited += 3
    try {
        $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 5 -ErrorAction Stop
        if ($health.status -eq "UP") {
            $healthy = $true
            break
        }
    } catch {
        Write-Host "  Waiting... ($waited s)" -ForegroundColor DarkGray
    }
}

if (!$healthy) {
    Write-Host "FAILED: App not healthy after $maxWait seconds" -ForegroundColor Red
    docker compose logs --tail=50
    docker compose down
    exit 1
}

Write-Host "  App is healthy" -ForegroundColor Green

Write-Host "Step 4: Verify API endpoints" -ForegroundColor Yellow
try {
    $models = Invoke-RestMethod -Uri "http://localhost:8080/api/chat/models" -TimeoutSec 5
    Write-Host "  Models: $($models.Count) available" -ForegroundColor Green

    $status = Invoke-RestMethod -Uri "http://localhost:8080/api/chat/storage-status" -TimeoutSec 5
    Write-Host "  Memory: $($status.memoryBackend)" -ForegroundColor Green
    Write-Host "  Vector: $($status.vectorStoreBackend)" -ForegroundColor Green
} catch {
    Write-Host "  WARNING: API check failed: $($_.Exception.Message)" -ForegroundColor DarkYellow
}

Write-Host "Step 5: Cleanup" -ForegroundColor Yellow
docker compose down
Write-Host "=== Docker verification passed ===" -ForegroundColor Green
exit 0
