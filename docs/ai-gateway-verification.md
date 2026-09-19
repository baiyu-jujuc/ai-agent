# AI 网关升级 · 验收证据（L1–L4）

> 采集日期：2026-09-16 / 09-17　分支：`feat/ai-gateway`
> 原则：**只写实际跑出来的结果**。没跑过的项目在"未验证/待办"一节里写清楚，不混在结论里。

---

## 0. 一句话结论

| 层 | 结论 |
|---|---|
| L1 计量 | 每次模型调用落一条用量记录；非流式与流式都拿到**供应商真实 usage**（流式取末片），场景 / 单价快照 / 整数微元成本齐全 |
| L2 稳定性 | 把上游指向不存在的端口后：**熔断器打开、请求短路、8 次调用全部返回降级话术、220–570ms 返回**，不走 500 错误 |
| L3 缓存与 Prompt | Prompt 模板入库 + 版本切换可回滚（已验证）；语义缓存**代码与测试完成**，但本机没有 Embedding Key，**运行时未做端到端实测** |
| L4 评测与指标 | 77 条评测集跑通：Hit@5 100%（67/67 可答题）、引用准确率 100%、拒答正确率 100%（10/10）、相关性 2.955、P95 4598ms、平均 10886 微元/条；`/actuator/prometheus` 有自定义指标 |

---

## 1. L1：统一入口 + 计量

### 1.1 调用入口收口

```powershell
rg -n "\.prompt\(" src/main/java
```

实测结果：`ChatClient.prompt(...)` 只出现在 `gateway/ModelGatewayImpl.java` 内部；
`ChatModelFactory` 保留的客户端自带 Key 旁路也只在网关内部被调用。
`AbstractAgent` / `KbQaService` / `ChatController` / `RagService` / `FunctionCallingService` / `CoordinatorAgent`
都不再直接持有 `ChatClient`（改造前是 6 个类、9 处调用）。

### 1.2 计量字段完整性

评测跑批后查 `/api/admin/usage`（改造前没有这张表，这是新增能力）：

```json
{ "summary": { "calls": 201, "promptTokens": "…", "completionTokens": "…", "costMicros": "…", "cacheHits": 0, "degraded": 0, "errors": 0 } }
```

每条记录包含：`modelId` / `scene` / `routeType` / `promptTokens` / `completionTokens` /
`usageSource`（PROVIDER 或 ESTIMATED）/ `promptUnitPrice` / `completionUnitPrice` / `costMicros` /
`latencyMs` / `cacheHit` / `outcome` / `createdAt`。

关键实测结论来自 Phase 0 探针（原始逐片输出见 `docs/ai-gateway-phase0.md`）：

> 供应商流式响应的 usage **只出现在最后一片 chunk** 上，中间片全为 0。
> 所以流式计量必须"取末片覆盖"，逐片累加会得到 0，成本会整块漏记。

### 1.3 成本计算

- 单价单位统一为「微元 / 百万 token」，全程整数运算（`PricingCalculator`）；
- 单价随记录写快照，供应商调价不会让历史成本失真；
- 复算脚本见执行文档第 6.3 节：逐条按 `单价 × tokens` 复算并与 `costMicros` 比对。

> ⚠️ **单价是假设值**：`application.yml` 里的 `PRICE_DEEPSEEK_CHAT_*` 按 DeepSeek 公开挂牌价换算，
> **不是账单实测值**。复算脚本验证的是"算术口径正确"，不等于"单价填对了"。
> 用之前请按自己的账单改这两个数字。

### 1.4 计量不影响主链路

- 单元测试：`UsageRecorderTest#swallowsRepositoryFailure`（仓储抛异常，调用方不受影响）；
- 设计上：`UsageRecorder.record` 用 `REQUIRES_NEW` 独立事务 + 整体 try/catch + error 日志；
- **端到端故障注入（把表改名）未执行**——本地是 H2 文件库，改名会影响后续验证，留作待办。

---

## 2. L2：稳定性（模拟故障实测）

脚本：`scripts/verify-gateway.ps1`（**模拟故障**：把模型 base-url 指向 `http://127.0.0.1:9`）

```powershell
.\scripts\verify-gateway.ps1 -Requests 8 -Port 8090
```

实测输出（摘要）：

```
App is UP
  call 1: 567 ms -> 当前模型服务繁忙，请稍后重试。
  call 2: 237 ms -> 当前模型服务繁忙，请稍后重试。
  call 3: 229 ms -> 当前模型服务繁忙，请稍后重试。
  ...（第 4–8 次同样在 220–240ms 返回降级话术）
  calls=16, errors=8, degraded=8
```

