# AI Agent 改造交付稿（Trae 实施用）

> 用途：把当前“多 Agent 功能展示项目”收敛为**通用企业/研发知识库问答底座**。
> 约束：只模仿开源项目的产品模型和工程模式，不照搬功能；保留 Java 21 + Spring AI 1.0 技术栈；产品是通用底座，不绑定某个公司或某套固定语料。

## 0. 一句话定位

做一套可部署到任意团队/企业的知识库问答平台：管理员建立知识空间，成员上传并管理文档版本，AI 在“权限可见的文档”上检索作答，答案必须带引用、可解释、可反馈。

“通用”体现在：

- 多知识空间/多租户概念从第一天就进入数据模型，而不是写死一个知识库。
- 模型、Embedding、向量库、文档解析器、重排器全部走配置和接口。
- 领域 Agent、工具、API 都带命名空间和权限约束。
- 企业可以换自己的语料、模型和部署环境，不改核心代码。

## 1. 范围处置表

| 现有能力 | 处置 | 理由 |
| --- | --- | --- |
| Coordinator / Code / Research / Data / ReAct 五 Agent | 解耦保留，默认 UI 不再暴露 | 知识库问答只需要一个“问答链路”；专家 Agent 后续可作为可插拔技能恢复 |
| 天气、代码生成、系统信息、HTTP 等工具 | 移出主链路，保留注册表 | 不再是核心卖点，避免功能堆砌 |
| Calculator / File 工具 | 改造后保留为内部工具 | File 工具将来可做“引用原文导出/临时工作区” |
| RAG 上传/检索/带上下文回答 | 重构成知识库核心 | 需补版本、权限、引用、置信度 |
| 对话记忆 | 保留并增强 | 需按知识空间隔离，记录引用与反馈 |
| SSE 流式 | 保留 | 作为问答主通道 |
| Web UI 聊天页 | 重构为运营后台式 UI | 需要知识空间、文档、版本、权限、问答记录等视图 |
| InMemory / Redis / Qdrant 后端 | 保留抽象，补充持久化元数据 | 演示用 InMemory；生产至少需要对象存储 + 元数据库 + 向量库 |

## 2. 核心领域模型

| 实体 | 职责 | 关键内容 |
| --- | --- | --- |
| Tenant / Workspace | 顶层隔离 | id、name、status |
| KnowledgeSpace | 知识空间 | 名称、可见范围、默认权限、embedding 配置 |
| Document | 文档元数据 | 文件名、MIME、状态、所属空间、创建者 |
| DocumentVersion | 文档版本 | versionNo、hash、解析状态、生效状态、回滚来源 |
| Chunk | 可检索片段 | versionId、content、embeddingId、page/heading/序号 |
| PermissionRule | 权限规则 | principal、scope、资源级别、allow/deny |
| Conversation / Message | 问答会话 | spaceId、user、content、model、tokens |
| Citation | 引用 | messageId、documentId、versionId、chunkId、score |
| Feedback | 用户反馈 | messageId、thumbs、reason、correction |

## 3. 核心机制要求

| 机制 | 最低要求 |
| --- | --- |
| 文档版本 | 新上传创建新版本；旧 chunk 标记失效但不物理删除；支持查看历史版本和回滚 |
| 权限 | 检索 SQL/过滤条件必须带权限约束，不允许“先全量检索再前端过滤”；支持空间级与文档级拒绝 |
| 引用溯源 | 返回的每条 chunk 都保留 documentId/versionId；回答写入 Citation；前端可点击查看原文片段 |
| 答案置信度 | 综合检索分数、重排分数（可选）、引用一致性给出 high/medium/low；低置信度应要求澄清或回答“信息不足” |
| 通用问答 | 同一套数据模型可服务研发文档、制度文档、产品手册等多个知识空间 |
| 可配置性 | LLM/Embedding/VectorStore/Reranker 均通过配置和接口切换 |

## 4. 当前问题改造表

