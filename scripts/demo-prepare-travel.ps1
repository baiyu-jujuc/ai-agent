#!/usr/bin/env pwsh
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$PlatformApiKey = "dev-key-change-in-production",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "123456",
    [string]$ReaderUsername = "reader",
    [string]$ReaderPassword = "123456",
    [string]$SpaceName = "旅游客服政策知识库（演示）"
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

    for ($attempt = 1; $attempt -le 7; $attempt++) {
        $response = Invoke-WebRequest @params
        if ([int]$response.StatusCode -ne 429 -or $attempt -eq 7) { break }
        Write-Host "  接口限流，10 秒后重试（$attempt/6）..." -ForegroundColor Yellow
        Start-Sleep -Seconds 10
    }
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

function Ensure-Document {
    param(
        [string]$SpaceId,
        [string]$Token,
        [string]$Filename,
        [string]$File
    )

    $documents = @(Invoke-DemoRequest -Method GET -Path "/api/kb/spaces/$SpaceId/documents" -Token $Token)
    $document = $documents | Where-Object { $_.filename -eq $Filename } | Select-Object -First 1
    if ($document) { return $document }

    return Invoke-DemoRequest -Method POST -Path "/api/kb/spaces/$SpaceId/documents" -Token $Token -Form @{
        file = Get-Item $File
    }
}

Write-Host "[1/5] 等待应用就绪..." -ForegroundColor Cyan
Wait-ForApplication

Write-Host "[2/5] 创建演示账号..." -ForegroundColor Cyan
$admin = Ensure-Account -Username $AdminUsername -Password $AdminPassword
$reader = Ensure-Account -Username $ReaderUsername -Password $ReaderPassword

Write-Host "[3/5] 创建旅游客服知识空间..." -ForegroundColor Cyan
$spaces = @(Invoke-DemoRequest -Method GET -Path "/api/kb/spaces" -Token $admin.token)
$space = $spaces | Where-Object { $_.name -eq $SpaceName } | Select-Object -First 1
if (-not $space) {
    $space = Invoke-DemoRequest -Method POST -Path "/api/kb/spaces" -Token $admin.token -Body @{
        name = $SpaceName
        description = "根据公开法规与虚构退改制度制作的旅游 OTA 知识库演示空间"
        visibility = "team"
    }
}

$adminOnlyName = "$SpaceName - 管理员专属"
$adminOnly = $spaces | Where-Object { $_.name -eq $adminOnlyName } | Select-Object -First 1
if (-not $adminOnly) {
    $adminOnly = Invoke-DemoRequest -Method POST -Path "/api/kb/spaces" -Token $admin.token -Body @{
        name = $adminOnlyName
        description = "普通账号不可访问，用于演示服务端空间权限校验"
        visibility = "team"
    }
}

Invoke-DemoRequest -Method POST -Path "/api/kb/spaces/$($space.id)/members" -Token $admin.token -Body @{
    userId = $reader.userId
    role = "reader"
} | Out-Null

Write-Host "[4/5] 上传旅游行业演示文档与版本..." -ForegroundColor Cyan
Ensure-Document -SpaceId $space.id -Token $admin.token -Filename "regulatory-baseline.md" -File "$repoRoot/docs/demo-data/travel-ota/regulatory-baseline.md" | Out-Null
Ensure-Document -SpaceId $space.id -Token $admin.token -Filename "customer-service-escalation.md" -File "$repoRoot/docs/demo-data/travel-ota/customer-service-escalation.md" | Out-Null

$refundDoc = Ensure-Document -SpaceId $space.id -Token $admin.token -Filename "refund-change-policy.md" -File "$repoRoot/docs/demo-data/travel-ota/refund-change-policy-v1/refund-change-policy.md"
$versions = @(Invoke-DemoRequest -Method GET -Path "/api/kb/documents/$($refundDoc.id)/versions" -Token $admin.token)
if ($versions.Count -lt 2) {
    Invoke-DemoRequest -Method POST -Path "/api/kb/spaces/$($space.id)/documents" -Token $admin.token -Form @{
        file = Get-Item "$repoRoot/docs/demo-data/travel-ota/refund-change-policy-v2/refund-change-policy.md"
    } | Out-Null
    $versions = @(Invoke-DemoRequest -Method GET -Path "/api/kb/documents/$($refundDoc.id)/versions" -Token $admin.token)
}

$latestVersion = $versions | Sort-Object versionNo -Descending | Select-Object -First 1
$activeVersion = $versions | Where-Object { $_.active } | Select-Object -First 1
if ($latestVersion -and (!$activeVersion -or $activeVersion.versionNo -ne $latestVersion.versionNo)) {
    Invoke-DemoRequest -Method POST -Path "/api/kb/documents/$($refundDoc.id)/rollback/$($latestVersion.versionNo)" -Token $admin.token | Out-Null
}

Write-Host "[5/5] 旅游演示数据准备完成" -ForegroundColor Green
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
    refundDocumentId = $refundDoc.id
    activeVersion = $latestVersion.versionNo
}