对得上验收标准的几条：

| 验收项 | 实测 |
|---|---|
| 熔断能打开 | `resilience4j_circuitbreaker_failure_rate{name="deepseek-chat"} 100.0` |
| 短路生效 | `resilience4j_circuitbreaker_not_permitted_calls_total{kind="not_permitted",name="deepseek-chat"} 11.0`（重试会再次进入熔断器，所以 11 大于请求数） |
| 降级能生效 | 8 次请求全部返回"当前模型服务繁忙，请稍后重试。"，**没有 500、没有堆栈** |
| 快速返回 | 220–570ms（验收要求 1 秒内） |
| 降级可查 | 用量表 8 条 `outcome=DEGRADED` + 8 条 `outcome=ERROR`，与指标一致 |

> **口径说明**：这是**模拟**故障（指向不存在的端口），不是线上故障演练。
> 简历/面试里必须这样表述："用模拟上游不可用的方式验证了熔断状态迁移与降级生效"。

流式重试规则（已输出就不再重试）由 `ModelGatewayImplResilienceTest` 的两条用例覆盖：
首片前失败会重试一次；已经输出"半句"后再失败**不重试**（避免用户看到重复内容）。

---

## 3. L3：语义缓存与 Prompt 治理

### 3.1 Prompt 模板入库（已完成）

- 启动时把内置模板写进 `prompt_template`（v1，幂等，不覆盖已有记录）；
- 取模板顺序：数据库 active 版本 → 代码内置模板；
- 管理接口：`GET /api/admin/prompts`、`POST /api/admin/prompts`、`POST /api/admin/prompts/{key}/activate?version=`；
- 每次问答把 `prompt_key` + `prompt_version` 写进用量记录；
- 单测覆盖：新建版本默认不生效、切换生效时旧版本置 inactive、未知版本报错、数据库不可用时回退内置模板。

### 3.2 语义缓存（**已在真实 Qdrant 上跑通**，2026-09-17）

已实现：

- 独立 collection（默认 `semantic_cache`），**与知识库 `kb_chunks` 隔离**；若配置成同名，启动时直接抛异常拒绝启动（单测断言内部 collectionName）；
- key = `sha256(spaceId | modelId | promptKey | promptVersion | 归一化问题)`；
- 检索带 `space_id` + `model_id` 等值过滤（越权防护）；TTL 默认 24 小时；
- **拒答不缓存**（网关层判断拒答话术）；
- 命中时写一条 `routeType=CACHE`、`cacheHit=true`、tokens=0、成本=0 的用量记录，便于统计"省了多少"；
- 缓存读写失败都被吞掉，不影响主链路（有单测）。

**运行时验证方式**：`SemanticCacheQdrantTest`（7 条用例）直连一个真实的 Qdrant 容器，
真的写向量、真的检索：

```bash
# WSL 里（脚本会自己确保 Qdrant 在跑）
bash "/mnt/d/AI Agent (test)/scripts/run-cache-it.sh"
```

| 用例 | 结果 |
|---|---|
| 同一个问题第二次问 → 命中（返回缓存答案、Prompt key/版本正确） | ✅ |
| 同一个问题换一个知识空间问 → **不命中**（防跨空间越权） | ✅ |
| 同一个问题换一个模型问 → 不命中 | ✅ |
| 完全无关的问题 → 不命中 | ✅ |
| 过期条目（TTL=0）→ 不命中 | ✅ |
| `evictSpace` 清空该空间缓存后 → 不命中 | ✅ |
| 缓存 collection 内部名 = `semantic_cache_it` ≠ `kb_chunks` | ✅ |

#### 这一步抓到的 3 个真 bug（单测用的是 mock 向量库，全都发现不了）

1. **Qdrant 的 point id 必须是 UUID**：原来用 64 位 sha256 字符串当 id，真实环境报
   `UUID string too large`，写入直接被 catch 吞掉——功能"看起来正常"，缓存其实一条都没写进去。
   修复：用 sha256 派生确定性 UUID（`UUID.nameUUIDFromBytes`），重复写入是覆盖而不是膨胀。
2. **Qdrant payload 不支持 `Long`**：`expires_at` 原来写 epoch 毫秒（Long），报
   `Unsupported Qdrant value type: class java.lang.Long`。
   修复：改用 epoch 秒（Integer），并跳过为 null 的 metadata（null 同样会抛异常）。