| ID | 严重性 | 问题 | 改造要求 |
| --- | --- | --- | --- |
| B1 | P0 | `VECTOR_STORE_TYPE=qdrant` 启动失败，Qdrant 自动配置被无条件排除且无替代 Bean | 按存储类型提供 VectorStore Bean；Docker Compose 必须可一键启动 |
| B2 | P1 | 上传 >1MB 文件被 Spring 默认 multipart 限制拦成 500 | 配置 `max-file-size`，返回 413；服务端校验与容器限制一致 |
| B3 | P1 | SSE + 工具路径并未真正流式，默认 Coordinator 又没有工具 | 明确“工具模式/普通流式”两条链路，UI 不允许误导组合 |
| B4 | P2 | InMemory 向量检索忽略 similarityThreshold，无关文档也会进入上下文 | 检索统一过滤阈值；阈值可配置 |
| B5 | P2 | 测试看似全绿但会真实请求 DeepSeek，删除接口测试未真正删除 | 单测禁止外网；用 Mock/本地受保护接口；补齐删除断言 |
| B6 | P2 | 模型名/默认模型在前后端多处硬编码 | 统一模型注册表，从配置读取；上线前用真实 API 验证模型名 |
| B7 | P2 | RAG 接口缺少入参校验和统一错误语义 | 空 content/question 返回 400；异常不要透传底层信息 |
| B8 | P2 | Actuator 公开且 show-details=always；SSRF 重定向、文件符号链接可绕过 | 公共部署前收口；demo 阶段至少不宣称“生产级五层防护” |
| B9 | P3 | 前端设置弹窗无入口，移动端无法改服务器/Key | 统一到可访问的设置入口 |
| B10 | P3 | 文档与实现不一致（LRU、一键生产模式、75 个测试等表述过强） | 文档只声明已验证能力 |

## 5. 推荐实施里程碑

| 阶段 | 目标 | 交付物 | 验收 |
| --- | --- | --- | --- |
| M0 | 基线可运行 | 修复 B1-B4；`mvn clean verify` 绿；Docker/Qdrant 可启动 | 无外网 API Key 也可完成测试与启动 |
| M1 | 知识库底座 | KnowledgeSpace/Document/Version/Chunk 模型、上传解析、检索服务 | 上传同一文件两版，可检索当前版本 |
| M2 | 问答主链路 | 空间问答、引用、置信度、流式、记忆隔离 | 一次问答返回 answer + citations + confidence |
| M3 | 权限与运营 | 空间成员/角色/文档权限、管理后台、反馈记录 | 无权限文档完全不可检索和回答 |
| M4 | 企业化 | 配置化 Provider、审计日志、导出、部署文档、E2E 验收 | 新环境仅改配置即可部署 |
| M5 | Demo 化 | 一套通用演示语料与脚本 | Demo 不依赖公司内部数据 |

## 6. 开源项目参考

| 项目 | 借鉴点 | 不要照抄 |
| --- | --- | --- |
| [Langchain-Chatchat](https://github.com/chatchat-space/Langchain-Chatchat) | 知识库/模型分层、文档处理管线、多后端接入 | 不继承 Python 栈；不复制其 Agent 编排复杂度 |
| [RAGFlow](https://github.com/infiniflow/ragflow) | 文档解析质量、chunk 与模板、引用片段展示 | 第一版不做重型可视化工作流 |
| [FastGPT](https://github.com/labring/FastGPT) | 知识库/应用配置化、中文体验、权限与分享模式 | 不把“低代码流程编辑器”当作核心 |
| [Dify](https://github.com/langgenius/dify) | Provider 抽象、应用编排、观测与评估体系 | 不做全栈 SaaS，只吸收分层思路 |
| [QAnything](https://github.com/netease-youdao/QAnything) | 离线/私域部署、引用与多轮检索 | 不绑定固定推理后端 |
| [Haystack](https://github.com/deepset-ai/haystack) | Pipeline 化检索生成、可插拔组件 | 保留 Spring AI，只借组件边界 |

## 7. 给 Trae 的实施约束

1. 先定义领域模型和 API 契约，再写前端；不要把当前页面直接“补按钮”。
2. 所有资源都有 `tenantId/spaceId`；新接口从第一版就校验权限。
3. 移除能力优先用特性开关/隐藏，不急于删源码；让项目保持“通用可扩展”。
4. 测试不能依赖真实 API Key，不能访问外网；提供本地 fixture。
5. 错误响应统一，不向客户端返回内部异常文本。
6. 演示语料只作为示例，不写进业务默认值。
7. Demo 视频/截图在 M4-M5 完成后另行制作，本期不做。

## 8. 阶段验收命令

```bash
mvn clean verify --no-transfer-progress
```

手工验收：

```text
1. memory 模式正常启动与问答
2. qdrant 模式经 docker compose 正常启动
3. 上传 1KB、1.5MB、8MB 文件得到一致且正确的响应
4. 创建两个知识空间，A 空间的用户检索不到 B 空间内容
5. 回答携带引用片段，前端可定位到原文和版本
```
