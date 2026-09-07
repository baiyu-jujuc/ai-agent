#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Local verification: clean build + test from a fresh state.
.DESCRIPTION
    Runs mvn clean verify without Docker or API key.
    Exits 0 on success, non-zero on failure.
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "=== Local Verification ===" -ForegroundColor Cyan
Write-Host "Step 1: Clean build + test (no Docker, no API key)" -ForegroundColor Yellow
mvn clean verify --no-transfer-progress
if ($LASTEXITCODE -ne 0) {
    Write-Host "FAILED: mvn clean verify exit code $LASTEXITCODE" -ForegroundColor Red
    exit 1
}

Write-Host "Step 2: Verify artifacts" -ForegroundColor Yellow
$jar = Get-ChildItem -Path "target" -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (!$jar) {
    Write-Host "FAILED: No JAR found in target/" -ForegroundColor Red
    exit 1
}
Write-Host "  JAR: $($jar.Name) ($([math]::Round($jar.Length/1KB)) KB)" -ForegroundColor Green

Write-Host "=== Local verification passed ===" -ForegroundColor Green
exit 0