3. **TTL 边界语义**：`now > expiresAt` 会让 TTL=0 的条目在同一秒内仍然有效。
   修复：改成 `now >= expiresAt`。

三个 bug 都已补回归测试（`SemanticCacheServiceTest#storeWritesKeyTtlAndMetadata` 断言
id 是合法 UUID、过期时间是 Integer）。

#### 唯一还没做的一步：阈值校准（需要真实 embedding）

本机 `.env` 里 `EMBEDDING_API_KEY` 为空，且 **DeepSeek 不提供 embedding 接口**，
所以上面的验证用<b>确定性替身 embedding</b>（字符二元组哈希向量）驱动向量库——
它验证的是缓存链路与隔离逻辑，"语义相近但措辞不同"的相似度分数它给不出来。

**因此阈值 0.92 仍然是初始值，不是实测校准值。**
补法：配一个 OpenAI 兼容的 embedding 服务（OpenAI / 硅基流动 / 智谱 / 阿里百炼均可）→
`EMBEDDING_API_KEY=...` → `VECTOR_STORE_TYPE=qdrant` → 重跑 `run-cache-it.sh`，
把 `EVAL` 里 4 组 `paraphrase-of` 与 3 组 `near-miss-of` 用例的问法当对照样本，记录实际相似度再定阈值。

> 顺带修正一个配置坑：**Qdrant 的 REST 端口是 6333，gRPC 端口是 6334**，
> 而 Spring AI 的 `QdrantVectorStore` 走的是 gRPC。仓库原来的 `QDRANT_PORT=6333` 会连不上
> （实测报 `UNAVAILABLE: io exception`）。已把默认值、`docker-compose.yml`、`.env.example`
> 统一改成 **6334**；你自己的 `.env` 也要跟着改。

---

## 4. L4：离线评测与可观测

### 4.1 评测跑批结果（tag = post-upgrade）

```powershell
.\scripts\run-eval-local.ps1 -Tag post-upgrade-v2
```

| 指标 | 实测 | 说明 |
|---|---|---|
| 用例数 | 77（67 可答题 + 10 拒答题） | 4 个知识空间、6 份演示文件；含 10 条难例（跨文档多跳 / 同名文档多版本 / 边界拒答 / 陷阱题） |
| Hit@5 | **100.0%**（67/67） | 拒答题不计入分母（它们没有期望文档） |
| 引用准确率 | **100.0%** | 返回引用里包含期望文档的比例 |
| 拒答正确率 | **100.0%**（10/10） | 文档里没有答案（或问历史版本）时明确拒答/说明查不到 |
| 答案相关性 | **2.955**（0–3，同条评 2 次取均值） | 231 次调用里含 154 次打分调用 |
| P95 延迟 | **4598 ms** | 端到端（含检索 + 生成）；跨文档多跳题会明显拉长 |
| 平均单次成本 | **10886 微元**（约 0.01 元/条） | 来自用量表；改造前没有这个能力 |
| 降级次数 | 0 | 正常网络下未触发降级 |

完整报告：`eval/reports/eval-report-post-upgrade-v2.json` 与 `.md`（含逐条结果）。

**难例到底有没有区分度？有，而且立刻抓到一个问题：**

| 用例 | 第一次跑（有 bug） | 修完后 |
|---|---|---|
| `hard-004`~`hard-007`（同名文档：当前有效版本应为 2.0） | **1.0 分**（答出了 1.0 版的 16:00 / 30 天 / 5% / 10 分钟） | **3.0 分** |
| `hard-008`（问 1.0 版内容，应说明只提供当前版本） | 1.0 分 | **3.0 分** |
| `hard-002`（跨文档多跳：纠错流程 + 复盘时限） | —— | **1.5 分**（唯一低分，属于真实难例） |

根因排查：**不是应用的版本隔离坏了，而是评测播种的缺陷**——`EvalRunner` 用"文件名是否已存在"判断要不要灌文档，
而同一份文档的多个版本用同一个文件名，导致 2.0 版根本没灌进去，库里只有 1.0 版内容。
修复方式：改成**按内容哈希判断**（库里已有同样内容的版本才跳过），修复后同一批用例全部答对。

> 这段经历本身就是可以说给面试听的："我加的难例第一次跑全线低分，先怀疑检索串版本，
> 查下来是评测脚本自己的播种逻辑有问题；修完再跑，指标验证了版本隔离是好的。"

