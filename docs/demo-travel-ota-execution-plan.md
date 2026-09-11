# 旅游 OTA 企业知识库 Demo 执行方案

这份方案面向南京的 Java 后端、Java 全栈和 AI 应用开发岗位，目标是把当前项目演示成“企业知识库平台”，而不是普通聊天 Demo。场景参考在线旅游行业，但不会冒充途牛官方，也不会引入途牛内部资料。

## 一、演示目标

用一条完整链路证明以下能力：

1. 普通账号和管理员账号拥有不同知识空间权限。
2. 同一份旅游退改制度可以形成 v1、v2，并可回滚。
3. 回答能够返回文档、版本、分块、引用编号和置信度。
4. 多轮追问保留会话上下文，但不会跨用户、跨空间串联。
5. 用户反馈能够关联到具体回答，形成后续质量优化入口。

视频讲解时不要只描述 Spring Boot、MySQL、Redis 等框架，而要重点说明“为什么这样设计”和“业务问题如何被解决”。

## 二、资料获取与合法边界

推荐采用“官方公开法规摘要 + 自建脱敏业务制度”的组合。这样既有旅游行业针对性，也不会把第三方公司的版权材料、内部制度或用户数据放进仓库。

### 2.1 可直接使用的公开法规

- 《中华人民共和国旅游法》：https://www.gov.cn/flfg/2013-04/25/content_2390945.htm
- 《在线旅游经营服务管理暂行规定》：https://www.gov.cn/zhengce/zhengceku/2020-09/01/content_5538951.htm

这两份文件来自中国政府网。项目中只保存与实际业务相关的摘要、原文链接和访问日期，不复制整篇法规。演示时可将链接写入 README 或答辩材料，说明规则来源。

### 2.2 途牛官网资料应该怎么用

途牛官网、帮助中心、用户协议和隐私政策属于第三方公开页面，可以人工阅读和引用链接，但不建议批量爬取、复制全文或随仓库公开再分发。“公开可访问”不等于“可以任意复制和二次发布”。

如果确实需要在本地演示用户协议或退改政策，建议：

1. 只下载 1 至 2 个公开页面页面的 PDF 或 HTML，不进行批量抓取。
2. 文件名中保留来源、页面名称和抓取日期。
3. 只在本地内网演示，不提交到 Git，也不在公网服务器向陌生人开放。
4. 录用流程结束后删除原始第三方文件，只保留自己的摘要和出处。
5. 页面已有更权威的法律或监管原文时，优先使用政府公开文件。

### 2.3 推荐提交到仓库的演示数据

本方案已经准备了以下脱敏材料：

- `docs/demo-data/travel-ota/regulatory-baseline.md`
- `docs/demo-data/travel-ota/refund-change-policy-v1/refund-change-policy.md`
- `docs/demo-data/travel-ota/refund-change-policy-v2/refund-change-policy.md`
- `docs/demo-data/travel-ota/customer-service-escalation.md`

所有制度和数字都是虚构的测试数据，只用于验证系统能力，不代表任何企业的真实政策。两个退改文件必须使用相同的文件名上传，这样可以形成同一文档的两个版本。

## 三、阿里云和腾讯云怎么选

建议优先使用“轻量应用服务器”，不要购买 1 核 1GB。MySQL、Redis、Qdrant 和 Spring Boot 同时运行，2GB 内存容易 OOM。

| 方案 | 适合情况 | 优点 | 注意点 |
| --- | --- | --- | --- |
| 腾讯云轻量 Hong Kong，2 核 4GB | 1 至 4 周临时面试演示 | 不需要域名备案，开箱即用 | 跨境访问延迟略高，价格与活动经常变化 |
| 腾讯云轻量，华东地域，2 核 4GB | 长期国内展示 | 面向南京访问速度稳定，控制台简单 | 绑定域名并长期对公网提供服务时，需要关注 ICP 备案 |
| 阿里云轻量应用服务器，华东地域，2 核 4GB | 简历长期展示、希望使用阿里云生态 | 生态完整，便于后续加域名、监控和对象存储 | 续费价格通常高于新用户活动价，购买前先看续费价 |

建议结论：

- 只做面试演示：优先腾讯云轻量 Hong Kong，2 核 4GB、40GB 系统盘、Ubuntu 24.04 LTS。
- 想长期放在线作品集：优先腾讯云或阿里云华东地域，搭配域名和 HTTPS，按备案要求处理。
- 带宽选择 3Mbps 起步，文字问答和几十 MB 的文档足够。
- 不要单独购买云数据库，当前 Compose 已包含 MySQL、Redis 和 Qdrant。

购买前必须以结算页为准。不同实名认证、学生认证、地域和促销时间会改变价格。

## 四、本地录制前的完整步骤

### 第 1 步：确认 Docker 环境

