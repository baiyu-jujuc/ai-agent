# Trae 执行总文档（代码评审修复 + UI/API Key 任务）

> 交付对象：Trae
> 建议基线：`7baa478 refactor: transform into enterprise knowledge-base QA platform`
> 开始前请先确认：如果你从当前工作区开始，工作区中已经有评审阶段的未提交“预览修改”，不要把它们当作最终实现；最终实现必须以本文档清单为准，并保证全量测试通过。

## 0. 本次需要完成的任务

1. 按“B1-B10”继续完成企业知识库方向的后端修复。
2. 按 [docs/trae-ui-api-key-prompt.md](./trae-ui-api-key-prompt.md) 完成页面模型 Key 和浅色 UI 任务。
3. 最终把改动提交成一个清晰的分支，不要混入无关文件。

## 1. 后端修复清单

### 1.1 Qdrant 生产模式可启动

现象：`VECTOR_STORE_TYPE=qdrant` 时，如果 Qdrant 自动配置被排除，应用会因为缺少 `VectorStore` Bean 启动失败；如果自动配置保留，memory 模式又会因为缺少 `EmbeddingModel` 失败。

目标：

- memory 模式启动不依赖 Qdrant 或 EmbeddingModel。
- qdrant 模式无需设置奇怪的空 `EXCLUDE_QDRANT` 环境变量即可启动。
- `docker compose up` 默认也能启动。

建议实现方式（二选一）：

- 方案 A：始终排除 Qdrant starter 自动配置，在 `VectorStoreConfig` 中按 `agent.storage.vector-store=qdrant` 显式创建 `VectorStore`。直接使用 `QdrantGrpcClient` 时需要补充缺失的编译依赖，例如 `io.grpc:grpc-api`/`grpc-stub` 的 compile scope，不能留下无法编译的代码。
- 方案 B：移除自动配置排除，并让 `EmbeddingModel` Bean 在 memory/qdrant 模式下都可创建。若采用此方案，需要确认没有 Qdrant 服务时应用仍能正常启动，不能把启动变成连接依赖。

涉及文件：

- `src/main/java/com/baiyu/agent/config/VectorStoreConfig.java`
- `src/main/java/com/baiyu/agent/config/AiConfig.java`
- `src/main/resources/application.yml`
- `docker-compose.yml`

验收：

```bash
# memory 模式可启动
mvn spring-boot:run

# qdrant 模式至少能完成 Bean 装配（有 Qdrant 环境时再验证真实检索）
VECTOR_STORE_TYPE=qdrant ./mvnw spring-boot:run
```

### 1.2 上传超过限制返回 413

现象：Spring multipart 达到上限时会抛 `MaxUploadSizeExceededException`，当前被当成普通运行时错误返回 500。

目标：

- `MaxUploadSizeExceededException` 返回 HTTP 413。
- 保留现有 `max-file-size: 10MB`、`max-request-size: 12MB` 配置。

涉及文件：

- `src/main/java/com/baiyu/agent/config/GlobalExceptionHandler.java`
- `src/main/resources/application.yml`

### 1.3 同名文档上传必须创建新版本而不是新文档

现象：同一知识空间连续上传相同文件名时，当前逻辑会新建一份 Document，导致版本号无法真正递增。

目标：

- 同一 `spaceId + filename` 复用同一个 `Document`。
- 每次上传在该 Document 下创建新的 `DocumentVersion`。
- 上一个活跃版本及其 chunk 变为非活跃。
- Document 增加 `(space_id, filename)` 唯一约束；DocumentVersion 增加 `(document_id, version_no)` 唯一约束。

涉及文件：

- `src/main/java/com/baiyu/agent/kb/entity/Document.java`
- `src/main/java/com/baiyu/agent/kb/entity/DocumentVersion.java`
- `src/main/java/com/baiyu/agent/kb/repository/DocumentRepository.java`
- `src/main/java/com/baiyu/agent/kb/KnowledgeBaseService.java`

### 1.4 回滚后必须重新启用目标版本 chunks

现象：当前回滚先禁用所有版本 chunks，再启用目标版本 chunks，但“启用”查询只查 `enabled=true`，所以目标 chunks 永远无法恢复。

目标：

- 禁用/启用都按 `versionId` 查询全部 chunks。
- 回滚后目标版本的 chunks 必须能被检索到。
- 增加测试覆盖“目标版本先被禁用，回滚后重新启用”。

涉及文件：

- `src/main/java/com/baiyu/agent/kb/repository/ChunkRepository.java`
- `src/main/java/com/baiyu/agent/kb/KnowledgeBaseService.java`
- `src/test/java/com/baiyu/agent/kb/KnowledgeBaseServiceTest.java`

### 1.5 KB 检索不能把完全无关的 chunks 塞进上下文

现象：`KnowledgeBaseService.searchChunks` 只排序并截断，相似度为 0 的内容也可能进入问答上下文。

目标：

- 检索结果过滤无匹配内容，例如只保留大于最小分数的结果。
- 阈值要有明确常量，并且被测试覆盖。

