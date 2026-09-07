# 手工演示清单

> 按顺序执行以下场景，验证各功能正常。所有 POST 请求需携带 `X-API-Key` 头。

## 前置准备

```bash
# 1. 设置环境变量
export DEEPSEEK_API_KEY=sk-your-key
export AGENT_API_KEY=dev-key-change-in-production

# 2. 启动应用（开发模式）
./mvnw spring-boot:run

# 3. 验证启动
curl http://localhost:8080/actuator/health
# 期望: {"status":"UP"}
```

## 场景 1: 普通对话

```bash
curl -X POST http://localhost:8080/api/chat/simple \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"message": "你好，请用三句话介绍自己", "model": "deepseek-v4-flash"}'
```

**预期**: 返回包含 `response` 字段的 JSON，内容为 AI 的自我介绍。

## 场景 2: 工具调用

```bash
curl -X POST http://localhost:8080/api/chat/simple \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"message": "帮我计算 2^10 + 3*4 的结果", "useTools": "true"}'
```

**预期**: AI 调用 calculator 工具，返回 1036。

## 场景 3: 指定 Agent 对话

```bash
# Code Agent
curl -X POST http://localhost:8080/api/chat/agent/code \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"message": "写一个 Java 快速排序", "model": "deepseek-v4-pro"}'

# Research Agent
curl -X POST http://localhost:8080/api/chat/agent/research \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"message": "研究 Spring AI 1.0 的核心特性"}'
```

**预期**: Code Agent 返回代码，Research Agent 返回研究摘要。

## 场景 4: 多 Agent 编排

```bash
# 顺序编排
curl -X POST http://localhost:8080/api/chat/orchestrate \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"message": "分析快速排序算法", "strategy": "sequential", "agents": ["code", "research"]}'

# 并行编排
curl -X POST http://localhost:8080/api/chat/orchestrate \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"message": "对比 Java 和 Python 的并发模型", "strategy": "parallel", "agents": ["code", "research"]}'
```

**预期**: 返回 `agentResults` 包含各 Agent 的输出。

## 场景 5: RAG 文件上传与检索

```bash
# 上传文本文件
echo "Java 虚拟线程是轻量级并发单元，适合 IO 密集型任务。" > /tmp/test.txt
curl -X POST http://localhost:8080/api/rag/upload \
  -H "X-API-Key: $AGENT_API_KEY" \
  -F "file=@/tmp/test.txt"

# 上传 Markdown 文件
echo "# Spring AI\nSpring AI 提供 ChatClient 和工具调用。" > /tmp/test.md
curl -X POST http://localhost:8080/api/rag/upload \
  -H "X-API-Key: $AGENT_API_KEY" \
  -F "file=@/tmp/test.md"

# 搜索
curl "http://localhost:8080/api/rag/search?query=虚拟线程&topK=3" \
  -H "X-API-Key: $AGENT_API_KEY"

# 带上下文查询
curl -X POST http://localhost:8080/api/rag/query \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"question": "虚拟线程适合什么场景？"}'
```

**预期**: 搜索结果包含上传的文本；带上下文查询返回基于上传内容的回答。

## 场景 6: 清空对话历史

```bash
# 查看历史
curl http://localhost:8080/api/chat/history/default \
  -H "X-API-Key: $AGENT_API_KEY"

# 清空
curl -X DELETE http://localhost:8080/api/chat/history/default \
  -H "X-API-Key: $AGENT_API_KEY"

# 再次查看（应为空）
curl http://localhost:8080/api/chat/history/default \
  -H "X-API-Key: $AGENT_API_KEY"
```

**预期**: 清空后历史为空数组 `[]`。

## 场景 7: 流式输出（SSE）

在浏览器中打开 http://localhost:8080 ，启用"流式输出"开关，选择 Agent，输入消息。

**预期**: 文字逐字出现，前端正确解析 SSE data 行。

## 场景 8: 工具直接执行

```bash
# 计算器
curl -X POST http://localhost:8080/api/tools/calculator \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"input": "(2+3)*4"}'

# 时间
curl -X POST http://localhost:8080/api/tools/time \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $AGENT_API_KEY" \
  -d '{"input": "now"}'
```

**预期**: 返回工具执行结果。

## 场景 9: 安全验证

```bash
# 无 API Key 的 POST 请求应被拒绝
curl -X POST http://localhost:8080/api/chat/simple \
  -H "Content-Type: application/json" \
  -d '{"message": "test"}'
# 预期: 401 Unauthorized

# 错误 API Key 应被拒绝
curl -X POST http://localhost:8080/api/chat/simple \
  -H "Content-Type: application/json" \
  -H "X-API-Key: wrong-key" \
  -d '{"message": "test"}'
# 预期: 401 Unauthorized

# 公开 GET 接口不需要 Key
curl http://localhost:8080/api/chat/models
# 预期: 200 OK
```
