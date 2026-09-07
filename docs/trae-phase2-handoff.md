# Trae Phase 2 执行文档：打通企业知识库问答主链路

> 工作目录：`D:\AI Agent (test)`
> 建议基线：GitHub `main`，当前参考提交 `5e10f6f`
> 目标：真正跑通“登录/建空间 -> 上传文档 -> 选择知识空间 -> 带引用回答 -> 反馈 -> 权限校验”的完整闭环，并补齐页面模型 Key、Qdrant 生产模式和 README/截图。

## 0. 执行前必读

请先阅读以下文档，按其中原则执行，不要只处理本文档的新增任务：

- [docs/trae-execution-handoff.md](./trae-execution-handoff.md)
- [docs/trae-refactor-handoff.md](./trae-refactor-handoff.md)
- [docs/trae-ui-api-key-prompt.md](./trae-ui-api-key-prompt.md)

如果工作区中已经存在未提交的评审修改，不要把它们当成最终实现；最终实现以“干净基线 + 本文档验收”为准。

## 1. 一句话目标

用户能在页面里：

1. 注册/登录并进入一个知识空间；
2. 上传一份 TXT / Markdown / PDF 文档；
3. 选择该知识空间后直接提问；
4. 回答展示引用片段、版本、置信度；
5. 对回答点赞/点踩并填写原因；
6. 无权用户无法看到或检索到不属于自己的空间/文档。

## 2. 当前项目已具备与缺失

| 能力 | 当前状态 |
| --- | --- |
| 知识空间/文档/版本/Chunk/JPA 模型 | 已具备 |
| `/api/kb` 上传、版本、检索、提问、反馈 API | 后端已具备基础版 |
| 页面浅色 UI、知识空间下拉 | 已具备 |
| 页面真正调用知识空间提问 | 缺失：顶部选了空间仍走普通聊天接口 |
| 页面文档上传 | 缺失：没有上传入口和上传状态展示 |
| 回答引用可点击查看原文 | 缺失：当前 citation 只有 ID，没有文件名/版本/片段 |
| 反馈按钮 | 缺失：页面没有 up/down 交互 |
| 页面模型 Key 的后端支持 | 缺失：前端发 `X-Model-API-Key`，服务端未读取 |
| 真实用户鉴权与文档级权限 | 缺失：PermissionService 存在但接口未强制执行 |
| Qdrant 生产模式 | 不完整：当前无 Qdrant 服务时默认模式无法可靠开箱 |
| README / 截图 | README 已重写；截图仍是旧深色图或缺失 |

## 3. 端到端用户故事

### 3.1 管理员视角

```text
注册管理员 -> 创建“研发中心”知识空间
-> 把自己加为 admin
-> 上传 README.md / 架构文档.pdf
-> 看到解析状态 ready 与版本号
-> 基于该空间提问，得到带引用的回答
-> 查看 down 反馈，人工修正知识库
```

### 3.2 普通成员视角

```text
注册成员 -> 被 admin 加入“研发中心”
-> 只能读取该空间允许范围的文档
-> 无法看到其他知识空间
-> 提问时只能基于有权限的文档
-> 对回答点赞/点踩
```

### 3.3 越权访问视角

```text
未登录用户调用受保护 KB API -> 401
非成员访问 team/private 空间 -> 403
尝试上传到只读空间 -> 403
被 deny 的文档不得出现在检索结果中
```

## 4. 任务拆解

### T0. 先修基线问题

按 [docs/trae-execution-handoff.md](./trae-execution-handoff.md) 完成：

- Qdrant 模式有明确可启动的 Bean 方案。
- 上传超限返回 413。
- 同名文档版本递增。
- 回滚后 chunks 重新启用。
- 检索过滤无关内容。
- PDF 文本解析。
- 成员列表返回真实数据。
- 测试不触发真实 LLM。
- Docker Compose 默认开箱可跑。

### T1. 用户与鉴权

建议引入 Spring Security + JWT，不要让“用户 ID 由请求头随意伪造”。

