# AI Agent - 多功能 Java AI Agent

基于 Spring AI + DeepSeek V4 构建的多功能 AI Agent 系统。

## 技术栈

| 组件 | 选型 |
|------|------|
| 框架 | Spring Boot 3.4 + Spring AI 1.0 |
| LLM | DeepSeek V4 (OpenAI 兼容端点) |
| 向量存储 | InMemory (开发) → Qdrant (生产) |
| 对话记忆 | InMemory (开发) → Redis (生产) |
| 数据库 | H2 (开发) → PostgreSQL (生产) |
| 流式输出 | SSE (Server-Sent Events) |
| 构建 | Maven 3.9 |
| Java | JDK 21 |

## 功能

- **多 Agent 系统**: Coordinator / Code / Research / Data / ReAct
- **Function Calling**: LLM 自动调用工具完成任务
- **7 个内置工具**: 计算器、时间、系统信息、天气查询、网络搜索、文件操作、HTTP 请求
- **RAG 管道**: 文档分块索引（500 字符 / 100 重叠）+ 检索增强生成
- **多模型支持**: Flash (低成本) / Pro (高性能) / Vision (图像)
- **SSE 流式输出**: 实时响应
- **对话记忆**: token 预算 + 消息条数双重裁剪，会话数超限自动淘汰 (InMemory / Redis 双模式)
- **存储后端切换**: 一键切换 Dev (InMemory) ↔ Prod (Qdrant + Redis)
- **安全防护**: API Key 鉴权（常量时间比较）、按 IP 限流、CORS 白名单、文件沙箱、SSRF 防护
- **统一异常处理**: 全局映射为规范 JSON（400 / 403 / 429 / 500）

## 快速开始

### 1. 配置环境变量

```bash
# 复制环境变量模板
cp .env.example .env

# 编辑 .env 文件，填入你的 API Key
# DEEPSEEK_API_KEY=sk-your-api-key-here
```

> **安全提示**: 永远不要将 API Key 硬编码在代码中或提交到 Git。本项目使用环境变量引用，`.env` 文件已被 `.gitignore` 排除。

### 2. 启动

```bash
# 编译
mvn clean compile

# 运行 (开发模式: InMemory 存储, 无需 Docker)
mvn spring-boot:run

# 运行 (生产模式: Qdrant + Redis, 需先启动 Docker)
$env:VECTOR_STORE_TYPE="qdrant"; $env:MEMORY_TYPE="redis"; mvn spring-boot:run
```

### 3. 访问

- Web UI: http://localhost:8080
- Chat API: http://localhost:8080/api/chat/simple
- 模型列表: http://localhost:8080/api/chat/models
- 工具列表: http://localhost:8080/api/chat/tools
- 存储状态: http://localhost:8080/api/chat/storage-status
- 健康检查: http://localhost:8080/actuator/health

### 环境变量

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `DEEPSEEK_API_KEY` | **无（必填）** | DeepSeek API Key，缺失则应用无法启动 |
| `AGENT_API_KEY` | `dev-key-change-in-production` | 本服务 API Key，生产环境必须修改 |
| `FILE_ACCESS_DIR` | `./workspace` | 文件工具沙箱根目录 |
| `RATE_LIMIT` | `30` | 单客户端每分钟请求上限，超出返回 429 |
| `ALLOWED_ORIGINS` | `localhost:8080,3000,127.0.0.1:8080` | CORS 允许来源，逗号分隔 |
| `VECTOR_STORE_TYPE` | `memory` | 向量存储: `memory` (开发) / `qdrant` (生产) |
| `MEMORY_TYPE` | `memory` | 对话记忆: `memory` (开发) / `redis` (生产) |
| `MEMORY_MAX_TOKENS` | `8000` | 单会话上下文 token 预算，超出裁剪最早消息 |
| `MEMORY_MAX_MESSAGES` | `40` | 单会话保留消息条数上限 |
| `MAX_CONVERSATIONS` | `100` | 内存模式下最大会话数，超出淘汰最早的 |
| `QDRANT_HOST` | `localhost` | Qdrant 地址 |
| `QDRANT_PORT` | `6333` | Qdrant 端口 |
| `QDRANT_INIT_SCHEMA` | `false` | 是否自动创建 Qdrant collection |
| `REDIS_HOST` | `localhost` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |

## API 示例

除少数只读 GET 接口（`/api/chat/models`、`/api/chat/tools`、
`/api/chat/storage-status`、`/api/agent/**`）外，所有接口都需在请求头携带
`X-API-Key`，否则返回 401。密钥只接受请求头传递，不接受 URL 参数。

```bash
export AGENT_API_KEY=dev-key-change-in-production
```

### 简单对话
```bash
curl -X POST http://localhost:8080/api/chat/simple \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"message": "你好", "model": "deepseek-v4-flash"}'
```

### 带工具调用的对话
```bash
curl -X POST http://localhost:8080/api/chat/simple \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"message": "北京今天天气怎么样？", "useTools": "true", "model": "deepseek-v4-flash"}'
```

