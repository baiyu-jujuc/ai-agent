#!/usr/bin/env pwsh
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$PlatformApiKey = "dev-key-change-in-production",
    [string]$AdminUsername = "demo_admin",
    [string]$AdminPassword = "Admin123!",
    [string]$ReaderUsername = "demo_reader",
    [string]$ReaderPassword = "Reader123!",
    [string]$SpaceName = "产品研发知识库（演示）"
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')
$repoRoot = Split-Path -Parent $PSScriptRoot

function Invoke-DemoRequest {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$Token,
        [hashtable]$Form
    )

    $headers = @{ "X-API-Key" = $PlatformApiKey }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }

    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $headers
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = ($Body | ConvertTo-Json -Compress)
    }
    if ($null -ne $Form) { $params.Form = $Form }

    $response = Invoke-WebRequest @params
    if ([int]$response.StatusCode -ge 400) {
        throw "HTTP $($response.StatusCode) $Path - $($response.Content)"
    }
    if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
    return $response.Content | ConvertFrom-Json
}

function Wait-ForApplication {
    for ($i = 1; $i -le 40; $i++) {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 3
            if ($health.status -eq "UP") { return }
        } catch {
            Start-Sleep -Seconds 3
        }
    }
    throw "应用在 120 秒内未就绪: $BaseUrl"
}

function Ensure-Account {
    param([string]$Username, [string]$Password)

    try {
        return Invoke-DemoRequest -Method POST -Path "/api/auth/register" -Body @{
            username = $Username
            password = $Password
        }
    } catch {
        return Invoke-DemoRequest -Method POST -Path "/api/auth/login" -Body @{
            username = $Username
            password = $Password
        }
    }
}

Write-Host "[1/5] 等待应用就绪..." -ForegroundColor Cyan
Wait-ForApplication

Write-Host "[2/5] 创建演示账号..." -ForegroundColor Cyan
$admin = Ensure-Account -Username $AdminUsername -Password $AdminPassword
$reader = Ensure-Account -Username $ReaderUsername -Password $ReaderPassword

Write-Host "[3/5] 创建知识空间..." -ForegroundColor Cyan
$spaces = @(Invoke-DemoRequest -Method GET -Path "/api/kb/spaces" -Token $admin.token)
$space = $spaces | Where-Object { $_.name -eq $SpaceName } | Select-Object -First 1
if (-not $space) {
    $space = Invoke-DemoRequest -Method POST -Path "/api/kb/spaces" -Token $admin.token -Body @{
        name = $SpaceName
        description = "用于演示版本管理、引用溯源、多轮问答与权限校验的脱敏知识空间"
        visibility = "team"
    }
}

$adminOnlyName = "$SpaceName - 管理员专属"
$adminOnly = $spaces | Where-Object { $_.name -eq $adminOnlyName } | Select-Object -First 1
if (-not $adminOnly) {
    $adminOnly = Invoke-DemoRequest -Method POST -Path "/api/kb/spaces" -Token $admin.token -Body @{
        name = $adminOnlyName
        description = "普通账号不可访问，用于演示空间级权限拒绝"
        visibility = "team"
    }
}

Invoke-DemoRequest -Method POST -Path "/api/kb/spaces/$($space.id)/members" -Token $admin.token -Body @{
    userId = $reader.userId
    role = "reader"
} | Out-Null

Write-Host "[4/5] 上传脱敏演示文档与版本..." -ForegroundColor Cyan
$documents = @(Invoke-DemoRequest -Method GET -Path "/api/kb/spaces/$($space.id)/documents" -Token $admin.token)
$handbook = $documents | Where-Object { $_.filename -eq "employee-handbook.md" } | Select-Object -First 1
if (-not $handbook) {
    Invoke-DemoRequest -Method POST -Path "/api/kb/spaces/$($space.id)/documents" -Token $admin.token -Form @{
        file = Get-Item "$repoRoot/docs/demo-data/employee-handbook.md"
    } | Out-Null
}

$releaseDoc = $documents | Where-Object { $_.filename -eq "release-manual.md" } | Select-Object -First 1
if (-not $releaseDoc) {
    $releaseDoc = Invoke-DemoRequest -Method POST -Path "/api/kb/spaces/$($space.id)/documents" -Token $admin.token -Form @{
        file = Get-Item "$repoRoot/docs/demo-data/release-manual-v1/release-manual.md"
    }
}

$versions = @(Invoke-DemoRequest -Method GET -Path "/api/kb/documents/$($releaseDoc.id)/versions" -Token $admin.token)
if ($versions.Count -lt 2) {
    Invoke-DemoRequest -Method POST -Path "/api/kb/spaces/$($space.id)/documents" -Token $admin.token -Form @{
        file = Get-Item "$repoRoot/docs/demo-data/release-manual-v2/release-manual.md"
    } | Out-Null
    $versions = @(Invoke-DemoRequest -Method GET -Path "/api/kb/documents/$($releaseDoc.id)/versions" -Token $admin.token)
}

$latestVersion = $versions | Sort-Object versionNo -Descending | Select-Object -First 1
$activeVersion = $versions | Where-Object { $_.active } | Select-Object -First 1
if ($latestVersion -and (!$activeVersion -or $activeVersion.versionNo -ne $latestVersion.versionNo)) {
    Invoke-DemoRequest -Method POST -Path "/api/kb/documents/$($releaseDoc.id)/rollback/$($latestVersion.versionNo)" -Token $admin.token | Out-Null
}

Write-Host "[5/5] 演示数据准备完成" -ForegroundColor Green
[pscustomobject]@{
    baseUrl = $BaseUrl
    platformApiKey = $PlatformApiKey
    adminUsername = $AdminUsername
    adminPassword = $AdminPassword
    readerUsername = $ReaderUsername
    readerPassword = $ReaderPassword
    spaceId = $space.id
    spaceName = $space.name
    adminOnlySpaceId = $adminOnly.id
    adminOnlySpaceName = $adminOnly.name
    releaseDocumentId = $releaseDoc.id
}