要求：

- 新增用户/账号实体，保存密码时使用 BCrypt 或等效安全散列。
- 提供注册、登录、获取当前用户接口。
- JWT 或等效 token 作为受保护 KB 接口的用户身份来源。
- 保留服务级 API Key 概念；两者不能混为一个字段。
- 普通对话接口也要能识别当前用户，避免伪造空间访问。

涉及方向：

- `kb` 权限模型
- `SecurityConfig` / 鉴权过滤器
- 新建 `user`、`auth` 相关包

验收：

```text
无 token -> 401
错误 token -> 401
非成员访问私有空间 -> 403
不同用户看到的可访问空间列表不同
```

### T2. 权限强制执行

将 `PermissionService` 真正接到所有 KB API 与查询链路。

要求：

- 创建空间的人自动成为该空间 admin。
- `listSpaces` 只返回当前用户可访问的空间。
- `getSpace` / `listDocuments` / `search` / `ask` / `getActiveVersion` 强制 `canRead`。
- 上传文档、回滚、成员管理强制 `canWrite` 或 `canAdmin`。
- 空间可见性为 `team/private` 时，未加入成员不可读。
- `PermissionRule` 支持文档级 `deny`，检索与问答前过滤，禁止先取全量再前端隐藏。
- 文档级权限规则需要 Repository 查询支持。

### T3. 页面模型 API Key 后端支持

保留“服务端环境变量为默认模型 Key”的安全模式，新增可选“前端模型 Key”模式。

要求：

- 新配置项默认关闭，例如 `agent.security.allow-client-model-key=false`。
- 开启后，服务端读取 `X-Model-API-Key`，并只对当前请求的 LLM 调用生效。
- 前端字段已经存在并发送该 header；后端需真正处理，不能继续忽略。
- Key 不进入日志、数据库、URL、异常响应。
- 请求结束后不保留临时 Key。
- 页面提示文字应区分“平台访问 Key”与“模型 API Key”。

技术约束：

- 当前 `AiConfig` 中的 `OpenAiApi`/`ChatModel` 是单例，若不能按请求覆盖 Key，需要抽象成可创建“按请求模型客户端”的工厂，而不是把 key 放进线程局部后不清除。
- 前端提供的 Key 只允许用于服务端配置的模型端点，不允许用于任意用户自定义 URL。

### T4. 页面文档上传

在浅色 UI 中加入文档上传能力。

要求：

- 页面选择某个知识空间后，显示该空间文档列表。
- 支持拖拽或选择文件上传 TXT / Markdown / PDF。
- 上传后展示解析状态：parsing / ready / failed。
- 显示文档版本列表与当前活跃版本。
- 管理员可回滚到历史版本。
- 上传接口沿用 `/api/kb/spaces/{spaceId}/documents`，不要另造一套重复 API。

### T5. 页面知识空间提问

顶部知识空间选择器必须真正决定提问目标：

- 未选择空间：维持普通对话入口，但明确显示“普通对话”。
- 选择知识空间后：发送到 `/api/kb/spaces/{spaceId}/ask`。
- 提问时带上当前用户会话上下文。
- 返回后渲染：answer、confidence badge、引用列表。
- 页面不能让“选了知识空间但实际仍走普通聊天”的情况继续存在。

### T6. 引用与反馈 UI

后端返回的 citation 需要足够前端展示，不能只给 ID。

建议 response 中的 citation 至少包含：

```json
{
  "citationId": "uuid",
  "documentId": "uuid",
  "documentName": "架构文档.pdf",
  "versionId": "uuid",
  "versionNo": 2,
  "chunkId": "uuid",
  "content": "引用片段",
  "score": 0.86
}
```

前端要求：

- 引用以编号 `[1]` 形式插入回答正文。
- 点击引用展开原文片段，展示文件名和版本号。
- 置信度显示高/中/低。
- 每条回答底部提供“有帮助 / 没帮助”。
- 没帮助时可填写原因或纠正文本，调用 `/api/kb/feedback`。
- 提交后显示“已记录”。

