# 2 分钟演示分镜脚本 · AI 网关升级（v0.2.0）

> 目标：用 2 分钟让人看到 **计量 → 熔断降级 → 评测闭环 → 可观测** 四件事都有真实证据，
> 而不是"我加了一个网关"这种空话。
> 约定：口播按每秒 4 个汉字写，全程约 470 字；每段一次录完再剪，不要现场敲命令。

---

## 一、录制前准备（5 分钟，录之前务必先跑一遍）

**1）确认 `.env` 里两件事**

```bash
DEEPSEEK_API_KEY=sk-...       # 已在用，确认没过期
ADMIN_USERNAMES=admin         # 关键：让 demo-prepare 建的 admin 账号有 ADMIN 角色，
                              # 否则 /api/admin/** 全返回 403
```

**2）启动应用（在 Windows 终端里启动，不要用 docker compose）**

```powershell
cd "D:\AI Agent (test)"
.\scripts\run-eval-local.ps1 -Tag demo -Limit 1   # 只跑 1 条，确认链路通（约 1 分钟，可跳过）
mvn -B spring-boot:run                            # 浏览器要访问 http://localhost:8080
```

> ⚠️ 别用 `docker compose up` 做这次演示：本机 **Windows 访问不到 WSL 里 docker 映射的端口**（实测 localhost 与 WSL IP 都不通），
> 浏览器会打不开 8080。容器化运行的正确验证方式见 `docs/ai-gateway-verification.md` 4.4。

**3）另开一个终端，准备知识空间和一个 token**

```powershell
cd "D:\AI Agent (test)"
.\scripts\demo-prepare.ps1         # 幂等：建账号 admin/reader、建知识空间、上传演示文档，最后打印 spaceId

$Base  = "http://localhost:8080"
$ApiKey = "dev-key-change-in-production"        # 与你 .env 的 AGENT_API_KEY 保持一致
$Token = (Invoke-RestMethod -Method Post "$Base/api/auth/login" -ContentType "application/json" `
          -Body '{"username":"admin","password":"123456"}').token
$Auth  = @{ "X-API-Key" = $ApiKey; "Authorization" = "Bearer $Token" }
"spaceId = " + (Invoke-RestMethod "$Base/api/kb/spaces" -Headers $Auth | Select-Object -First 1).id
```

**4）把下面 4 条命令先粘到一个记事本/脚本里**（录制时直接复制，避免现场敲错）

```powershell
# A 计量
$today = (Get-Date).ToString("yyyy-MM-dd")
(Invoke-RestMethod "$Base/api/admin/usage?from=$today&to=$today" -Headers $Auth).items |
  Select-Object -First 5 scene,modelId,routeType,promptTokens,completionTokens,usageSource,costMicros,latencyMs,outcome |
  Format-Table -AutoSize

# B 熔断+降级（自动起一个上游不可用的实例，跑完自动停）
.\scripts\verify-gateway.ps1 -Requests 6 -Port 8090

# C 评测报告（VS Code 用 code + Ctrl+Shift+V 预览；没装就 start 用默认程序打开）
code "eval\reports\eval-report-post-upgrade-v2.md"
# 或者：start "" "eval\reports\eval-report-post-upgrade-v2.md"

# D 指标端点（顺带证明不带 token 读不到）
curl.exe -s "http://localhost:8090/actuator/prometheus" -o NUL -w "without token: HTTP %{http_code}`n"
curl.exe -s "http://localhost:8090/actuator/prometheus" -H "Authorization: Bearer $Token" | findstr /R "^llm_"
```

**5）画面设置**：终端字号 14–16 号、关掉无关窗口与通知、浏览器缩放 110%（引用编号才看得清）。

---

## 二、分镜表（总长 2:00）

### 镜头 1　开场（0:00–0:12）

- **画面**：浏览器停在 `http://localhost:8080` 的知识库问答页，光标停在输入框
- **操作**：无（先不提问）
- **口播**：这次给企业知识库平台做了一次 AI 工程化升级：把散落的模型调用收口到一个网关，然后在这层上加计量、稳定性、缓存和评测。我用四个能复现的证据讲，不吹效果。
- **要点**：一句话说清"在哪儿加、加了什么"，不解释技术细节

### 镜头 2　计量：问一次，落一条账（0:12–0:42）

- **画面/操作**：
1. 在输入框问：`生产数据库的全量备份在几点执行、保留多少天？` → 回车，等回答出来（带 `[1]` 引用）
2. 切到终端，粘 **命令 A** → 出现一条记录
- **画面对照**：记录里的 `scene=KB_QA`、`modelId=deepseek-chat`、`usageSource=PROVIDER`、`promptTokens/completionTokens`、`costMicros`、`latencyMs`
- **口播**：每次模型调用都会落一条用量记录：模型、prompt 和 completion token、延迟、场景，还有当时的单价快照和整数微元成本。token 用的是供应商返回的真实值——流式请求我也实测过，usage 只出现在最后一片 chunk 上，所以要取末片覆盖写，逐片累加会得到 0。成本用整数微元存，避免浮点累加对不上账。计量是独立事务，写失败只记日志，不影响用户拿答案。
- **剪切提示**：提问后等待的那 2–3 秒可加速 2 倍

### 镜头 3　熔断 + 降级：一条命令看到证据（0:42–1:05）

