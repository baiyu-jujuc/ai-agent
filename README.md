# AI Agent - 多功能 Java AI Agent

基于 Spring AI + DeepSeek V4 构建的多功能 AI Agent 系统。

## 技术栈

| 组件 | 选型 |
|------|------|
| 框架 | Spring Boot 3.4 + Spring AI 1.0 |
| LLM | DeepSeek V4 (OpenAI 兼容端点) |
| 向量存储 | Simple Vector Store (开发) → Qdrant (生产) |
| 数据库 | H2 (开发) → PostgreSQL (生产) |
| 流式输出 | SSE (Server-Sent Events) |
| 构建 | Maven 3.9 |
| Java | JDK 21 |

## 功能

- **多 Agent 系统**: Coordinator / Code / Research / Data / ReAct
- **Function Calling**: LLM 自动调用工具完成任务
- **7 个内置工具**: 计算器、时间、系统信息、天气查询、网络搜索、文件操作、HTTP 请求
- **RAG 管道**: 文档索引 + 检索增强生成
- **多模型支持**: Flash (低成本) / Pro (高性能) / Vision (图像)
- **SSE 流式输出**: 实时响应
- **对话记忆**: 滑动窗口上下文管理

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

# 运行
mvn spring-boot:run
```

### 3. 访问

- Web UI: http://localhost:8080
- Chat API: http://localhost:8080/api/chat/simple
- 模型列表: http://localhost:8080/api/chat/models
- 工具列表: http://localhost:8080/api/chat/tools
- 健康检查: http://localhost:8080/actuator/health

## API 示例

### 简单对话
```bash
curl -X POST http://localhost:8080/api/chat/simple \
  -H "Content-Type: application/json" \
  -d '{"message": "你好", "model": "deepseek-v4-flash"}'
```

### 带工具调用的对话
```bash
curl -X POST http://localhost:8080/api/chat/simple \
  -H "Content-Type: application/json" \
  -d '{"message": "北京今天天气怎么样？", "useTools": "true", "model": "deepseek-v4-flash"}'
```

### 指定 Agent 对话
```bash
curl -X POST http://localhost:8080/api/chat/agent/code \
  -H "Content-Type: application/json" \
  -d '{"message": "写一个快速排序", "model": "deepseek-v4-pro"}'
```

### 执行工具
```bash
curl -X POST http://localhost:8080/api/tools/calculator \
  -H "Content-Type: application/json" \
  -d '{"input": "2+3*4"}'
```

## 项目结构

```
src/main/java/com/baiyu/agent/
├── agent/              # Agent 核心
│   ├── Agent.java              # Agent 接口
│   ├── AbstractAgent.java      # 抽象基类
│   ├── CoordinatorAgent.java   # 协调路由
│   ├── CodeAgent.java          # 代码专家
│   ├── ResearchAgent.java      # 研究专家
│   ├── DataAgent.java          # 数据专家
│   └── ReActAgent.java         # ReAct 循环
├── orchestrator/       # 多 Agent 编排
│   ├── SequentialStrategy.java # 顺序执行
│   └── ParallelStrategy.java   # 并行执行
├── tool/               # 工具系统
│   ├── Tool.java               # 工具接口
│   ├── ToolRegistry.java       # 工具注册中心
│   ├── FunctionCallingService  # LLM 工具调用
│   └── builtin/                # 内置工具
│       ├── CalculatorTool      # 计算器
│       ├── TimeTool            # 时间
│       ├── SystemInfoTool      # 系统信息
│       ├── WeatherTool         # 天气查询
│       ├── WebSearchTool       # 网络搜索
│       ├── FileOperationTool   # 文件操作
│       └── HttpRequestTool     # HTTP 请求
├── rag/                # RAG 管道
│   ├── RagService.java         # 文档索引+检索
│   └── RagController.java      # RAG API
├── memory/             # 对话记忆
│   └── ChatMemoryService.java
├── api/                # REST API
│   ├── ChatController.java     # 聊天+工具+模型选择
│   ├── AgentController.java    # Agent 状态
│   └── ToolController.java     # 工具执行
└── config/             # 配置
    ├── AiConfig.java           # DeepSeek 配置
    ├── EncodingConfig.java     # UTF-8 编码
    └── ToolRegistrationConfig  # 工具自动注册
```

## 可用模型

| 模型 | 用途 | 特点 |
|------|------|------|
| deepseek-v4-flash | 默认 | 快速响应，低成本，1M 上下文 |
| deepseek-v4-pro | 复杂任务 | 最强推理能力，1M 上下文 |
| deepseek-v4-flash-vision-exp | 图像输入 | 支持图片理解 (实验) |

## Docker 部署 (可选)

```bash
# 设置环境变量
export DEEPSEEK_API_KEY=your-api-key

# 启动所有服务
docker-compose up -d

# 包含: AI Agent + Qdrant 向量库 + Redis 缓存
```

## 安全说明

- API Key 通过环境变量注入，不硬编码在代码中
- `.env` 文件已被 `.gitignore` 排除
- H2 数据库密码为空（仅开发环境）
- 生产环境请使用 PostgreSQL + Qdrant + Redis

## License

MIT
