# Docker Desktop 一键安装 + 镜像源配置脚本
# 使用方法: 开启代理后，右键 -> 用 PowerShell 运行

$ErrorActionPreference = "Stop"

Write-Host "===== Docker Desktop 安装脚本 =====" -ForegroundColor Cyan

# Step 1: Download Docker Desktop
$installerPath = "D:\DockerDesktopSetup.exe"
$proxyUrl = "http://127.0.0.1:7897"

if (-not (Test-Path $installerPath) -or (Get-Item $installerPath).Length -lt 100MB) {
    Write-Host "[1/4] 下载 Docker Desktop..." -ForegroundColor Yellow
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri "https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe" `
        -OutFile $installerPath -UseBasicParsing -TimeoutSec 600 -Proxy $proxyUrl
    $size = [math]::Round((Get-Item $installerPath).Length / 1MB, 1)
    Write-Host "  下载完成: $size MB" -ForegroundColor Green
} else {
    Write-Host "[1/4] Docker Desktop 已存在，跳过下载" -ForegroundColor Green
}

# Step 2: Install Docker Desktop
Write-Host "[2/4] 安装 Docker Desktop..." -ForegroundColor Yellow
Start-Process -FilePath $installerPath -ArgumentList "install","--quiet","--accept-license" -Wait
Write-Host "  安装完成" -ForegroundColor Green

# Step 3: Configure Chinese mirror sources
Write-Host "[3/4] 配置国内镜像源..." -ForegroundColor Yellow
$dockerConfigDir = "$env:USERPROFILE\.docker"
if (-not (Test-Path $dockerConfigDir)) {
    New-Item -ItemType Directory -Path $dockerConfigDir -Force | Out-Null
}

$daemonJson = @"
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.xuanyuan.me",
    "https://docker.m.daocloud.io",
    "https://mirror.ccs.tencentyun.com",
    "https://docker.mirrors.ustc.edu.cn",
    "https://hub-mirror.c.163.com",
    "https://docker.mirrors.sjtug.sjtu.edu.cn"
  ],
  "insecure-registries": [],
  "debug": true,
  "experimental": false,
  "builder": {
    "gc": {
      "enabled": true,
      "defaultKeepStorage": "20GB"
    }
  },
  "features": {
    "buildkit": true
  },
  "dns": ["223.5.5.5", "223.6.6.6"]
}
"@

$daemonJson | Out-File -FilePath "$dockerConfigDir\daemon.json" -Encoding ascii -Force
Write-Host "  镜像源已配置: $dockerConfigDir\daemon.json" -ForegroundColor Green
Write-Host "  配置的镜像源:" -ForegroundColor Gray
Write-Host "    - docker.1ms.run" -ForegroundColor Gray
Write-Host "    - docker.m.daocloud.io" -ForegroundColor Gray
Write-Host "    - mirror.ccs.tencentyun.com (腾讯云)" -ForegroundColor Gray
Write-Host "    - docker.mirrors.ustc.edu.cn (中科大)" -ForegroundColor Gray
Write-Host "    - hub-mirror.c.163.com (网易)" -ForegroundColor Gray
Write-Host "    - docker.mirrors.sjtug.sjtu.edu.cn (上海交大)" -ForegroundColor Gray

# Step 4: Start Docker and verify
Write-Host "[4/4] 启动 Docker Desktop..." -ForegroundColor Yellow
$dockerPath = "${env:ProgramFiles}\Docker\Docker\Docker Desktop.exe"
if (Test-Path $dockerPath) {
    Start-Process -FilePath $dockerPath
    Write-Host "  Docker Desktop 已启动，等待初始化 (30秒)..." -ForegroundColor Yellow
    Start-Sleep -Seconds 30
} else {
    Write-Host "  未找到 Docker Desktop，可能需要重启电脑" -ForegroundColor Red
}

Write-Host ""
Write-Host "===== 安装完成 =====" -ForegroundColor Cyan
Write-Host "如果 Docker 没有自动启动，请重启电脑后手动打开 Docker Desktop" -ForegroundColor Yellow
Write-Host "镜像源已配置，拉取镜像将使用国内源，速度大幅提升" -ForegroundColor Green

Read-Host "按 Enter 退出"