当前机器的 Docker 在 WSL2 Debian 内，Windows PowerShell 中直接执行 `docker` 可能找不到命令。统一使用：

```powershell
wsl -d Debian -- docker version
```

进入项目目录并启动：

```powershell
.\scripts\docker-start.ps1 start
wsl -d Debian -- bash -lc 'cd "/mnt/d/AI Agent (test)" && docker compose up -d --build'
wsl -d Debian -- bash -lc 'cd "/mnt/d/AI Agent (test)" && docker compose ps'
```

预期 `app`、`mysql`、`redis`、`qdrant` 均正常运行，浏览器访问 `http://localhost:8080`。

### 第 2 步：准备演示账号

```powershell
.\scripts\demo-prepare.ps1
```

如果希望自动创建旅游客服空间并上传本方案的四份旅游文档，可以执行：

```powershell
.\scripts\demo-prepare-travel.ps1
```

脚本会把 `refund-change-policy.md` 的 v1、v2 上传到同一个空间，并确保最新版本处于启用状态。数据库已有同名文档时，脚本会复用版本记录，不会重复创建空间。

准备完成后执行旅游场景自动验收：

```powershell
.\scripts\demo-smoke-travel.ps1
```

该脚本会验证 v2 引用、多轮追问、反馈、回滚到 v1 后的引用切换，以及普通账号访问管理员空间返回 403。

如果当前终端是 Windows PowerShell 5.1，或系统 PATH 中没有 `pwsh.exe`，可以直接使用兼容启动器：

```powershell
cd "D:\AI Agent (test)"
.\scripts\run-demo-travel.ps1
```

启动器会自动定位 PowerShell 7，执行数据准备和旅游场景验收，不需要手工调用 `pwsh.exe`。

需要修改演示账号时，把同一组参数交给启动器，准备和验收脚本会自动保持一致：

```powershell
.\scripts\run-demo-travel.ps1 `
  -AdminUsername "admin" -AdminPassword "123456" `
  -ReaderUsername "reader" -ReaderPassword "123456" `
  -SpaceName "旅游客服政策知识库（简历演示）"