### 指定 Agent 对话
```bash
curl -X POST http://localhost:8080/api/chat/agent/code \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"message": "写一个快速排序", "model": "deepseek-v4-pro"}'
```

### 多 Agent 编排
```bash
curl -X POST http://localhost:8080/api/chat/orchestrate \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"message": "分析快速排序", "strategy": "parallel", "agents": ["code", "data"]}'
```

### 执行工具
```bash
curl -X POST http://localhost:8080/api/tools/calculator \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"input": "2+3*4"}'
```

## 项目结构

```
src/main/java/com/baiyu/agent/
├── AiAgentApplication.java     # 启动类 (@EnableScheduling 驱动限流桶清理)
├── agent/              # Agent 核心
│   ├── Agent.java              # Agent 接口 (execute / executeWithModel)
│   ├── AbstractAgent.java      # 抽象基类，execute 统一委托 executeWithModel
│   ├── CoordinatorAgent.java   # LLM 意图分类路由 + 关键词兜底
│   ├── CodeAgent.java          # 代码专家
│   ├── ResearchAgent.java      # 研究专家
│   ├── DataAgent.java          # 数据专家
│   └── ReActAgent.java         # ReAct 循环（挂载全部工具）
├── orchestrator/       # 多 Agent 编排
│   ├── OrchestrationStrategy.java # 策略接口
│   ├── SequentialStrategy.java # 顺序执行，前者输出作为后者输入
│   └── ParallelStrategy.java   # 并行执行，守护线程池 + 超时 + 单点失败隔离
├── tool/               # 工具系统
│   ├── ToolComponent.java      # 空标记接口，供 Spring 收集工具 bean
│   ├── FunctionCallingService  # 交给 Spring AI 原生工具调用循环
│   └── builtin/                # 7 个 @Tool 注解方法
│       ├── CalculatorTool      # 计算器（手写递归下降解析）
│       ├── TimeTool            # 时间
│       ├── SystemInfoTool      # 系统信息
│       ├── WeatherTool         # 天气查询 (wttr.in)
│       ├── WebSearchTool       # 网络搜索 (DuckDuckGo)
│       ├── FileOperationTool   # 文件操作（沙箱限制在 workspace/）
│       └── HttpRequestTool     # HTTP 请求（含 SSRF 防护）
├── rag/                # RAG 管道
│   ├── RagService.java         # 文档分块索引 + 检索增强生成
│   └── RagController.java      # RAG API
├── memory/             # 对话记忆
│   └── ChatMemoryService.java  # InMemory/Redis 双后端 + token 预算 + 会话淘汰
├── api/                # REST API
│   ├── ChatController.java     # 聊天/流式/编排/历史/模型/工具列表
│   ├── AgentController.java    # Agent 状态与清单
│   └── ToolController.java     # 工具反射列举与直接执行
└── config/             # 配置
    ├── AiConfig.java           # OpenAiApi / ChatModel / ChatClient（含超时）
    ├── SecurityConfig.java     # API Key 鉴权 + 限流 + CORS
    ├── GlobalExceptionHandler  # 统一异常映射为 JSON（400/403/500）
    ├── VectorStoreConfig.java  # InMemory 向量库（关键词重叠打分）
    └── EncodingConfig.java     # UTF-8 编码过滤器
```

> 工具不再需要注册中心：每个工具是一个 `@Component`，方法上标注 Spring AI 的
> `@Tool(name, description)`，由 `List<ToolComponent>` 注入后直接传给
> `ChatClient.tools(...)`，工具调用循环由框架原生处理。所有工具方法签名统一为
> `String execute(String input)`，因此 `FileOperationTool` 使用 `read:<路径>` /
> `write:<路径>:<内容>` / `list:<路径>` 形式的输入约定。

## 可用模型

| 模型 | 用途 | 特点 |
|------|------|------|
| deepseek-v4-flash | 默认 | 快速响应，低成本，1M 上下文 |
| deepseek-v4-pro | 复杂任务 | 最强推理能力，1M 上下文 |
| deepseek-v4-flash-vision-exp | 图像输入 | 支持图片理解 (实验) |

## Docker 部署

### 启动 Qdrant + Redis

```bash
# 启动 Qdrant 向量库 + Redis 缓存
docker-compose up -d qdrant redis

# 验证
curl http://localhost:6333/healthz    # Qdrant
redis-cli ping                         # Redis

# 切换到生产模式
$env:VECTOR_STORE_TYPE="qdrant"
$env:MEMORY_TYPE="redis"
$env:QDRANT_INIT_SCHEMA="true"        # 首次启动时创建 collection
mvn spring-boot:run
```

### 完整 Docker 部署

```bash
# 设置环境变量
export DEEPSEEK_API_KEY=your-api-key

# 启动所有服务 (AI Agent + Qdrant + Redis)
docker-compose up -d
```

## 安全说明

- API Key 通过环境变量注入，不硬编码在代码中
- `.env` 文件已被 `.gitignore` 排除
- H2 数据库密码为空（仅开发环境）
- 生产环境请使用 PostgreSQL + Qdrant + Redis

## License

MIT
