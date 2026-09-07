# Changelog

## v0.1.0 — 2026-09-07

首个可用版本。基于 Spring AI + DeepSeek V4 构建的多 Agent 智能体平台。

### Features

- **多 Agent 系统**：Coordinator / Code / Research / Data / ReAct，按意图路由
- **多 Agent 编排**：顺序 + 并行策略，`TaskTrace` 执行轨迹可视化
- **Function Calling**：7 个内置工具，Spring AI 原生 `@Tool` 自动注册
- **RAG 管道**：文档分块索引（TXT / Markdown / PDF），中文单字分词
- **对话记忆**：InMemory / Redis 双后端，token 预算 + 消息条数 + 会话 LRU 淘汰
- **SSE 流式输出**：支持指定 Agent 与工具调用
- **安全防护**：API Key 鉴权、限流、CORS 白名单、文件沙箱、SSRF 防护
- **ToolRegistry**：统一工具元数据管理，消除重复扫描代码
- **Web UI**：Agent 选择、工具调用开关、流式开关、执行轨迹展示

### Infrastructure

- MIT License
- GitHub Actions CI（JDK 21 + `mvn verify`）
- Maven Wrapper
- Docker 多阶段构建 + docker-compose
- `.gitattributes` 统一 LF + UTF-8
- Dependabot 依赖更新提醒

### Tests

- 75 个自动化测试：工具 / 安全 / 记忆 / RAG / 编排 / 控制器
- E2E 验收脚本 `scripts/verify-local.ps1`
- 手工演示清单 `docs/demo-checklist.md`