涉及文件：

- `src/main/java/com/baiyu/agent/kb/KnowledgeBaseService.java`
- `src/test/java/com/baiyu/agent/kb/KnowledgeBaseServiceTest.java`
- `src/test/java/com/baiyu/agent/kb/KbQaServiceTest.java`

### 1.6 KB 上传支持 PDF 文本解析

现象：知识库模块目前把上传内容直接按 UTF-8 文本处理，PDF 会成为乱码。

目标：

- `.pdf` 上传使用已有 PDF Document Reader 解析文本后再分块。
- 解析失败返回明确的 400 或业务错误，而不是让乱码进入知识库。
- 非 PDF 仍保持 TXT/Markdown 文本读取。

涉及文件：

- `src/main/java/com/baiyu/agent/kb/KnowledgeBaseService.java`
- 如需要补充测试文件或 fixture，自行增加。

### 1.7 空间成员列表接口必须返回真实数据

现象：`GET /api/kb/spaces/{spaceId}/members` 固定返回空数组。

目标：

- `PermissionService` 增加 `listMembers(spaceId)`。
- Controller 返回 `SpaceMember` 列表。

涉及文件：

- `src/main/java/com/baiyu/agent/kb/PermissionService.java`
- `src/main/java/com/baiyu/agent/kb/KnowledgeBaseController.java`
- 可增加 Controller/Service 测试。

### 1.8 测试不得触发真实 LLM 调用

现象：安全测试里的“正确 Key 的 POST”仍会触发 ChatModel 请求。

目标：

- 使用不依赖 LLM 的受保护 POST 接口做鉴权测试，例如 `/api/tools/calculator`。
- 测试运行不依赖外网、不等待连接超时。

涉及文件：

- `src/test/java/com/baiyu/agent/config/SecurityConfigTest.java`

### 1.9 JPA 生产配置

目标：

- 关闭 `spring.jpa.open-in-view`，消除启动警告。
- 数据库生产配置不在本轮展开，只保留当前 H2 开发默认。

涉及文件：

- `src/main/resources/application.yml`

### 1.10 Docker Compose 默认开箱可跑

目标：

- Compose 默认使用 memory 模式，避免没有 Qdrant/Redis/Embedding 配置时直接失败。
- Qdrant/Redis 仍然保留在 Compose 中，并透传生产所需环境变量。

涉及文件：

- `docker-compose.yml`

## 2. 页面模型 Key 与浅色 UI

请严格按 [docs/trae-ui-api-key-prompt.md](./trae-ui-api-key-prompt.md) 执行，重点包括：

- 区分“平台访问 Key”和“模型 API Key”。
- 默认使用服务端环境变量；前端模型 Key 模式默认关闭并安全实现。
- 浅色 DeepSeek 风格对话页、中文优先、移动端可用。
- 不暴露任何 Key 到页面文案、日志、错误信息、URL。

## 3. 涉及文件汇总

后端：

- `src/main/java/com/baiyu/agent/config/AiConfig.java`
- `src/main/java/com/baiyu/agent/config/VectorStoreConfig.java`
- `src/main/java/com/baiyu/agent/config/GlobalExceptionHandler.java`
- `src/main/java/com/baiyu/agent/kb/entity/Document.java`
- `src/main/java/com/baiyu/agent/kb/entity/DocumentVersion.java`
- `src/main/java/com/baiyu/agent/kb/repository/ChunkRepository.java`
- `src/main/java/com/baiyu/agent/kb/repository/DocumentRepository.java`
- `src/main/java/com/baiyu/agent/kb/KnowledgeBaseService.java`
- `src/main/java/com/baiyu/agent/kb/KnowledgeBaseController.java`
- `src/main/java/com/baiyu/agent/kb/PermissionService.java`
- `src/main/resources/application.yml`
- `docker-compose.yml`

测试：

- `src/test/java/com/baiyu/agent/config/SecurityConfigTest.java`
- `src/test/java/com/baiyu/agent/kb/KnowledgeBaseServiceTest.java`
- `src/test/java/com/baiyu/agent/kb/KbQaServiceTest.java`

前端/文档：

- `src/main/resources/static/index.html`
- `README.md`
- `docs/trae-ui-api-key-prompt.md`

## 4. 验收

```bash
mvn clean verify --no-transfer-progress
```

手工验收：

1. memory 模式启动无报错。
2. qdrant 模式完成 Bean 装配；Compose 默认启动不依赖手工空变量。
3. 同一文件名连续上传两次，Document 只有一条，Version 从 1 到 2。
4. 回滚到 v1 后，搜索能命中 v1 内容而不是返回空。
5. 上传超过限制返回 413。
6. 安全测试不调用真实 LLM。
7. 页面浅色、中文优先、移动端无溢出。
8. 页面不出现真实模型 Key，所有 Key 都不进日志和 URL。

## 5. 完成后交接给 Codex 定稿

Trae 完成并自行验证后，请保留一个清晰的 diff 或分支，返回给 Codex 做最终代码审查和定稿；不要直接合入 main。
