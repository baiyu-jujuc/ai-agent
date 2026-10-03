# Changelog

## v0.2.1 — 2026-10-03

Agent 能力补强的第一步：安全前置（含“新路径默认拒绝”）。对 v0.2.0 无破坏性变更，但认证状态码口径有变化（未认证由 403 改为 401）。

### Security

- **新路径默认拒绝**：`anyRequest()` 由 `permitAll` 改为 **`denyAll`**——没有显式放行的新路径，匿名请求 401、已登录普通用户 403，避免“新写一个接口就默认对匿名开放”
- **`/api/agent/**` 不再匿名放行**：Agent 的只读接口（`health` / `models` / `list`）改为需要 JWT，为 `POST /api/agent/{name}`（Agent 执行入口）守底线；否则匿名就能触发模型调用（烧 Token）与内置工具
- **未认证统一返回 401**：新增 `authenticationEntryPoint` 与 `accessDeniedHandler`，把“未认证（401）”与“已认证但无权限（403）”分开，与 `ApiKeyInterceptor` 缺 Key 时的 401 口径一致
- 单页 UI 的静态路径（`/`、`/index.html`、`/favicon.ico`）与 `/error` 显式放行，避免被“默认拒绝”误伤
- `/api/agent/**` 从 `agent.security.public-paths` 移除，与其他业务接口一致（需要 `X-API-Key` + JWT）

### Tests

- 新增 `AgentAccessSecurityTest`（7 条）：匿名访问 `/api/agent/**` → 401；登录后只读接口仍可用；未来的 `POST /api/agent/{name}` 匿名 → 401；未实现的新路径默认拒绝（匿名 401 / 普通用户 403）；首页仍可匿名打开
- 既有无 JWT 用例的期望值由 403 更新为 401（`SecurityConfigTest` / `JwtRouteSecurityTest` / `LegacyRagDisabledTest` / `AdminAccessSecurityTest`）
- 测试数：v0.2.0 的 240 → **247（通过 237 + 跳过 10）**，失败 0
## v0.2.0 — 2026-09-19

AI 平台升级：统一网关 + 计量 + 稳定性 + 缓存与 Prompt 治理 + 离线评测。每一层都有独立开关，出问题可按层定位；关掉开关即退回改造前行为。

### Features

- **统一模型调用入口（L1）**：新增 `com.baiyu.agent.gateway.ModelGateway`，把原来散落在 6 个类、9 处的 `ChatClient.prompt(...)` 收口到网关内部；`AbstractAgent` / `KbQaService` / `ChatController` / `RagService` / `FunctionCallingService` / `CoordinatorAgent` 不再直接持有 `ChatClient`
- **Token 计量与成本（L1）**：新增 `llm_usage_record` 表，记录模型、prompt/completion tokens、`usage_source`（PROVIDER / ESTIMATED）、延迟、场景、单价快照与整数微元成本；计量用 `REQUIRES_NEW` 独立事务并吞异常，计量失败不影响回答
- **流式计量（L1）**：实测确认流式 usage 只出现在最后一片 chunk 上，因此取末片覆盖写而不是逐片累加；记账用 `doFinally`，取消与异常同样留痕
- **用量查询与管理接口（L1）**：`GET /api/admin/usage` 按天、空间、场景、模型聚合；`GET /api/admin/models`、`PUT /api/admin/models/{routeKey}`、`POST /api/admin/models/reload` 支持运行时改路由（数据库 → yml → 代码默认值三级回退，本地缓存 30 秒）
- **稳定性（L2）**：接入 Resilience4j（编程式、按模型维度）实现 限流 → 熔断 → 重试 → 网关层超时（默认 30s，短于 HTTP 层 60s）；主模型失败自动降级到备用模型，全部失败返回可读兜底话术；降级与失败均落库并暴露指标
- **流式重试规则（L2）**：只在"还没有向下游发出任何内容"时重试；一旦开始输出就放弃重试，避免用户看到重复内容
- **语义缓存（L3）**：新增独立 collection（默认 `semantic_cache`，与知识库 `kb_chunks` 隔离），key 含空间 / 模型 / Prompt 版本，检索时按 `space_id` 过滤；拒答不缓存；缓存 Bean 只在 `VECTOR_STORE_TYPE=qdrant` 时装配，缺失时启动日志写明原因
- **Prompt 版本治理（L3）**：Prompt 模板入库（key + version + active），支持新建版本、切换生效、回滚；调用记录写入 `prompt_key` / `prompt_version`
- **离线评测（L4）**：`eval/eval_set.jsonl`（**77 条**，含 10 条拒答题、4 组语义改写对、3 组近似干扰对、1 组跨空间同题对，以及跨文档多跳、同名文档多版本与陷阱题共 10 条难例）；`EvalRunner` 跑批产出 Hit@5、引用准确率、拒答正确率、答案相关性（0–3 分、同条评 2 次取平均）、P95 延迟、平均单次成本，输出 JSON + Markdown 报告
- **可观测（L4）**：新增 `micrometer-registry-prometheus` 与 `/actuator/prometheus`；自定义指标 `llm_call_total` / `llm_token_total` / `llm_cost_micros_total` / `llm_fallback_total` / `llm_call_duration`，tag 只用低基数维度（model / scene / outcome）

