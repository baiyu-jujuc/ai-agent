# 离线评测集说明

> 这份评测集用于回答一个具体问题：**这次升级到底有没有把东西变好？**
> 没有评测集，"变好了"就只是感觉；有了它，才是数字。

## 1. 文件与字段

`eval/eval_set.jsonl`：一行一个 JSON 对象，共 **77 条**：67 条基础用例 + 10 条难例（`hard-*`）。

| 字段 | 含义 | 备注 |
|---|---|---|
| `id` | 用例编号 | 前缀区分空间：`hb-` / `rel-` / `trav-` / `cs-` |
| `question` | 提问内容 | 全部不超过 200 字 |
| `space_id` | 逻辑空间名 | 跑批时会按这个名字**创建或复用**真实知识空间 |
| `doc_keys` | 期望命中的文档键 | 对应 `docs/demo-data/` 下的真实文件，见第 2 节 |
| `expected_doc_ids` | 真实文档 ID | 初始为空数组，跑批时按 `doc_keys` 解析成真实 `documentId` |
| `reference_answer` | 参考要点 | **必须能对上原文**，不允许写文档里没有的数字或结论 |
| `tags` | 标签 | 见第 4 节 |

## 2. 空间与文档对应关系

| 空间 | 灌入的文档 | 来源文件 |
|---|---|---|
| `eval-handbook` | 员工手册 | `docs/demo-data/employee-handbook.md` |
| `eval-release` | 生产发布与回滚手册（**1.0 版**） | `docs/demo-data/release-manual-v1/release-manual.md` |
| `eval-travel` | 退改规范（**V2.0 现行版**） | `docs/demo-data/travel-ota/refund-change-policy-v2/refund-change-policy.md` |
| `eval-travel` | 客服与研发协同升级规范 | `docs/demo-data/travel-ota/customer-service-escalation.md` |
| `eval-travel` | 在线旅游法规基线摘要 | `docs/demo-data/travel-ota/regulatory-baseline.md` |
| `eval-release-v2` | 生产发布手册（**同名文档两个版本**：先传 1.0 再传 2.0，当前生效 2.0） | `release-manual-v1/release-manual.md` + `release-manual-v2/release-manual.md` |

两个**故意**做的选择，避免答案自相矛盾：

1. `eval-release` 只灌 1.0 版手册（因此"变更冻结期"的正确答案是 **周五 16:00**，不是 v2 的 18:00）；
2. `eval-travel` 只灌退改规范 **V2**（因此"免服务费"的正确答案是 **72 小时**，不是 v1 的 48 小时）。

还没做的一件事：**版本回滚不在本评测集范围内**。要验证"回滚到 v1 后答案跟着变"，需要把两个 `release-manual.md`
用同一文件名上传成同一文档的两个版本，再用 `/api/kb/documents/{id}/rollback/{versionNo}` 切换——这属于
知识库功能验证，不在这套指标里，别把它和评测混为一谈。

> 不过 `eval-release-v2` 这个空间**已经按"同名文档 + 两版本"的方式播种**（先传 1.0，再传 2.0，后者成为当前有效版本）。
> 它验证的是另一件事：**同名文档存在多版本时，检索不会串到历史版本**——
> 如果旧版本 chunk 没清干净，同一批用例的答案就会在 16:00 和 18:00 之间摇摆。

## 3. 指标定义（跑批实际计算的就是这些）

| 指标 | 定义 | 口径细节 |
|---|---|---|
| **Hit@5** | 可答题（有期望文档）里，召回的前 5 个 chunk 是否包含期望文档 | **拒答题不计入分母**：它们没有期望文档，而检索总会返回 top-5，混进来只会把指标算歪 |
| **引用准确率** | 可答题里，返回的引用是否包含期望文档 | 与 Hit@5 分开统计：检索对了但引用没带上，一样算不准 |
| **拒答正确率** | 拒答题里，回答是否明确拒答（或没有返回引用） | 9 条 `no-answer` 用例，专门测"文档里没有就不要编" |
| **答案相关性** | 固定评分 Prompt 让模型打 0–3 分 | **同一条评 2 次取平均**，因为单次打分噪声很大 |
| **P95 延迟** | 端到端耗时的 95 分位 | 排序后取第 `ceil(0.95 × n)` 个 |
| **平均单次成本** | 本次跑批的总成本 ÷ 用例数 | 单位**微元**；数据来自 `llm_usage_record` 表（改造前没有这张表，所以基线这一列天然缺失） |
| **降级次数** | `outcome=DEGRADED` 的记录数 | 用来观察"上游不稳时有没有悄悄降级" |