### 4.2 Prometheus 指标

```powershell
curl.exe -s http://localhost:8090/actuator/prometheus -H "Authorization: Bearer <token>" | findstr llm_
```

实测存在（摘录）：

```
llm_call_duration_seconds_sum{model="deepseek-chat",scene="CHAT_SIMPLE"} 1.802
llm_call_duration_seconds_count{model="none",scene="CHAT_SIMPLE"} 8
llm_call_total{...}
llm_token_total{...}
llm_cost_micros_total{...}
resilience4j_circuitbreaker_failure_rate{name="deepseek-chat"} 100.0
resilience4j_circuitbreaker_not_permitted_calls_total{kind="not_permitted",name="deepseek-chat"} 11.0
```

tag 只用 `model` / `scene` / `outcome` 这类有限枚举，**没有 userId / spaceId / conversationId**，
并有单测断言（`ModelGatewayImplTest#metricsDoNotUseHighCardinalityTags`）。

### 4.3 越权修复（2026-09-17 追加）

发现的问题是真实的：`/api/admin/**` 原先只要求"登录"，`/actuator/**` 原先整段放行——
任何注册用户都能读到全站成本数据、改模型路由与 Prompt，未登录也能读 `metrics` / `prometheus`
（里面能看出内存、线程、调用量与错误分布）。

修复内容：

- `@EnableMethodSecurity` + 四个管理 Controller 上的 `@PreAuthorize("hasRole('ADMIN')")`；
- `/actuator/**` 从 permitAll 收敛为**只放行 `/actuator/health`**，`metrics` / `prometheus` 需认证；
- `GlobalExceptionHandler` 新增 `AccessDeniedException` → **403**（原先被兜底 handler 吞成 500）；
- ADMIN 角色来源：`ADMIN_USERNAMES` 名单 / `BOOTSTRAP_ADMIN_*` 引导管理员，且**不自动给已有用户提权**。

实测证据（`scripts/verify-gateway.ps1`，本地 jar + 端口 8090）：

```
  without token -> blocked (403) as expected          # /actuator/prometheus 不带 token 读不到
  llm_call_total{model="deepseek-chat",outcome="ERROR",scene="CHAT_SIMPLE"} 6.0
  ...（带 ADMIN 的 JWT 才能读到指标）
  calls=502, errors=20, degraded=20                   # /api/admin/usage 由 ADMIN 读到
```

单元/集成测试 10 条（`AdminAccessSecurityTest`）：管理接口无 JWT → 403、普通用户 → 403、ADMIN → 200；
`/actuator/health` → 200、`metrics`/`prometheus` 无 token → 403、带 JWT → 200。

### 4.4 容器化运行验证（2026-09-17）

```bash
# WSL 里执行（用 Windows 侧构建好的 jar 打运行时镜像并启动 compose 的 app 服务）
bash "/mnt/d/AI Agent (test)/scripts/run-docker-from-jar.sh"
```

实测结果：

```
aiagenttest-app-1      app       Up 21 seconds (healthy)
aiagenttest-mysql-1    mysql     Up 7 minutes (healthy)
aiagenttest-qdrant-1   qdrant    Up 7 minutes
aiagenttest-redis-1    redis     Up 7 minutes (healthy)
```

容器内验证（用 `docker exec` 打）：

| 检查 | 结果 |
|---|---|
| `/actuator/health` | `{"status":"UP"}` |
| `/actuator/prometheus` 不带 token | **HTTP 403**（越权修复在容器里同样生效） |
| `/api/admin/usage` 不带 token | **HTTP 403** |
| `/api/chat/storage-status` | `memoryBackend=redis`、`vectorStoreBackend=memory` |
| MySQL 新表 | `llm_usage_record` / `model_route_config` / `prompt_template` 三张表都建出来了 |
| 初始化数据 | `prompt_template` 2 行（两个 key 的 v1）、`model_route_config` 2 行、`llm_usage_record` 0 行（还没发生调用） |
| 启动日志 | 网关四层开关、路由初始化、Prompt 模板初始化、语义缓存不可用原因（memory 模式）都按预期打出 |

> ⚠️ **本机 `docker compose up -d --build` 走不通**：容器**没有外网出口**
> （实测容器内 `wget https://maven.aliyun.com/...` 直接超时），而 Dockerfile 需要在构建阶段跑
> `mvn package` 下载依赖。所以本机采用"Windows 侧构建 jar → 容器只负责运行"的方式
> （`scripts/run-docker-from-jar.sh`）。等容器网络可用时，`docker compose up -d --build` 照常可用
> （Dockerfile 已配置阿里云 Maven 镜像）。

