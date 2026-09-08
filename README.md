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
| 页面模型 Key | `X-Model-API-Key` 后端支持，`ChatModelFactory` per-request 安全注入，默认关闭 | ✅ |
| Qdrant 生产模式 | 真实 Embedding + VectorStore 的 RAG 链路，版本化向量索引同步 | ✅ |
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
        │  JPA(H2/PostgreSQL) · VectorStore · Redis · LLM    │
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
export DEEPSEEK_API_KEY=your-key
docker compose up -d --build
```

Compose 默认保持 memory 模式可开箱运行，同时提供 Qdrant、Redis 服务供生产模式切换。

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
- **权限强制执行**：所有 KB API 通过 `PermissionService` 检查 `canRead`/`canWrite`/`canAdmin`。
- **空间隔离**：`listSpaces` 只返回当前用户可访问的空间；非成员访问私有空间返回 403。

### 5.5 Qdrant 生产模式

默认使用内存向量存储（零依赖开发模式）。切换到 Qdrant 生产模式：

```bash
export VECTOR_STORE_TYPE=qdrant
export EMBEDDING_API_KEY=your-embedding-key
export EMBEDDING_BASE_URL=https://api.openai.com
export EMBEDDING_MODEL=text-embedding-3-small
export QDRANT_HOST=localhost
export QDRANT_PORT=6333
./mvnw spring-boot:run
```

- 文档上传/回滚时，向量按版本删除和重建，旧版本不污染检索结果。
- 检索结果携带 `space_id` 过滤条件，确保空间隔离。
- 内存模式保留作为测试和零依赖开发模式。

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
| `VECTOR_STORE_TYPE` | `memory` | `memory` / `qdrant` |
| `MEMORY_TYPE` | `memory` | `memory` / `redis` |
| `EMBEDDING_API_KEY` | 空 | 生产向量模式所需 Embedding Key |
| `EMBEDDING_BASE_URL` | `https://api.openai.com` | Embedding 服务地址 |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding 模型 |
| `QDRANT_HOST` / `QDRANT_PORT` | `localhost` / `6333` | Qdrant 地址 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 地址 |
| `RATE_LIMIT` | `30` | 每 IP 每分钟请求上限 |
| `ALLOWED_ORIGINS` | 本机地址 | CORS 白名单 |

完整配置见 `.env.example`。

---

## 8. 测试与质量

```bash
./mvnw clean verify --no-transfer-progress
```

当前仓库包含 119 个自动化测试，覆盖：

- ChatController 参数与状态码
- KB 服务、KbQaService 问答与消息持久化
- 权限服务（canRead/canWrite/canAdmin）
- 内存向量检索（含 similarityThreshold 过滤、Filter 表达式）
- 安全鉴权（API Key、JWT、公开 GET、错误 Key）
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
- ✅ 119 个自动化测试（不依赖外网 LLM）
- ✅ 文档分块（中文单字分词 + 英文 token）、PDF/Markdown 读取
- ✅ 文档版本管理与回滚（含向量索引同步）
- ✅ 统一异常处理、Actuator 最小暴露
- ✅ JWT + Spring Security 用户鉴权（注册/登录/BCrypt）
- ✅ 权限强制执行（canRead/canWrite/canAdmin 接入所有 KB API）
- ✅ 页面文档上传 UI（拖拽/选择上传、解析状态、版本列表、回滚）
- ✅ 知识空间问答主链路打通（选空间 → /api/kb/spaces/{id}/ask）
- ✅ 引用与反馈 UI（编号引用、点击展开原文、置信度 badge、点赞点踩）
- ✅ 对话上下文隔离（conversationId 隔离到用户 + 空间 + 会话）
- ✅ 消息持久化（KbMessage 实体，按会话查询历史）
- ✅ 页面模型 Key 后端支持（ChatModelFactory + allow-client-model-key）
- ✅ Qdrant 生产模式（版本化向量索引、空间过滤、Embedding 集成）

### 规划

- 🔜 检索相关性优化：rerank、引用片段原文回显增强
- 🔜 生产数据库迁移脚本与审计日志
- 🔜 文档级权限规则（deny 规则在检索前过滤）
- 🔜 移动端适配优化

详细改造方向见 [docs/trae-phase2-handoff.md](docs/trae-phase2-handoff.md)。

---

## 11. 端到端演示步骤

1. **启动应用**：`./mvnw spring-boot:run`，浏览器打开 <http://localhost:8080>
2. **注册/登录**：点击右上角用户图标，注册账号并登录
3. **配置 API Key**：点击齿轮图标，确认平台访问 Key 为 `dev-key-change-in-production`
4. **创建知识空间**：点击"创建空间"按钮，输入名称和描述
5. **上传文档**：选择知识空间后，点击文档图标，拖拽或选择 TXT/Markdown/PDF 文件上传
6. **提问**：在知识空间下拉框选择空间后，在输入框提问
7. **查看引用**：回答中引用以 `[1]` 编号显示，点击可展开原文片段、文件名和版本号
8. **反馈**：点击"有帮助"或"没帮助"对回答进行评价
9. **查看历史**：系统自动持久化对话消息，按会话隔离

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