- **画面/操作**：粘 **命令 B**（`.\scripts\verify-gateway.ps1 -Requests 6 -Port 8090`），等它跑完（约 30 秒），停在输出上
- **必须出现的行**：
  - `call 1..6: xxx ms -> 当前模型服务繁忙，请稍后重试。`（都是降级话术，不是 500、不是堆栈）
  - `without token -> blocked (403) as expected`
  - `resilience4j_circuitbreaker_failure_rate{name="deepseek-chat"} 100.0`
  - `resilience4j_circuitbreaker_not_permitted_calls_total{...} 7.0`（**示例值，以实际输出为准**：这个数随 `-Requests` 次数变化）
  - `llm_call_total{model="none",outcome="DEGRADED",scene="CHAT_SIMPLE"} 6.0`（**示例值，以实际输出为准**：跑几次就是几）
- **口播**：稳定性这块我是按模型维度做的：限流、熔断、重试，还有一层比 HTTP 更短的网关超时。验证方式是把上游指向一个不存在的端口——这是模拟故障，不是线上演练。可以看到熔断打开后失败率 100%，后面的请求直接被短路不再打上游，六次调用全部在几百毫秒内返回可读的降级话术。这段脚本还会顺手证明：不带 token 读不到指标端点，因为上次我发现管理接口和 Actuator 存在越权，一起修了。
- **剪切提示**：脚本跑的时候画面停住不动即可，剪掉中间的等待

### 镜头 4　评测闭环：77 条评测集的报告（1:05–1:30）

- **画面/操作**：粘 **命令 C**，打开报告；先停在"指标总览"表格，再往下滚两屏到"逐条结果"
- **必须出现的数字**：`可答题数 / 拒答题数 67 / 10`、`Hit@5 100.0%`、`引用准确率 100.0%`、`拒答正确率 100.0%`、`答案相关性 2.955`、`P95 4598ms`、`平均单次成本 10886 微元`
- **口播**：为了让改动可比较，我建了 77 条评测集，里面有跨文档多跳、同名文档多版本、陷阱题和 10 条"文档里没有、必须拒答"的题。跑批会算出检索命中率、引用准确率、拒答正确率、答案相关性、P95 延迟和平均成本。这里有个坑我特意处理了：拒答题没有期望文档，不能塞进命中率的分母，否则指标会被算歪——所以它们单独统计。
- **剪切提示**：滚屏速度放慢，让每个数字停留 1 秒以上

### 镜头 5　可观测 + 收尾（1:30–2:00）

- **画面/操作**：粘 **命令 D** → 先看到 `without token: HTTP 403`，再看到 `llm_*` 指标列表；最后切到 GitHub 的 PR #11 与 v0.2.0 Release 页面
- **必须出现的行**：`llm_call_total{...}`、`llm_token_total{...}`、`llm_cost_micros_total{...}`、`llm_call_duration_seconds_count{...}`
- **口播**：指标接的是 Prometheus，自定义指标有调用量、token、成本、降级次数和延迟；标签只放模型、场景这些低基数字段，不敢放用户 ID——那样时间序列会爆掉。这次升级以 PR #11 合并进 main，打了 v0.2.0 的 tag 和 Release，两次 CI 都是绿的。还没做的我也写在文档里：改造前的基线评测没采、缓存阈值没有真实 embedding 校准，这两句我不会含糊过去。

---

## 三、连读口播稿（约 470 字，可直接录音）

> 这次给企业知识库平台做了一次 AI 工程化升级：把散落在六七个类里的模型调用收口到一个网关，然后在这层上加计量、稳定性、缓存和评测。我用四个能复现的证据讲。
>
> 第一，计量。每次模型调用都会落一条用量记录：模型、prompt 和 completion token、延迟、场景，还有当时的单价快照和整数微元成本。token 用供应商返回的真实值——流式请求我实测过，usage 只出现在最后一片 chunk 上，所以要取末片覆盖写，逐片累加会得到零。计量是独立事务，写失败只打日志，不影响用户拿到答案。
>
> 第二，稳定性。我按模型维度做了限流、熔断、重试，还有一层比 HTTP 更短的网关超时。验证方式是把上游指向一个不存在的端口：熔断打开后失败率百分之百，后面的请求直接被短路，六次调用全部在几百毫秒内返回可读的降级话术，而不是五百错误。这是模拟故障，不是线上演练。顺便说一句，我发现管理接口和 Actuator 存在越权，这次一起修了：不带 token 读指标会返回四百零三。
>
> 第三，评测闭环。我建了七十七条评测集，包含跨文档多跳、同名文档多版本、陷阱题，还有十条必须拒答的题。跑批算出检索命中率、引用准确率、拒答正确率、答案相关性、P95 延迟和平均成本。这里有个坑我特意处理了：拒答题没有期望文档，不能塞进命中率的分母，要单独统计。
>
> 第四，可观测。指标接进 Prometheus，标签只用模型、场景这些低基数字段，不敢放用户 ID。这次升级以 PR 11 合并进 main，打了 v0.2.0，两次 CI 都是绿的。还没做的我也写在文档里：改造前的基线评测没采、缓存阈值没有真实 embedding 校准，这两句我不会含糊过去。

---

## 四、面试安全线（说错会翻车的三处）

1. **熔断那段必须说"模拟"**：是"把上游指向不存在的端口验证熔断与降级"，不是"线上故障演练"。
2. **缓存不要说"实测命中率高"**：真实 Qdrant 上验证的是命中、空间隔离、TTL、清空；`0.92` 这个阈值**没有**用真实 embedding 校准过，被问到要直说。
3. **评测不要说"建立了评估体系"**：就说"77 条用例、算了这六个指标、基线还没采"。数字都在 `eval/reports/eval-report-post-upgrade-v2.md` 里，随口编会被追着问。

---

## 五、如果只录 60 秒（备选剪辑）

保留镜头 2 与镜头 3，各 25 秒，最后 10 秒用镜头 5 的 PR/Release 画面收尾；
镜头 4 用一句口播带过（"评测报告和已知局限都在仓库 docs 里"），把细节留给面试时展开。
