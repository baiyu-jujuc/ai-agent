# 企业知识库问答平台

> Enterprise Knowledge Base QA Platform
> 基于 Java 21 + Spring Boot 3.4 + Spring AI，面向企业/研发团队的多知识空间 RAG 问答系统。

[![CI](https://github.com/baiyu-jujuc/ai-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/baiyu-jujuc/ai-agent/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![JDK 21](https://img.shields.io/badge/JDK-21-orange.svg)](https://openjdk.org/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI 1.0](https://img.shields.io/badge/Spring%20AI-1.0-brightgreen.svg)](https://spring.io/projects/spring-ai)

---

## 1. 项目定位

这是一个**通用企业知识库问答底座**：管理员/团队创建多个"知识空间"，成员上传文档，系统完成分块、索引、版本管理、权限建模，再让 LLM 只基于权限范围内、与问题相关的文档片段回答，并返回**引用、版本、置信度与反馈记录**。

它不是把某个公司文档写死，而是把"知识空间、文档版本、权限、引用、反馈"抽象成通用数据模型和 API，方便不同团队部署自己的语料与模型。

### 主要价值

- 从"通用 AI 聊天"收敛到"可落地的企业知识库问答"。
- 每个请求都能追溯到文档、版本和 chunk。
- 提供开发零依赖模式和 Docker/Qdrant/Redis 扩展模式。
- 前端提供轻量的浅色 DeepSeek 风格对话界面，选项全部中文优先。

---

## 2. 核心能力

| 能力 | 说明 | 状态 |
| --- | --- | --- |
| 知识空间 | `KnowledgeSpace` 空间模型，支持 name / visibility / default permission / embedding model | ✅ |
| 文档管理 | `Document` + `DocumentVersion`，记录上传状态、内容哈希、版本号、活跃版本 | ✅ |
| 分块索引 | 500 字符 / 100 重叠分块，中文与英文 token 化检索 | ✅ |
| 知识问答 | `/api/kb/spaces/{spaceId}/ask`，返回 answer、citations、confidence、topScore | ✅ |
| 引用溯源 | `Citation` 记录 message/document/version/chunk 与相似度，返回 `documentName`/`versionNo`/`content` | ✅ |
| 用户反馈 | `Feedback` 支持 up/down、原因与人工纠正文本，前端点赞点踩 UI | ✅ |
| 权限建模 | `SpaceMember` + `PermissionRule` + `PermissionService`，成员管理 API，所有 KB 接口强制 `canRead`/`canWrite`/`canAdmin` | ✅ |
| 多 Agent / 工具 | Coordinator / Code / Research / Data / ReAct，以及 Spring AI `@Tool` 工具注册表 | ✅ |
| 记忆 | 会话消息记录，内存与 Redis 两种实现，支持 token / 条数 / 会话淘汰 | ✅ |
| 流式输出 | SSE 流式聊天与工具/Agent 单事件模式 | ✅ |
| 用户鉴权 | JWT + Spring Security，注册/登录/获取当前用户，BCrypt 密码散列 | ✅ |
| 文档上传 UI | 页面拖拽/选择上传、解析状态展示、版本列表、管理员回滚 | ✅ |
| 引用与反馈 UI | 引用编号插入正文、点击展开原文片段、置信度 badge、点赞点踩 | ✅ |
| 页面模型 Key | `X-Model-API-Key` 全入口支持（chat/stream/agent/kb），`ChatModelFactory` per-request 安全注入，默认关闭 | ✅ |
| Qdrant 生产模式 | Spring AI 自动配置 `QdrantClient`，版本化向量索引同步，内存模式零依赖 | ✅ |
| 对话上下文隔离 | `conversationId` 隔离到用户 + 空间 + 会话，`KbMessage` 持久化 | ✅ |

---

## 3. 技术架构

```text
                         ┌──────────────────────┐
                         │   浅色 Web UI        │
                         │ 知识空间 / 模型 / 设置│
                         └──────────┬───────────┘
                                    │ REST + SSE
              ┌─────────────────────▼─────────────────────┐
              │  Chat / Agent / RAG / KB API              │
              └───┬──────────┬───────────┬───────────┬────┘
                  │          │           │           │
       ┌──────────▼──┐  ┌────▼───┐  ┌────▼────┐  ┌───▼────────────┐
       │ KB Service  │  │KbQa    │  │ Agent   │  │ ToolRegistry   │
       │ 空间/文档/   │  │引用+   │  │编排      │  │ Function       │
       │ 版本/权限    │  │置信度   │  │         │  │ Calling        │
       └──────┬──────┘  └───┬────┘  └────┬────┘  └───────┬────────┘
              │             │            │               │
        ┌─────▼─────────────▼────────────▼───────────────▼────┐
        │  JPA(H2/MySQL) · VectorStore · Redis · LLM         │
        └──────────────────────────────────────────────────────┘
```

### 数据模型

| 实体 | 表 | 职责 |
| --- | --- | --- |
| `KnowledgeSpace` | `kb_space` | 顶层知识空间，隔离团队/项目语料 |
| `Document` | `kb_document` | 文档元数据与解析状态 |
| `DocumentVersion` | `kb_document_version` | 同一文档的版本历史、哈希、活跃标记 |
| `Chunk` | `kb_chunk` | 文档片段，可禁用旧版本、记录 heading/page |
| `PermissionRule` | `kb_permission_rule` | 空间/文档权限规则（扩展预留） |
| `SpaceMember` | `kb_space_member` | 空间成员与 reader/writer/admin 角色 |
| `Citation` | `kb_citation` | 回答引用 |
| `Feedback` | `kb_feedback` | 用户对回答的反馈与纠错 |

### 关键模块

| 模块 | 说明 |
| --- | --- |
| `kb.*` | 知识空间、文档、版本、分块、权限、引用、反馈 |
| `rag.*` | RAG 文本/PDF/Markdown 读取、分块与检索 |
| `agent.*` | 专家 Agent 与意图路由 |
| `orchestrator.*` | sequential / parallel 编排和 `TaskTrace` |
| `tool.*` | `@Tool` 自动注册、工具列表和直接执行 |
| `config.*` | AI 配置、模型注册、向量存储、安全、异常处理 |

---

## 4. 技术栈

| 组件 | 选型 |
| --- | --- |
| 语言 | Java 21 |
| Web | Spring Boot 3.4 / Web MVC / WebFlux |
| AI | Spring AI 1.0 + OpenAI 兼容 API（默认 DeepSeek） |
| 持久化 | Spring Data JPA / H2（开发） |
| 向量库 | Spring AI VectorStore 抽象：内存（开发）/ Qdrant（可选） |
| 记忆 | InMemory / Redis |
| 文档解析 | Spring AI Text / Markdown / PDF Reader + Apache Tika |
| 构建 | Maven Wrapper |
| 前端 | 单页 HTML + 原生 JS / SSE |
| 测试 | JUnit 5 / Mockito / MockMvc |

---

## 5. 快速开始

### 5.1 本地开发（无需 Docker）

```bash
cp .env.example .env
# 编辑 .env，设置 DEEPSEEK_API_KEY

./mvnw clean verify --no-transfer-progress
./mvnw spring-boot:run
```

默认配置：

- `agent.storage.vector-store=memory`
- `agent.storage.memory=memory`
- H2 内存数据库
- API Key 默认 `dev-key-change-in-production`

浏览器打开 <http://localhost:8080>。

### 5.2 Docker Compose

```bash
cp .env.example .env
# 编辑 .env，至少填写 DEEPSEEK_API_KEY，并修改平台 Key / JWT / 数据库密码
docker compose up -d --build
docker compose ps
```

Compose 会启动四类服务：

| 服务 | 用途 | 是否映射到宿主机 |
| --- | --- | --- |
| `app` | Spring Boot 应用与 Web UI | 是，`8080` |
| `mysql` | 用户、知识空间、文档、版本、消息、反馈持久化 | 否，仅容器网络 |
| `redis` | 会话记忆与缓存 | 否，仅容器网络 |
| `qdrant` | 生产向量索引服务 | 否，仅容器网络 |

默认使用 MySQL 持久化元数据、Redis 保存会话记忆；向量检索使用内存模式，确保没有 Embedding Key 时仍能一键启动。需要 Qdrant 时按 5.5 切换。

首次启动后可准备可重复演示数据并执行端到端验收：

```powershell
.\scripts\demo-prepare.ps1
.\scripts\demo-smoke.ps1
```

完整录屏脚本见 `docs/demo-recording-script.md`，云服务器部署见 `docs/cloud-deployment.md`。面向旅游 OTA 场景的逐步执行方案见 `docs/demo-travel-ota-execution-plan.md`。

### 5.3 API Key 双模式说明

本平台使用两层 Key，请勿混淆：

| Key | 用途 | 传递方式 | 默认值 |
| --- | --- | --- | --- |
| **平台访问 Key** | 前端访问后端 REST API 的鉴权 | 请求头 `X-API-Key` | `dev-key-change-in-production` |
| **模型 API Key** | 后端调用 DeepSeek / OpenAI 兼容模型的密钥 | 服务端环境变量 `DEEPSEEK_API_KEY` | 无（必须配置） |

**安全模型：**

- 模型 Key 只存在服务端环境变量中，不出现在页面、日志或 API 响应中。
- 前端设置弹窗中的"模型 API Key"字段为可选功能，仅在服务端开启 `allow-client-model-key` 时生效（默认关闭）。
- 开启后，前端通过 `X-Model-API-Key` 请求头传递 Key，服务端通过 `ChatModelFactory` 为当前请求创建独立 `ChatModel` 实例，请求结束后不保留。
- Key 不进入日志、数据库、URL 或异常响应。
- `.env` 已被 `.gitignore` 排除，不会上传到 GitHub。

> **首次使用：** 打开页面后点击右上角齿轮图标，在"平台访问 Key"中填入 `dev-key-change-in-production`，保存即可。Key 存在浏览器 `localStorage`，刷新不丢失。

### 5.4 用户鉴权（JWT）

本平台使用 JWT + Spring Security 进行用户身份认证：

- **注册/登录**：`POST /api/auth/register`、`POST /api/auth/login`，密码使用 BCrypt 散列存储。
- **JWT Token**：登录后返回 JWT，前端存储在 `localStorage`，后续请求通过 `Authorization: Bearer <token>` 传递。
- **Spring Security 路由级授权**：`/api/kb/**`、`/api/chat/**`（非公开GET）、`/api/rag/**`、`/api/tools/**` 均要求已认证，未登录返回 403。
- **权限强制执行**：所有 KB API 通过 `PermissionService` 检查 `canRead`/`canWrite`/`canAdmin`。
- **空间隔离**：`listSpaces` 只返回当前用户可访问的空间；非成员访问私有空间返回 403。
- **资源归属校验**：按 `documentId` 访问的接口先解析 `spaceId` 再校验权限；按 `messageId` 访问的接口先校验消息归属；会话历史非 admin 只能查看自己的会话。

### 5.5 Qdrant 生产模式

默认使用内存向量存储（零依赖开发模式）。切换到 Qdrant 生产模式：

```bash
export VECTOR_STORE_TYPE=qdrant
export AUTOCONFIG_EXCLUDE=  # 清空排除，让 QdrantClient 自动配置生效
export EMBEDDING_API_KEY=your-embedding-key
export EMBEDDING_BASE_URL=https://api.openai.com
export EMBEDDING_MODEL=text-embedding-3-small
export QDRANT_HOST=localhost
export QDRANT_PORT=6333
export QDRANT_INIT_SCHEMA=true
./mvnw spring-boot:run
```

使用 `docker compose` 时，Compose 已将 `QDRANT_HOST` 设置为容器网络内的 `qdrant`，无需在 `.env` 中改成主机名。

- Qdrant 模式下，Spring AI 自动配置创建 `QdrantClient` Bean。
- 文档上传/回滚时，向量按版本删除和重建，旧版本不污染检索结果。
- 检索结果携带 `space_id` 过滤条件，确保空间隔离。
- 内存模式保留作为测试和零依赖开发模式。

### 5.6 多轮上下文问答

知识库问答支持多轮对话上下文：

- 每次 `ask` 时读取当前用户、空间、会话的最近历史消息（最多 20 条）。
- 历史进入 prompt 时按时间顺序排列，过滤掉失败的助手回复。
- 无相关文档时仍能基于上下文回答"知识库中未找到"。
- 前端按空间维护独立 `conversationId`，避免跨空间上下文串扰。

---

## 6. API 示例

除少数 GET 只读接口外，接口需要：

```text
X-API-Key: dev-key-change-in-production
Authorization: Bearer <jwt-token>
```

> JWT Token 通过注册/登录获取。前端自动在所有请求中携带这两个头。

### 6.1 创建知识空间

```bash
curl -X POST http://localhost:8080/api/kb/spaces \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dev-key-change-in-production" \
  -d '{"name":"研发中心文档","visibility":"team"}'
```

### 6.2 上传文档

```bash
curl -X POST http://localhost:8080/api/kb/spaces/{spaceId}/documents \
  -H "X-API-Key: dev-key-change-in-production" \
  -F "file=@docs/README.md"
```

支持 TXT / Markdown / PDF，单文件 10MB 上限。同名文档上传后版本号自动递增。

### 6.3 基于知识空间提问

```bash
curl -X POST http://localhost:8080/api/kb/spaces/{spaceId}/ask \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dev-key-change-in-production" \
  -d '{"question":"这个项目如何启动？"}'
```

返回示例结构：

```json
{
  "messageId": "uuid",
  "answer": "运行 `./mvnw spring-boot:run` [1]",
  "confidence": "high",
  "topScore": 0.86,
  "conversationId": "conv-xxx",
  "citations": [
    {
      "citationId": "uuid",
      "documentId": "uuid",
      "documentName": "README.md",
      "versionId": "uuid",
      "versionNo": 1,
      "chunkId": "uuid",
      "content": "引用片段原文...",
      "score": 0.86
    }
  ]
}
```

citation 包含文件名、版本号和原文片段，前端以编号 `[1]` 形式插入回答正文，点击可展开原文。

### 6.4 文档版本与回滚

```bash
# 查看版本
curl http://localhost:8080/api/kb/documents/{documentId}/versions \
  -H "X-API-Key: dev-key-change-in-production"

# 回滚到指定版本
curl -X POST http://localhost:8080/api/kb/documents/{documentId}/rollback/1 \
  -H "X-API-Key: dev-key-change-in-production"
```

回滚后旧版本 chunk 重新启用，新版本 chunk 标记 disabled，不物理删除。

### 6.5 普通对话与工具

```bash
curl -X POST http://localhost:8080/api/chat/simple \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dev-key-change-in-production" \
  -d '{"message":"你好","model":"deepseek-v4-flash"}'
```

```bash
curl -X POST http://localhost:8080/api/tools/calculator \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dev-key-change-in-production" \
  -d '{"input":"(2+3)*4"}'
```

### 6.6 多 Agent 编排

```bash
curl -X POST http://localhost:8080/api/chat/orchestrate \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dev-key-change-in-production" \
  -d '{"message":"研究虚拟线程","conversationId":"demo","strategy":"sequential","agents":["research","code"]}'
```

返回包含每个 Agent 的执行结果和 `traces`（执行轨迹）。

---

## 7. 环境变量

常用配置：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 必填 | 模型服务 API Key（服务端环境变量） |
| `AGENT_API_KEY` | `dev-key-change-in-production` | 平台 API Key |
| `SERVER_PORT` | `8080` | 服务端口 |
| `SPRING_DATASOURCE_URL` | H2 内存库 | JDBC 地址；Compose 使用 MySQL |
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` | `ai_agent` 等 | Compose 数据库初始化参数 |
| `VECTOR_STORE_TYPE` | `memory` | `memory` / `qdrant` |
| `MEMORY_TYPE` | `memory` | `memory` / `redis`；Compose 默认 `redis` |
| `DOCKER_NETWORK_MTU` | `1400` | Docker 网络 MTU；兼容 WSL/VPN，避免模型 API TLS 超时 |
| `EMBEDDING_API_KEY` | 空 | 生产向量模式所需 Embedding Key |
| `EMBEDDING_BASE_URL` | `https://api.openai.com` | Embedding 服务地址 |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding 模型 |
| `QDRANT_HOST` / `QDRANT_PORT` | `localhost` / `6333` | Qdrant 地址 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 地址 |
| `RATE_LIMIT` | `30` | 每 IP 每分钟请求上限 |
| `ALLOWED_ORIGINS` | 本机地址 | CORS 白名单 |
| `ALLOW_CLIENT_MODEL_KEY` | `false` | 是否允许前端传入模型 API Key |
| `LEGACY_RAG_ENABLED` | `false` | 是否启用旧 `/api/rag/**` 接口（默认关闭） |
| `AUTOCONFIG_EXCLUDE` | Qdrant 自动配置类 | 内存模式下排除的自动配置类 |

完整配置见 `.env.example`。

---

## 8. 测试与质量

```bash
./mvnw clean verify --no-transfer-progress
```

当前仓库包含 131 个自动化测试，覆盖：

- ChatController 参数与状态码
- KB 服务、KbQaService 问答、消息持久化与多轮上下文
- 权限服务（canRead/canWrite/canAdmin）
- 内存向量检索（含 similarityThreshold 过滤、Filter 表达式）
- Spring Security 路由级授权（未认证返回 403、公开 GET 放行）
- 模型 API Key 一致性（allow-client-model-key 开关行为）
- Qdrant Bean 装配（内存模式不创建 QdrantClient）
- 旧 RAG 接口默认禁用（`legacy.rag.enabled=false`）
- 编排策略（sequential / parallel + TaskTrace）
- RAG 与内置工具

> 测试默认不依赖真实 API Key，不会访问外网 LLM。

---

## 9. 安全与隐私设计

- 平台 API Key 只通过请求头 `X-API-Key` 传递，常量时间比较，避免 URL 记录。
- 按 IP 限流、CORS 白名单、Actuator 最小暴露。
- 文件工具限制在工作区目录，HTTP 工具带 SSRF 校验。
- `.env` 不入库，真实 Key 不出现在页面、日志和错误响应。
- 生产环境必须替换默认 `AGENT_API_KEY`，并根据部署模式配置 DB / Redis / Qdrant / Embedding。
- 异常处理统一返回通用错误信息，不泄露堆栈细节。

---

## 10. 当前状态与 Roadmap

### 已完成

- ✅ 企业知识库领域模型（8 个 JPA 实体）、Repository 与 REST API
- ✅ 知识空间问答：answer + citations + confidence + feedback
- ✅ 浅色 DeepSeek 风格中文 Web UI
- ✅ 模型注册、SSE、工具调用、多 Agent 编排（含执行轨迹）
- ✅ GitHub Actions CI、Dockerfile、Compose、Maven Wrapper
- ✅ 131 个自动化测试（不依赖外网 LLM）
- ✅ 文档分块（中文单字分词 + 英文 token）、PDF/Markdown 读取
- ✅ 文档版本管理与回滚（含向量索引同步）
- ✅ 统一异常处理、Actuator 最小暴露
- ✅ JWT + Spring Security 用户鉴权（注册/登录/BCrypt）
- ✅ Spring Security 路由级授权（`/api/kb/**`、`/api/chat/**` 等要求已认证）
- ✅ 权限强制执行（canRead/canWrite/canAdmin 接入所有 KB API，资源归属校验）
- ✅ 页面文档上传 UI（拖拽/选择上传、解析状态、版本列表、回滚）
- ✅ 知识空间问答主链路打通（选空间 → /api/kb/spaces/{id}/ask）
- ✅ 引用与反馈 UI（编号引用、点击展开原文、置信度 badge、点赞点踩）
- ✅ 对话上下文隔离（conversationId 隔离到用户 + 空间 + 会话）
- ✅ 消息持久化（KbMessage 实体，按会话查询历史）
- ✅ 多轮上下文问答（历史消息进入 prompt，过滤失败回复）
- ✅ 页面模型 Key 全入口支持（chat/stream/agent/kb 均读取 X-Model-API-Key）
- ✅ Qdrant 生产模式（Spring AI 自动配置 QdrantClient，版本化向量索引，空间过滤）
- ✅ 旧 RAG 接口默认禁用（`legacy.rag.enabled=false`，防止绕过知识空间隔离）
- ✅ 前端 XSS 防护（escapeHtml 转义所有动态内容）

### 规划

- 🔜 检索相关性优化：rerank、引用片段原文回显增强
- 🔜 生产数据库迁移脚本与审计日志
- 🔜 文档级权限规则（deny 规则在检索前过滤）
- 🔜 移动端适配优化

---

## 11. 端到端演示步骤

1. **启动应用**：`./mvnw spring-boot:run`，浏览器打开 <http://localhost:8080>
2. **注册/登录**：点击右上角用户图标，注册账号并登录
3. **配置 API Key**：点击齿轮图标，确认平台访问 Key 为 `dev-key-change-in-production`
4. **创建知识空间**：点击"创建空间"按钮，输入名称和描述
5. **上传文档**：选择知识空间后，点击文档图标，拖拽或选择 TXT/Markdown/PDF 文件上传
6. **查看版本**：在文档列表中点击"版本"，查看版本历史；管理员可回滚到旧版本
7. **提问**：在知识空间下拉框选择空间后，在输入框提问
8. **查看引用**：回答中引用以 `[1]` 编号显示，点击可展开原文片段、文件名、版本号和相似度
9. **多轮追问**：继续追问，系统按空间和会话保留最近上下文
10. **反馈**：点击"有用"或"没用"对回答进行评价
11. **权限验证**：用普通成员访问未授权空间，接口返回 403

推荐使用 `docs/demo-data` 中的脱敏业务文档完成完整演示；`scripts/demo-smoke.ps1` 会自动检查上述关键链路。

旅游行业演示数据可通过 `scripts/demo-prepare-travel.ps1` 自动准备，并通过 `scripts/demo-smoke-travel.ps1` 验证退改制度 v1/v2 回滚、引用切换、多轮问答和空间权限拒绝。所有旅游制度和数字均为虚构数据，法规摘要保留政府公开原文链接。

Windows PowerShell 5.1 用户可直接运行 `scripts/run-demo-travel.ps1`。启动器会自动定位 PowerShell 7，并依次执行旅游数据准备和完整验收。

---

## 12. 项目结构

```text
src/main/java/com/baiyu/agent/
├── agent/            # Coordinator / Code / Research / Data / ReAct
├── api/              # Chat / Agent / Tool Controller
├── config/           # AI、模型注册、向量库、安全、异常
├── kb/               # 知识空间、文档、版本、权限、引用、反馈
│   ├── entity/
│   ├── repository/
│   ├── KnowledgeBaseService.java
│   ├── KbQaService.java
│   └── PermissionService.java
├── orchestrator/     # Sequential / Parallel + TaskTrace
├── rag/              # RAG 文档读取、分块、检索
├── tool/             # ToolRegistry + 内置工具
└── memory/           # ChatMemoryService
```

---

## 13. License

[MIT](LICENSE)
