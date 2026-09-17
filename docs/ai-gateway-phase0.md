# AI 网关升级 · Phase 0 基线记录

> 采集日期：2026-09-16
> 分支：`feat/ai-gateway`（从 `main` @ `6c80b43` 切出）
> 采集人：Codex 会话（在 `D:\AI Agent (test)` 本地环境执行）
> 说明：本文只记录**实际跑出来**的结果。没有跑过的项一律写"未运行"。

---

## 1. 结论速览（一句话）

| 问题 | 实测结论 |
|---|---|
| 非流式调用能否拿到 usage | **能**，供应商返回 prompt / completion / total tokens |
| 流式调用能否拿到 usage | **能，但只在最后一片 chunk 上**；中间片 usage 为 0 |
| 因此 L1 计量怎么做 | 流式也优先用 `PROVIDER` 真实值（取末片覆盖），只有末片缺失或流被取消时才降级为 `ESTIMATED` |
| 基线测试数 | **134 个 `@Test`，全部通过**，`mvn -B verify` BUILD SUCCESS |

---

## 2. 环境基线

| 项目 | 实测值 | 依据 |
|---|---|---|
| Java | 21（Oracle JDK 21，`D:\Development\JDK21\jdk`） | `mvn -v` |
| Maven | 3.9.16（本地仓库 `D:\DevCache\MavenRepo`） | `mvn -v` |
| Git 分支 | `feat/ai-gateway`，工作区干净 | `git status --short --branch` |
| 模型接入 | `https://api.deepseek.com`，实际模型名 `deepseek-chat`（`.env` 覆盖 `DEFAULT_MODEL`） | 探针输出 |
| 向量库模式 | 本地默认 `memory`；Compose 内 app 容器实测 `vectorStoreBackend=memory` | `/api/chat/storage-status` |
| 会话记忆模式 | Compose 内 app 容器实测 `memoryBackend=redis` | `/api/chat/storage-status` |
| Embedding | `.env` 里 `EMBEDDING_API_KEY` 为空，`EMBEDDING_BASE_URL=https://api.openai.com` → **本地无法计算 embedding** | `.env`（未打印密钥） |

---

## 3. 基线构建（Phase 0 清单第 3 项）

```powershell
cd "D:\AI Agent (test)"
mvn -B verify --no-transfer-progress
```

实测结果：

```
[INFO] Tests run: 134, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  43.271 s
```

完整日志：`target/verify-baseline.log`（`target/` 已被 `.gitignore` 忽略，不会误提交）。

> 注意：这条"134"是 `mvn verify` 报告里的**用例执行数**；`rg -o "@Test" src/test` 静态数出来的也是 134，两者一致。

---

## 4. usage 探针（Phase 0 清单第 5、6 项）

探针代码：`src/test/java/com/baiyu/agent/probe/ProviderUsageProbeTest.java`

设计要点：

- 默认**跳过**（`@EnabledIfSystemProperty(named = "probe.provider.usage", matches = "true")`），所以日常 `mvn verify` 和 CI 不会真实调用模型、不消耗 token；
- API Key 从环境变量 `DEEPSEEK_API_KEY` 读取，没有则回退读仓库根目录 `.env`；**只在内存里用，不打印、不落库**；
- 结果同时输出到控制台和 `target/probe/provider-usage-probe.txt`（UTF-8，避免 Windows 控制台 GBK 乱码）。

复现命令：

```powershell
cd "D:\AI Agent (test)"
mvn -B -Dprobe.provider.usage=true -Dtest=ProviderUsageProbeTest -DfailIfNoTests=false test
Get-Content -Encoding UTF8 target\probe\provider-usage-probe.txt
```

### 4.1 原始输出（逐字复制，未改写）

```
[PROBE] 已读取 .env，键数量=33
[PROBE] baseUrl=https://api.deepseek.com model=deepseek-chat apiKey=已加载(长度35)
[PROBE] OpenAiChatOptions.builder().build().getStreamUsage() = false
[PROBE] ===== 流式（默认 options） =====
[PROBE] chunk 总数=3，带 usage 的 chunk=3，usage.totalTokens>0 的 chunk=1
[PROBE]   chunk[0] text="" usage=prompt=0,completion=0,total=0
[PROBE]   chunk[1] text="收到" usage=prompt=0,completion=0,total=0
[PROBE]   chunk[2] text="" usage=prompt=10,completion=1,total=11
[PROBE] 最后一片文本=
[PROBE] usage: promptTokens=10, completionTokens=1, totalTokens=11
[PROBE] nativeUsage=Usage[completionTokens=1, promptTokens=10, totalTokens=11, promptTokensDetails=PromptTokensDetails[audioTokens=null, cachedTokens=0], completionTokenDetails=null]
[PROBE] ===== 流式（streamUsage=true） =====
[PROBE] chunk 总数=3，带 usage 的 chunk=3，usage.totalTokens>0 的 chunk=2
[PROBE]   chunk[0] text="" usage=prompt=0,completion=0,total=0
[PROBE]   chunk[1] text="收到" usage=prompt=10,completion=1,total=11
[PROBE]   chunk[2] text="" usage=prompt=10,completion=1,total=11
[PROBE] 最后一片文本=
[PROBE] usage: promptTokens=10, completionTokens=1, totalTokens=11
[PROBE] nativeUsage=Usage[completionTokens=1, promptTokens=10, totalTokens=11, promptTokensDetails=PromptTokensDetails[audioTokens=null, cachedTokens=0], completionTokenDetails=null]
[PROBE] ===== 非流式 =====
[PROBE] answer=收到
[PROBE] model=deepseek-flash
[PROBE] usage: promptTokens=10, completionTokens=1, totalTokens=11
[PROBE] nativeUsage=Usage[completionTokens=1, promptTokens=10, totalTokens=11, promptTokensDetails=PromptTokensDetails[audioTokens=null, cachedTokens=0], completionTokenDetails=null]
```

