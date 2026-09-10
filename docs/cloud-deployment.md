# 云服务器部署清单

本文件用于把当前项目部署到一台 Linux 云服务器，供 HR 或面试官在线体验。项目已经提供 MySQL、Redis、Qdrant 和应用的一体化 Compose，不需要单独购买数据库服务。

## 一、建议配置

| 项目 | 建议 |
| --- | --- |
| 云厂商 | 阿里云、腾讯云或华为云 |
| 配置 | 2 核 4GB 起步，40GB 系统盘 |
| 系统 | Ubuntu 22.04/24.04 LTS |
| 带宽 | 3Mbps 起，演示足够 |
| 域名 | 可选；有域名可启用 HTTPS，没有则用公网 IP:8080 演示 |

不要使用 1 核 1GB 服务器同时运行 MySQL、Redis、Qdrant 和应用，容易出现 OOM。

## 二、服务器初始化

以 Ubuntu 为例：

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-v2 git
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
newgrp docker
```

如果 `docker compose version` 不可用，请按 Docker 官方文档安装 Compose Plugin。

## 三、上传代码与配置

```bash
git clone https://github.com/baiyu-jujuc/ai-agent.git
cd ai-agent
cp .env.example .env
```

编辑 `.env`，至少修改以下值：

```dotenv
DEEPSEEK_API_KEY=你的真实模型Key
AGENT_API_KEY=随机生成的长平台Key
JWT_SECRET=至少32位随机字符串
MYSQL_PASSWORD=强数据库密码
MYSQL_ROOT_PASSWORD=强Root密码
ALLOWED_ORIGINS=http://你的公网IP:8080
ALLOW_CLIENT_MODEL_KEY=false
```

不要提交 `.env`。如果页面需要让访问者自带模型 Key，再将 `ALLOW_CLIENT_MODEL_KEY=true`；此时服务端 Key 可以只保留占位值，但平台 Key、JWT 和数据库密码仍必须是强随机值。

## 四、启动与验收

```bash
docker compose pull
docker compose up -d --build
docker compose ps
curl http://127.0.0.1:8080/actuator/health
```

预期四个服务处于运行状态，健康检查返回：

```json
{"status":"UP"}
```

随后在本地执行：

```powershell
.\scripts\demo-smoke.ps1 -BaseUrl "http://公网IP:8080" -PlatformApiKey "平台Key"
```

## 五、安全组配置

只开放以下端口：

| 端口 | 用途 |
| --- | --- |
| 22 | SSH 管理，最好限制为你的固定 IP |
| 8080 | 临时直接演示 |
| 80/443 | 配置域名和 HTTPS 后使用 |

MySQL 3306、Redis 6379、Qdrant 6333/6334 均未在 Compose 中映射到宿主机，不要额外开放。

## 六、HTTPS 建议

有域名时，推荐在云服务器上用 Caddy 反向代理 `127.0.0.1:8080`。Caddy 可以自动申请和续期证书；配置域名解析后，再将安全组 80/443 开放即可。

如果暂时没有域名，可先使用 `http://公网IP:8080` 供短期面试演示。演示结束后关闭服务器或修改平台 Key，避免产生持续模型费用。

## 七、更新与回滚

代码更新：

```bash
git pull --ff-only
docker compose up -d --build
```

查看日志：

```bash
docker compose logs -f --tail=200 app
```

停止服务但保留数据：

```bash
docker compose down
```

只有确认不再需要演示数据时，才执行：

```bash
docker compose down -v
```

`-v` 会删除 MySQL、Redis 和 Qdrant 的数据卷。