### Infrastructure

- 新增依赖：`resilience4j-spring-boot3` 2.2.0、`resilience4j-micrometer` 2.2.0、`micrometer-registry-prometheus`（注意：Spring Boot 3.4.4 的 BOM 不管理 resilience4j 版本，必须显式指定）
- 新增 3 张表（只增不改）：`llm_usage_record`、`prompt_template`、`model_route_config`
- 新增启动初始化器：默认路由与内置 Prompt 模板 v1（幂等，不覆盖已有配置）

### Security

- **修复管理接口越权**：开启方法级安全（`@EnableMethodSecurity`），`/api/admin/**` 由"登录即可访问"改为**要求 ADMIN 角色**（`@PreAuthorize("hasRole('ADMIN')")`）；普通用户的 JWT 访问会得到 403，而不是读到全站成本数据、改模型路由和 Prompt
- **修复 Actuator 越权**：`/actuator/**` 由"全部 permitAll"收敛为**只放行 `/actuator/health`**；`metrics` 与 `prometheus` 里有 token 总量、成本、调用量与错误分布，属于算账数据，因此要求 **ADMIN 角色**——普通用户即使登录也读不到（实测：不带 token → 403，普通用户 JWT → 403，仅 ADMIN 可读）
- 新增 ADMIN 角色的两种来源：`ADMIN_USERNAMES` 名单（注册即为 admin，默认空）与 `BOOTSTRAP_ADMIN_*` 引导管理员（幂等，且**不会给已存在的同名用户自动提权**）
- `GlobalExceptionHandler` 新增 `AccessDeniedException` 处理：越权返回 **403**，不再被兜底 handler 吞成 500（否则"没权限"会被误报成"服务器故障"）
- **修复语义缓存写入真实 Qdrant 时的 3 个缺陷**（单测用 mock 向量库发现不了，靠直连 Qdrant 的集成测试暴露）：
  ① Qdrant point id 必须是 UUID，原先用 64 位 sha256 字符串会被拒绝且被 catch 吞掉；② payload 不支持 `Long`，
  `expires_at` 改用 epoch 秒（Integer）并跳过 null metadata；③ TTL 边界由 `>` 改为 `>=`
- 修正 Qdrant 端口口径：REST 6333 / **gRPC 6334**，Spring AI 的 `QdrantVectorStore` 走 gRPC，默认值与 Compose 统一改为 6334
- Dockerfile 构建阶段改用 `scripts/maven-aliyun-settings.xml`（阿里云 Maven 镜像），并新增 `scripts/run-docker-from-jar.sh`：
  本机容器无外网出口时，用"Windows 侧出 jar、容器只运行"的方式验证容器化

### Tests

- 自动化测试从 134 个增加到 240 个（其中 10 个依赖外部服务的集成用例默认跳过：3 个用量探针 + 7 个 Qdrant 集成用例），失败 0：新增网关计量、成本计算、降级、熔断、限流、超时、流式记账、Prompt 版本、语义缓存空间隔离（含直连真实 Qdrant 的集成用例）、评测指标、管理接口越权与 Actuator 收敛、评测播种回归等覆盖
- Phase 0 探针测试 `ProviderUsageProbeTest` 默认跳过（加 `-Dprobe.provider.usage=true` 才真实调用模型）

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