评分 Prompt 与业务 Prompt 分开管理（scene = `EVAL_JUDGE`），这样"评测打分"的调用也会被计量，
不会出现"评测跑了一小时，成本看不见"的情况。

## 4. 标签说明

| 标签 | 数量 | 用途 |
|---|---|---|
| `fact` / `policy` / `procedure` / `numeric` / `table` / `multi-hop` | 见文件 | 题型分布，避免全是简单事实题 |
| `no-answer` | 10 | 文档里没有答案（或问的是历史版本），**必须拒答/说明查不到**；用于验证"拒答不缓存"和幻觉 |
| `hard` | 10 | 难例总标记：跨文档多跳、同名文档多版本、边界拒答、带错误前提的陷阱题 |
| `same-name-version` | 5 | 同名文档存在多个版本（`eval-release-v2` 空间） |
| `trap` | 2 | 问题里带错误前提（如"不可抗力必须全额退款，对吗？"），答"对"就是错的 |
| `paraphrase-of:<id>` | 4 组 | 语义相同、措辞不同 → 语义缓存**应该**命中 |
| `near-miss-of:<id>` | 3 组 | 措辞相近但答案不同 → 语义缓存**不应该**命中（阈值校准用） |
| `cross-space-pair` | 1 组 | 同一个问题出现在两个空间，答案不同 → 验证缓存不跨空间串答案 |

## 5. 怎么跑

### 5.1 本地一键跑批（推荐）

```powershell
cd "D:\AI Agent (test)"
.\scripts\run-eval-local.ps1 -Tag post-upgrade -Limit 10   # 先小样本验证链路
.\scripts\run-eval-local.ps1 -Tag post-upgrade            # 全量（77 条，会真实消耗 token）
```

脚本会自己从 `.env` 读取密钥（**不打印、不写进命令行**），跑完在 `eval/reports/` 生成
`eval-report-<tag>.json` 与 `eval-report-<tag>.md`。

### 5.2 服务已在运行时，用管理接口触发

```powershell
# 需要同时带 X-API-Key 与 JWT（/api/admin/** 要求登录）
curl.exe -X POST "http://localhost:8080/api/admin/eval/run?tag=post-upgrade&limit=10" `
  -H "X-API-Key: <你的 API Key>" -H "Authorization: Bearer <token>"
curl.exe "http://localhost:8080/api/admin/eval/status" -H "X-API-Key: <key>" -H "Authorization: Bearer <token>"
```

### 5.3 用同一个评测集跑基线

```powershell
git stash push --include-untracked        # 或把改造前的 commit 单独 checkout 成 worktree
.\scripts\run-eval-local.ps1 -Tag baseline
git stash pop
```

**基线必须在改造前采集**。如果漏了，事后补跑时一定要保证三件事相同：**同一份评测集、同一个模型、
同一台机器**，否则两列数字不可比。

## 6. 新增用例的规矩

1. 先确认答案能在 `docs/demo-data/` 的某个文件里找到依据，再把 `reference_answer` 写上去；
2. `doc_keys` 必须登记在 `EvalRunner.DOC_FILES` 里，否则跑批会直接报错（**宁可报错，不要猜文档**）；
3. 拒答题的 `doc_keys` 留空数组；
4. 改完用 `ConvertFrom-Json` 逐行校验，别让一行坏 JSON 毁掉整次跑批。

## 7. 已知边界（诚实标注）

- 评测集只有 77 条、4 个知识空间，**规模小**，结论只能说明"这次改动没有明显退化"，不能当通用结论；
- 打分用的是同一个模型（DeepSeek），**存在自我偏好**，所以答案相关性只适合做同版本横向对比；
- 覆盖的是中文问答，没有多语言用例；
- 基线（改造前）报告需要在改造前采集，见 5.3。