### T7. 对话上下文与消息持久化

知识空间问答的 `conversationId` 应隔离到“用户 + 空间 + 会话”，不要跨空间串上下文。

建议：

- 页面为每个空间维护独立 `conversationId`。
- `/api/kb/spaces/{spaceId}/ask` 写入用户消息、回答、citation。
- 支持按 messageId 回查 citation 和 feedback。

### T8. Qdrant 与向量检索生产化

目标：使用真实 Embedding + VectorStore 的 RAG 链路可以运行，并且旧版本向量不会污染新版本检索。

要求：

- Qdrant 模式有显式、可编译、可启动的 Bean。
- Compose 默认不依赖手工空环境变量。
- 文档上传/回滚时，VectorStore 中对应版本向量能删除或失效。
- 检索结果必须携带空间与权限过滤条件。
- 保留 memory 模式作为测试和零依赖开发模式。
- 补充 Qdrant/Embedding 的 `.env.example` 说明与 README 启动命令。

### T9. README 与截图

当前 [README.md](../README.md) 已经按知识库定位重写，请在其基础上继续更新，不要恢复旧的多 Agent 展示定位。

需要更新：

- 将“当前状态与 Roadmap”改为已完成内容。
- 补一段“端到端演示步骤”。
- 补 API Key 双模式说明。
- 补权限/JWT、Qdrant 生产模式说明。
- 删除或替换旧截图。
- 生成新截图并放置到 `docs/screenshots/`，建议包含桌面端与移动端。

## 5. 优先级

| 优先级 | 任务 | 原因 |
| --- | --- | --- |
| P0 | T0 基线修复 | 当前仓库仍有启动/版本/回滚缺陷 |
| P0 | T1 + T2 用户鉴权与权限 | 所有 KB 能力都依赖身份边界 |
| P0 | T4 + T5 上传与知识空间提问 | 这是面试/演示主链路 |
| P1 | T6 引用与反馈 UI | 让“引用溯源/置信度”成为可见卖点 |
| P1 | T7 会话隔离 | 避免多空间上下文串扰 |
| P1 | T8 Qdrant 生产模式 | 证明 RAG 不是纯关键词演示 |
| P2 | T3 页面模型 Key 后端支持 | 默认关闭，不影响主流程 |
| P2 | T9 README 与截图 | 最后统一收尾 |

## 6. 不建议做的事

- 不要继续保留“页面选空间但实际不调用 KB ask”的行为。
- 不要用 `X-User-Id` 之类可伪造 header 冒充真实用户鉴权。
- 不要把页面模型 Key 写入服务端全局配置或日志。
- 不要为 Qdrant 引入一个无法编译、只存在于 review diff 的方案。
- 不要在 README 中声明未验收完成的功能。

## 7. 验收命令

```bash
mvn clean verify --no-transfer-progress
```

手工验收清单：

1. 注册 A、B 两个用户。
2. A 创建私有空间并上传文档。
3. B 登录后看不到该空间，直接调用 API 返回 401/403。
4. A 将 B 加入为 reader 后，B 可提问但不可上传。
5. 页面选择空间后提问，返回的答案中引用和置信度可见。
6. 点击引用能看到文档名、版本号与原文片段。
7. 点击“没帮助”并提交原因后，管理员能通过 API 查到 feedback。
8. 上传同一文件两次，版本号递增。
9. 回滚到 v1 后，检索命中 v1 内容。
10. 开启 `allow-client-model-key` 后，页面模型 Key 能实际驱动请求。
11. Qdrant 模式能启动并在 README 的命令下完成一次真实检索。
12. README 截图是浅色 UI 的新截图。

## 8. 输出与交接

完成并自测后，请：

- 保持一个清晰的分支或完整 diff；
- 不要直接合入 `main`；
- 把 README、截图和新增测试清单一起返回，交由 Codex 做最终代码审查与定稿。
