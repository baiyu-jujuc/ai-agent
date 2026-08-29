# AI Agent - 多功能 Java AI Agent

基于 Spring AI + DeepSeek V4 构建的多功能 AI Agent 系统。

## 技术栈

| 组件 | 选型 |
|------|------|
| 框架 | Spring Boot 3.4 + Spring AI 1.0 |
| LLM | DeepSeek V4 Pro (OpenAI 兼容端点) |
| 向量存储 | Simple Vector Store (开发) → Qdrant (生产) |
| 数据库 | H2 (开发) → PostgreSQL (生产) |
| 流式输出 | SSE (Server-Sent Events) |
| 构建 | Maven 3.9 |
| Java | JDK 21 |

## 快速开始

```bash
# 设置环境变量
export DEEPSEEK_API_KEY=your-api-key

# 启动
mvn spring-boot:run

# 访问
# Web UI: http://localhost:8080
# API: http://localhost:8080/api/chat/simple
# SSE: http://localhost:8080/api/chat/sse
# H2 Console: http://localhost:8080/h2-console
# Actuator: http://localhost:8080/actuator/health
```

## API 示例

### 简单对话
```bash
curl -X POST http://localhost:8080/api/chat/simple \
  -H "Content-Type: application/json" \
  -d '{"message": "你好"}'
```

### SSE 流式对话
```bash
curl -X POST http://localhost:8080/api/chat/sse \
  -H "Content-Type: application/json" \
  -d '{"message": "写一个 Java Hello World"}'
```

## 项目结构

```
src/main/java/com/baiyu/agent/
├── agent/              # Agent 核心 (待实现)
├── api/                # REST API
│   ├── ChatController.java      # 聊天 + SSE 流式
│   └── AgentController.java     # Agent 状态
├── config/             # 配置
│   └── AiConfig.java            # Spring AI ChatClient
├── memory/             # 记忆管理 (待实现)
├── observability/      # 可观测性 (待实现)
├── orchestrator/       # 多 Agent 编排 (待实现)
├── rag/                # RAG 管道 (待实现)
└── tool/               # 工具
    └── builtin/CalculatorTool.java  # 计算器
```

## 可用模型

| 模型 | 用途 |
|------|------|
| deepseek-v4-pro | 默认模型，最强推理 |
| deepseek-v4-flash | 轻量快速 |
| deepseek-v4-flash-vision-exp | 视觉理解 |
