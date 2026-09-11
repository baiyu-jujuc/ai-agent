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

function Invoke-DemoRequest {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$Token,
        [switch]$AllowFailure
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

    for ($attempt = 1; $attempt -le 7; $attempt++) {
        $response = Invoke-WebRequest @params
        if ([int]$response.StatusCode -ne 429 -or $attempt -eq 7) { break }
        Write-Host "  接口限流，10 秒后重试（$attempt/6）..." -ForegroundColor Yellow
        Start-Sleep -Seconds 10
    }
    if ([int]$response.StatusCode -ge 400) {
        if ($AllowFailure) {
            return [pscustomobject]@{ statusCode = [int]$response.StatusCode; content = $response.Content }
        }
        throw "HTTP $($response.StatusCode) $Path - $($response.Content)"
    }
    if ([string]::IsNullOrWhiteSpace($response.Content)) { return $null }
    return $response.Content | ConvertFrom-Json
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "验证失败: $Message" }
    Write-Host "  PASS: $Message" -ForegroundColor Green
}

Write-Host "[1/6] 准备旅游行业演示数据..." -ForegroundColor Cyan
$prepared = & "$PSScriptRoot/demo-prepare-travel.ps1" -BaseUrl $BaseUrl -PlatformApiKey $PlatformApiKey `
    -AdminUsername $AdminUsername -AdminPassword $AdminPassword `
    -ReaderUsername $ReaderUsername -ReaderPassword $ReaderPassword `
    -SpaceName $SpaceName

$admin = Invoke-DemoRequest -Method POST -Path "/api/auth/login" -Body @{
    username = $AdminUsername; password = $AdminPassword
}
$reader = Invoke-DemoRequest -Method POST -Path "/api/auth/login" -Body @{
    username = $ReaderUsername; password = $ReaderPassword
}

Write-Host "[2/6] 验证旅游退改 v2 问答与引用..." -ForegroundColor Cyan
$conversationId = "travel-smoke-" + [guid]::NewGuid().ToString("N")
$ask1 = Invoke-DemoRequest -Method POST -Path "/api/kb/spaces/$($prepared.spaceId)/ask" -Token $admin.token -Body @{
    question = "申请退改时提前多少小时可以免平台服务费？"
    conversationId = $conversationId
}
Assert-True ($ask1.citations.Count -ge 1) "回答返回引用"
Assert-True (@($ask1.citations | Where-Object { $_.versionNo -eq 2 }).Count -ge 1) "首次问答引用退改制度版本 2"
Assert-True (-not [string]::IsNullOrWhiteSpace($ask1.confidence)) "回答包含置信度"

Write-Host "[3/6] 验证多轮上下文与反馈..." -ForegroundColor Cyan
$ask2 = Invoke-DemoRequest -Method POST -Path "/api/kb/spaces/$($prepared.spaceId)/ask" -Token $admin.token -Body @{
    question = "那台风导致行程取消时，第一步要做什么？"
    conversationId = $conversationId
}
Assert-True ($ask2.citations.Count -ge 1) "追问仍然返回知识库引用"
Invoke-DemoRequest -Method POST -Path "/api/kb/feedback" -Token $admin.token -Body @{
    messageId = $ask2.messageId; spaceId = $prepared.spaceId; thumbs = "up"; reason = "旅游行业演示链路验证"
} | Out-Null
Assert-True $true "反馈写入成功"

Write-Host "[4/6] 回滚到退改制度 v1 并验证引用切换..." -ForegroundColor Cyan
Invoke-DemoRequest -Method POST -Path "/api/kb/documents/$($prepared.refundDocumentId)/rollback/1" -Token $admin.token | Out-Null
$ask3 = Invoke-DemoRequest -Method POST -Path "/api/kb/spaces/$($prepared.spaceId)/ask" -Token $admin.token -Body @{
    question = "申请退改时提前多少小时可以免平台服务费？"
    conversationId = "travel-smoke-after-rollback-" + [guid]::NewGuid().ToString("N")
}
Assert-True (@($ask3.citations | Where-Object { $_.versionNo -eq 1 }).Count -ge 1) "回滚后问答引用退改制度版本 1"
Invoke-DemoRequest -Method POST -Path "/api/kb/documents/$($prepared.refundDocumentId)/rollback/2" -Token $admin.token | Out-Null
Assert-True $true "验收结束后恢复退改制度版本 2"

Write-Host "[5/6] 验证普通账号不能访问管理员空间..." -ForegroundColor Cyan
$forbidden = Invoke-DemoRequest -Method GET -Path "/api/kb/spaces/$($prepared.adminOnlySpaceId)/documents" -Token $reader.token -AllowFailure
Assert-True ($forbidden.statusCode -eq 403) "未授权空间返回 403"

Write-Host "[6/6] 旅游行业演示链路全部通过" -ForegroundColor Green
[pscustomobject]@{
    spaceId = $prepared.spaceId
    conversationId = $conversationId
    firstAnswerMessageId = $ask1.messageId
    followUpMessageId = $ask2.messageId
    rollbackAnswerMessageId = $ask3.messageId
    restoredVersion = 2
}
