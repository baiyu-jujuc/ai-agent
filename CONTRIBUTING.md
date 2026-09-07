# Contributing to AI Agent

感谢你考虑为本项目做贡献！以下指南帮助你快速参与。

## 开发环境

- JDK 21
- Maven 3.9+（项目已含 Maven Wrapper，无需本地安装）
- Docker（可选，生产模式需要）
- DeepSeek API Key（测试中通过 Mock 避免真实调用）

## 快速验证

```bash
# 克隆仓库
git clone https://github.com/baiyu-jujuc/ai-agent.git
cd ai-agent

# 编译 + 运行全部测试（无需 API Key 或 Docker）
./mvnw clean verify
```

`mvn verify` 必须全绿后再提交。

## 分支规范

- `main` — 可发布的稳定分支
- `feature/*` — 新功能开发分支
- `fix/*` — Bug 修复分支

提交前请 rebase 到 `main` 最新。

## 提交规范

使用 Conventional Commits 风格：

```
<type>(<scope>): <subject>

feat(tool): add file upload tool
fix(memory): fix conversation eviction order
docs(readme): update architecture diagram
test(rag): add markdown upload test
refactor(tool): extract ToolRegistry
```

| type | 说明 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `docs` | 文档 |
| `test` | 测试 |
| `refactor` | 重构（无行为变更） |
| `chore` | 构建/配置 |

## 如何跑测试

```bash
# 全部测试
./mvnw test

# 单个测试类
./mvnw test -Dtest=CalculatorToolTest

# 包含集成测试
./mvnw verify
```

所有聊天测试使用 Mock `ChatModel` / `ChatClient`，不依赖真实 API Key。
网络工具测试使用可替换的 HTTP client，不发真实外网请求。

## 代码风格

- 4 空格缩进
- import 顺序：项目包 → Spring → 第三方 → JDK
- 工具方法统一 `String execute(String input)` 签名
- 新增工具：实现 `ToolComponent` 接口 + `@Tool` 注解
- 新增 Agent：继承 `AbstractAgent`，实现 `executeWithModel`

## 添加新工具

1. 在 `tool/builtin/` 下创建新类，实现 `ToolComponent`
2. 在 `execute` 方法上标注 `@Tool(name = "...", description = "...")`
3. 方法签名统一为 `public String execute(String input)`
4. 在 `CalculatorToolTest` 等同级目录下添加单元测试
5. `ToolRegistry` 会自动发现新工具，无需手动注册

## 添加新 Agent

1. 在 `agent/` 下创建新类，继承 `AbstractAgent`
2. 实现 `executeWithModel(String input, String model, List<Message> history)`
3. 通过 `super(chatClient, systemPrompt, tools)` 指定 prompt 和工具子集
4. 在 `AiConfig` 中注册为 `@Bean`
5. 添加对应测试

## PR 流程

1. Fork 仓库，创建分支
2. 确保 `./mvnw verify` 通过
3. 提交 PR，描述变更和测试方案
4. CI 必须绿色
5. 等待 review

## 报告 Bug

提交 Issue，请包含：
- 复现步骤
- 预期行为 vs 实际行为
- 环境信息（JDK 版本、OS、是否 Docker）
- 日志或截图（如有）