---

## 5. 未验证 / 待办（不要当成已完成）

| 项 | 状态 | 怎么补 |
|---|---|---|
| 改造前基线评测 | 未采集 | 跑批入口是本次改造的一部分，改造前不存在；补法见 `eval/README.md` 5.3（切回改造前 commit，同一份评测集 + 同一模型 + 同一机器，tag 用 baseline） |
| 语义缓存运行时实测（命中 / 跨空间隔离 / TTL / 清空） | ✅ 已实测 | `scripts/run-cache-it.sh`（真实 Qdrant，7 条用例全绿）；替身 embedding 驱动，见 3.2 |
| 缓存阈值 0.92 的实测校准 | 未做（**需要真实 embedding**） | 配 `EMBEDDING_API_KEY` 后重跑对照样本；DeepSeek 不提供 embedding，必须用第三方 OpenAI 兼容服务 |
| 「拒答不缓存」在真实 Qdrant 上的端到端验证 | 间接覆盖 | 网关层单测已覆盖（拒答不进缓存）；与本机无 embedding Key 无关，属可补项 |
| 计量故障注入（把表改名）端到端 | 未做 | 单元级已覆盖；端到端可用 MySQL 容器 `ALTER TABLE ... RENAME` |
| 重试的计量粒度 | 已知局限 | 一次候选调用只记一条 ERROR/DEGRADED；若重试真的打到上游并计费，成本记录会偏低。改进：把计量下沉到每次重试尝试 |
| 单价数值 | 假设值 | 按实际账单核对 `PRICE_DEEPSEEK_CHAT_*` |
| 容器化运行 | ✅ 已验证 | 见 4.4；本机 `--build` 因容器无外网出口走不通，改用 `scripts/run-docker-from-jar.sh`（Windows 侧出 jar，容器只运行） |
| 容器内 `docker compose up -d --build` | ❌ 本机不可用 | 容器无外网出口 → 构建阶段下载依赖超时；网络可用后可直接用 |
| **`Dockerfile`（多阶段 maven 构建）** | ⚠️ **从未成功构建过 = 未经构建验证** | 本机容器无外网出口，`mvn package` 阶段必然超时。**文档与简历都不要写"已验证容器构建"**；换成有网络的环境后跑一次 `docker compose build` 再改口径 |
| 空间内容变更时自动清缓存 | 未接线 | `SemanticCacheService.evictSpace()` 已实现且有测试，但还没挂到文档上传/回滚的调用点上 |
| 难例 `hard-002`（跨文档多跳） | 唯一低分（1.5/3） | 模型把"纠错流程的时限"答成了 P2 响应时限 + 复盘时限；可以再拆成两条更明确的用例，或作为"多跳仍需加强"的真实结论保留 |
| README 测试数与简历 | 已对齐 | 统一为 **240**：`mvn -B verify` 输出 `Tests run: 240, Failures: 0, Errors: 0, Skipped: 10`，即 **通过 230 + 跳过 10**（跳过的是 3 个用量探针 + 7 个依赖 Qdrant 的集成用例）。注意 surefire 的 `Tests run` **已经包含跳过数**，不要写成"240 通过" |

---

## 6. 复现命令清单

```powershell
cd "D:\AI Agent (test)"

# 1) 全量测试（默认不访问外网模型）
mvn -B verify --no-transfer-progress

# 2) 供应商 usage 探针（会真实调用模型，只跑 3 条）
mvn -B -Dprobe.provider.usage=true -Dtest=ProviderUsageProbeTest -DfailIfNoTests=false test
Get-Content -Encoding UTF8 target\probe\provider-usage-probe.txt

# 3) 小样本评测 → 全量评测
.\scripts\run-eval-local.ps1 -Tag smoke -Limit 10
.\scripts\run-eval-local.ps1 -Tag post-upgrade

# 4) 熔断/降级模拟故障实测
.\scripts\verify-gateway.ps1 -Requests 6 -Port 8090
#    这个脚本同时验证越权修复：不带 token 读 /actuator/prometheus 必须 403，
#    带 ADMIN JWT 才能读到指标与 /api/admin/usage

# 5) 语义缓存运行时实测（在 WSL 里跑，连真实 Qdrant）
bash "/mnt/d/AI Agent (test)/scripts/run-cache-it.sh"
```