### 4.2 从输出里能得出的工程结论

1. **非流式**：`ChatResponse.getMetadata().getUsage()` 有真实值（本次 10 / 1 / 11），可以直接用。
2. **流式**：`ChatClient...stream().chatResponse()` 每一片都有 `Usage` 对象，但**前几片的 token 数全是 0，只有最后一片带整段的总用量**。
   - 所以正确做法是：**用最后一片的 usage 覆盖整段流的计量结果**；
   - 错误做法是逐片累加（累加结果是 0，成本会整块漏记）；
   - 错误做法也是"只记第一片"（第一片必然是 0）。
3. `OpenAiChatOptions.builder().build().getStreamUsage()` 默认是 **false**；但本次实测**即使不显式打开，DeepSeek 也会在末片返回 usage**。
   我们仍在 `application.yml` 里显式配置 `stream-usage: true`，理由是不依赖供应商的隐式行为——换供应商时显式配置更不容易踩坑。
4. **供应商返回的模型名可能与请求的不一致**：请求 `deepseek-chat`，响应 `metadata.model = "deepseek-flash"`。
   计量记录里 `model_id` 记**我们请求的模型**（用于和单价表对齐），供应商返回名只作为附加信息，避免单价表匹配不上。
5. 流式最后一片的 `text` 是空串，**不要**用"最后一片文本为空"去判断流是否正常结束。

### 4.3 本次探针没覆盖的部分（诚实标注）

- 未测流被客户端中途取消（cancel）时末片是否到达 —— 所以 L1 里流式计量仍保留 `ESTIMATED` 兜底分支；
- 未测工具调用（tool calls）场景下流式 usage 的行为；
- 未测上游超时 / 5xx 场景下的 usage 行为（这类请求本来就没有 usage）。

---

## 5. 容器编排验证（用户要求的另一件事）

用户原话是"在 WSL2 里跑一次 `cd docker && docker compose up -d` 确认三个容器 healthy"。实测有两处出入，记录如下：

1. **仓库里没有 `docker/` 目录**，`docker-compose.yml` 在仓库根目录，所以正确的命令是在**仓库根目录**执行 `docker compose up -d`；
2. Compose 里实际有 **4 个服务**（`app` / `mysql` / `qdrant` / `redis`），其中 `qdrant` **没有配置 healthcheck**，"三个容器"通常指中间件那三个。

执行的命令（WSL2 Debian 内）：

```bash
cd '/mnt/d/AI Agent (test)'
docker compose up -d mysql qdrant redis
docker compose ps
```

实测结果：

| 容器 | 状态 | 验证方式 |
|---|---|---|
| `aiagenttest-mysql-1` | **healthy** | Compose healthcheck（`mysqladmin ping`）+ 手工 `mysqladmin ping` 返回 `mysqld is alive` |
| `aiagenttest-redis-1` | **healthy** | Compose healthcheck（`redis-cli ping`）+ 手工 `PONG`，并验证从 `redis` 主机名可连通 |
| `aiagenttest-qdrant-1` | **running（无 healthcheck）** | 从 app 容器内 `curl http://qdrant:6333/readyz` 返回 `all shards are ready`；`/collections` 返回空集合列表 |
| `aiagenttest-app-1` | **healthy（额外发现）** | 该容器此前已在运行，`/actuator/health` 返回 `{"status":"UP"}` |

补充事实：

- WSL 环境：Debian，WSL 版本 2；`docker` 服务端 29.7.2；`docker compose` v5.5.0。
- `app` 容器当时跑的是**改造前**的镜像（`aiagenttest-app:latest`），`/api/chat/storage-status` 返回 `memoryBackend=redis`、`vectorStoreBackend=memory`。
- Qdrant 里目前**还没有任何 collection**（`kb_chunks` 尚未创建），说明知识库还没在这个 Compose 环境里灌过数据。

---

## 6. 一个必须当场定下来的口径问题：README 的测试数

现状：

- `README.md` 第 379 行、第 414 行写的是"133 个自动化测试"；
- 实测：`mvn -B verify` 报告 **134**，`rg -o "@Test\b" src/test` 静态数也是 **134**；
- 简历里写的是 133。

**决定（2026-09-16）**：**以实测为准，收尾阶段统一改 README**。
原因：本次升级还会新增测试，最终数字一定不是 133；与其现在改成 134、收尾再改一次，不如在收尾时（`mvn verify` 全绿之后）一次性把 README 两处数字、CHANGELOG、简历口径全部对齐到最终实测值。
风险提示：**简历与 README 必须用同一个数字**，面试官当面跑 `mvn test` 数报告时，两边不一致比数字偏小更致命。

---

## 7. Phase 0 未完成 / 待办

| 项 | 状态 | 说明 |
|---|---|---|
| 工作区干净 | ✅ | 切分支前 `git status --short` 无输出 |
| 新建分支 `feat/ai-gateway` | ✅ | 从 `6c80b43` 切出 |
| 基线 `mvn -B verify` | ✅ | 134 全绿，日志在 `target/verify-baseline.log` |
| README 测试数口径 | ✅ 已决定 | 收尾时统一改成最终实测值 |
| usage 探针（非流式 + 流式） | ✅ | 见第 4 节 |
| `eval/` 目录与评测集 | ⏳ 进行中 | `eval/eval_set.jsonl` ≥ 50 条，由并行子任务产出 |
| 评测集问题的期望文档回填 | ⏳ 待做 | 需要先建知识空间并上传文档，拿到真实 `documentId` 后回填 |