```

脚本只会创建新账号或复用同名且密码一致的账号，不会修改已经存在账号的密码。需要更换密码时，请改用新的用户名，或者清理演示数据库后重新执行准备脚本。

`admin / 123456` 和 `reader / 123456` 只用于本地录屏演示。公网部署前必须改为强密码，避免任何人直接登录演示环境。

如果希望把旅游行业文档设为演示主数据，可以在脚本执行后登录管理员账号，在“研发知识库”或新建的“客服政策知识库”中上传：

1. `regulatory-baseline.md`
2. `customer-service-escalation.md`
3. 先上传 `refund-change-policy-v1/refund-change-policy.md`
4. 再上传 `refund-change-policy-v2/refund-change-policy.md`

第 3、4 步必须选择同一个知识空间，并保持文件名为 `refund-change-policy.md`。上传后确认版本面板显示 v1、v2，且 v2 为当前版本。

### 第 3 步：跑自动验收

```powershell
.\scripts\demo-smoke.ps1
```

当前自动化脚本主要验证原有发布手册链路。旅游文档上传后，至少手工确认以下问题：

1. “申请退改时提前多少小时可以免平台服务费？”应引用 v2 的 72 小时。
2. 回滚到 v1 后重复提问，应引用 v1 的 48 小时。
3. “不可抗力导致行程无法继续时，应先做什么？”应引用法规摘要和客服升级制度。
4. 普通账号访问管理员空间时返回 403 或无权限提示。

### 第 4 步：录制前清理

- 关闭 `.env`、终端、浏览器开发者工具和包含 API Key 的窗口。
- 浏览器缩放设为 100%，隐藏书签栏和无关标签页。
- 使用 1920x1080，优先 `Win + G` 或 OBS 录制。
- 上传前再次检查文档没有真实手机号、订单号、姓名、邮箱和公司内部内容。

## 五、2 至 3 分钟录屏脚本

### 00:00-00:15 启动结果

画面：`docker compose ps` 中四个服务运行。

口播：这是一个面向企业知识库的 Java AI 应用，使用 Spring Boot、Spring AI、MySQL、Redis 和 Qdrant，重点解决文档版本、权限隔离和引用可追溯。

### 00:15-00:35 多知识空间与权限

画面：管理员登录，切换“客服政策知识库”和“研发知识库”。

口播：系统以知识空间隔离数据。用户只能检索被授权空间，权限判断在服务端执行，不依赖前端隐藏按钮。

### 00:35-01:05 文档版本与回滚

画面：上传退改政策 v1、v2，展示版本列表，回滚到 v1。

口播：同名文档再次上传会生成新版本。回滚不会删除历史版本，查询时只会召回当前有效版本的分块。

### 01:05-01:35 引用和置信度

画面：提问“申请退改时提前多少小时可以免平台服务费？”，展开引用。

口播：答案返回引用编号、文件名、版本、分块和相似度。置信度较低时会提示人工核对，避免把不确定答案包装成确定结论。

### 01:35-01:55 多轮对话

画面：追问“如果因为台风取消，应该怎么处理？”。

口播：追问会带上当前会话上下文，但会话严格绑定用户和知识空间，避免跨用户读取。

### 01:55-02:20 反馈闭环

画面：点击“有用”或填写反馈。

口播：反馈会关联到消息、回答和引用，后续可以用于低置信度问题聚类、文档修订和检索策略评估。

### 02:20-02:40 权限拒绝与测试

画面：登录普通账号，访问管理员空间失败；切到 GitHub Actions 显示测试通过。

口播：权限和版本能力都有自动化测试覆盖，核心链路不是只做页面展示。

## 六、云端部署步骤

### 第 1 步：创建服务器和安全组

1. 系统选择 Ubuntu 24.04 LTS。
2. 配置选择 2 核 4GB、40GB 系统盘、3Mbps 带宽。
3. 安全组只开放 `22`、`8080`；配置域名后再开放 `80` 和 `443`。
4. 不要开放 MySQL `3306`、Redis `6379`、Qdrant `6333/6334`。
5. SSH 的 `22` 端口尽量限制为自己的公网 IP。

### 第 2 步：安装 Docker

```bash
sudo apt update
sudo apt install -y git docker.io docker-compose-v2
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
newgrp docker
```

### 第 3 步：拉取项目和配置密钥

```bash
git clone https://github.com/baiyu-jujuc/ai-agent.git
cd ai-agent
cp .env.example .env
```

编辑 `.env`，至少修改：

```dotenv
DEEPSEEK_API_KEY=你的模型Key
AGENT_API_KEY=随机平台Key
JWT_SECRET=至少32位随机字符串
MYSQL_PASSWORD=强数据库密码
MYSQL_ROOT_PASSWORD=强Root密码
ALLOWED_ORIGINS=http://你的公网IP:8080
ALLOW_CLIENT_MODEL_KEY=false
RATE_LIMIT=60
```

如果公开地址会长期存在，优先使用服务端 Key 并设置限额；如果只让面试官临时体验，可以开启 `ALLOW_CLIENT_MODEL_KEY=true`，让对方在页面填写自己的模型 Key，以降低你的费用风险。

### 第 4 步：启动并验证

```bash
docker compose up -d --build
docker compose ps
curl http://127.0.0.1:8080/actuator/health
```

然后在本地执行：

```powershell
.\scripts\demo-smoke.ps1 -BaseUrl "http://公网IP:8080" -PlatformApiKey "平台Key"
```

### 第 5 步：更新和下线

```bash
git pull --ff-only
docker compose up -d --build
```

暂停但保留数据：

```bash
docker compose down
```

彻底删除演示数据：

```bash
docker compose down -v
```

`-v` 会删除 MySQL、Redis 和 Qdrant 数据卷，只能在确认不需要演示数据时使用。

## 七、面试讲述与简历呈现

简历中建议使用下面这条项目描述：

> 面向旅游 OTA 场景的企业知识库问答平台，支持多知识空间、文档版本回滚、服务端权限校验、SSE 流式回答、引用溯源和多轮会话；使用 MySQL 持久化元数据、Redis 管理会话、Qdrant 承接向量检索，并通过 JWT、平台 Key 和按空间鉴权防止越权访问。

面试时重点回答三类问题：

1. 为什么不是普通聊天机器人：因为系统以知识空间、版本、权限和引用为一级能力。
2. 如何保证不会串数据：请求上下文绑定 userId、spaceId 和 conversationId，服务端执行 `canRead`、`canWrite`、`canAdmin`。
3. 如何保证答案可追溯：保存文档版本、分块、相似度、消息编号，并提供原始片段展开和反馈链路。

不要声称使用了途牛内部资料，也不要说项目已经服务真实客户。可以诚实表述为“参考在线旅游行业公开法规和公开业务场景，独立设计并实现的脱敏演示项目”。

## 八、最终验收清单

- [ ] 旅游退改政策 v1、v2 能正确形成版本并回滚。
- [ ] 问 48 小时间题时 v1 和 v2 的回答引用不同。
- [ ] 法规摘要能命中原文链接对应的知识点。
- [ ] 普通账号访问管理员空间被拒绝。
- [ ] 引用可展开，显示文档、版本、分块和置信度。
- [ ] 连续追问不会丢失上下文，也不会跨空间检索。
- [ ] 测试全部通过，视频中展示测试结果。
- [ ] README 使用最新浅色界面截图。
- [ ] 公网地址不暴露 `.env`、数据库端口和模型 Key。
- [ ] 演示结束后关闭服务器或轮换平台 Key。
