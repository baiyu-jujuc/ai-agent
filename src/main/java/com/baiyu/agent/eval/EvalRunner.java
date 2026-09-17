package com.baiyu.agent.eval;

import com.baiyu.agent.gateway.CallScene;
import com.baiyu.agent.gateway.GatewayRequest;
import com.baiyu.agent.gateway.GatewayResponse;
import com.baiyu.agent.gateway.ModelGateway;
import com.baiyu.agent.gateway.entity.LlmUsageRecord;
import com.baiyu.agent.gateway.repository.LlmUsageRecordRepository;
import com.baiyu.agent.kb.KbQaService;
import com.baiyu.agent.kb.KnowledgeBaseService;
import com.baiyu.agent.kb.entity.Chunk;
import com.baiyu.agent.kb.entity.Citation;
import com.baiyu.agent.kb.entity.Document;
import com.baiyu.agent.kb.entity.KnowledgeSpace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 离线评测跑批。
 *
 * <p>指标定义（对应执行文档第 2 节）：
 * <ul>
 *   <li><b>Hit@5</b>：召回的前 5 个 chunk 里是否包含期望文档；</li>
 *   <li><b>引用准确率</b>：返回的引用里是否包含期望文档；</li>
 *   <li><b>答案相关性</b>：固定评分 Prompt 让模型打 0–3 分，同一条评 2 次取平均；</li>
 *   <li><b>P95 延迟</b>与<b>平均单次成本</b>（成本来自 L1 的用量记录，单位微元）。</li>
 * </ul>
 *
 * <p>跑批会真实消耗 token，所以支持 {@code limit} 限制条数，逐条可单独重跑。
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final Path REPORT_DIR = Path.of("eval", "reports");
    private static final int JUDGE_ROUNDS = 2;

    /** doc_key → 演示数据文件。没有登记的 key 会被明确报错，绝不"猜一个相近的文档"。 */
    private static final Map<String, String> DOC_FILES = Map.of(
            "employee-handbook", "docs/demo-data/employee-handbook.md",
            "release-manual-v1/release-manual", "docs/demo-data/release-manual-v1/release-manual.md",
            "release-manual-v2/release-manual", "docs/demo-data/release-manual-v2/release-manual.md",
            "travel-ota/refund-change-policy-v2/refund-change-policy",
            "docs/demo-data/travel-ota/refund-change-policy-v2/refund-change-policy.md",
            "travel-ota/customer-service-escalation", "docs/demo-data/travel-ota/customer-service-escalation.md",
            "travel-ota/regulatory-baseline", "docs/demo-data/travel-ota/regulatory-baseline.md");

    /**
     * 需要"同名文档多版本"的空间：按顺序上传，后上传的成为当前有效版本（旧版本向量会被删除）。
     *
     * <p>存在的意义：验证"同名文档 + 版本"场景下检索不会串到历史版本。
     * 如果实现有 bug（比如旧版本 chunk 没清干净），同一批用例的答案就会在 16:00/18:00 之间摇摆。
     */
    private static final Map<String, List<String>> SPACE_SEED_FILES = Map.of(
            "eval-release-v2", List.of(
                    "docs/demo-data/release-manual-v1/release-manual.md",
                    "docs/demo-data/release-manual-v2/release-manual.md"));

    private final KnowledgeBaseService kbService;
    private final KbQaService kbQaService;
    private final ModelGateway modelGateway;
    private final LlmUsageRecordRepository usageRepository;
    private final EvalSetLoader loader;
    private final ObjectMapper objectMapper;

    public EvalRunner(KnowledgeBaseService kbService,
                      KbQaService kbQaService,
                      ModelGateway modelGateway,
                      LlmUsageRecordRepository usageRepository,
                      EvalSetLoader loader,
                      ObjectMapper objectMapper) {
        this.kbService = kbService;
        this.kbQaService = kbQaService;
        this.modelGateway = modelGateway;
        this.usageRepository = usageRepository;
        this.loader = loader;
        this.objectMapper = objectMapper;
    }

    /**
     * 跑一次评测。
     *
     * @param tag   报告标签（例如 post-upgrade / baseline）
     * @param limit 只跑前 N 条（0 表示全跑），用于先小样本验证链路
     * @param seed  是否先建空间/灌文档（幂等）
     */
    public EvalReport run(String tag, int limit, boolean seed) throws IOException {
        String startedAt = Instant.now().toString();
        List<EvalCase> cases = loader.load(EvalSetLoader.DEFAULT_PATH);
        if (limit > 0 && limit < cases.size()) {
            cases = cases.subList(0, limit);
        }

        Map<String, String> spaceIds = seed ? seedSpacesAndDocuments(cases) : loadExistingSpaces(cases);
        Map<String, List<String>> expectedDocIds = resolveExpectedDocIds(cases, spaceIds);

        Instant windowStart = Instant.now();
        List<EvalReport.CaseResult> results = new ArrayList<>();
        int index = 0;
        for (EvalCase evalCase : cases) {
            index++;
            String spaceId = spaceIds.get(evalCase.spaceId());
            if (spaceId == null) {
                log.warn("[{}/{}] 跳过 {}：空间 {} 不存在", index, cases.size(), evalCase.id(), evalCase.spaceId());
                continue;
            }
            EvalReport.CaseResult result = runSingle(evalCase, spaceId,
                    expectedDocIds.getOrDefault(evalCase.id(), List.of()));
            results.add(result);
            log.info("[{}/{}] {}：hit@5={}, 引用命中={}, 相关性={}, 耗时={}ms",
                    index, cases.size(), evalCase.id(), result.hitAt5(),
                    result.citationHit(), result.relevance(), result.latencyMs());
        }
        Instant windowEnd = Instant.now();

        List<LlmUsageRecord> usage = usageRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(windowStart, windowEnd);
        long calls = usage.size();
        long degraded = usage.stream().filter(r -> "DEGRADED".equals(r.getOutcome())).count();
        long totalCost = usage.stream().mapToLong(LlmUsageRecord::getCostMicros).sum();
        long avgCost = results.isEmpty() ? 0L : totalCost / results.size();

        // 拒答题单独统计：它们没有期望文档，混进 Hit@5 分母只会把指标算歪
        List<EvalReport.CaseResult> answerable = results.stream()
                .filter(result -> !result.refusalCase()).toList();
        List<EvalReport.CaseResult> refusals = results.stream()
                .filter(EvalReport.CaseResult::refusalCase).toList();
        long hitCount = answerable.stream().filter(EvalReport.CaseResult::hitAt5).count();
        long citationCount = answerable.stream().filter(EvalReport.CaseResult::citationHit).count();
        long refusalCorrect = refusals.stream().filter(EvalReport.CaseResult::refusalCorrect).count();
        double avgRelevance = results.stream().mapToDouble(EvalReport.CaseResult::relevance).average().orElse(0d);
        long p95 = EvalReport.p95(results.stream().map(EvalReport.CaseResult::latencyMs).toList());

        EvalReport report = new EvalReport(tag, startedAt, Instant.now().toString(), results.size(),
                answerable.size(), refusals.size(),
                ratio(hitCount, answerable.size()), ratio(citationCount, answerable.size()),
                ratio(refusalCorrect, refusals.size()),
                round(avgRelevance), p95, totalCost, avgCost, calls, degraded, results);

        writeReports(report);
        log.info("评测完成：tag={}, 用例={}, Hit@5={}, 引用准确率={}, 相关性={}, P95={}ms, 平均成本={}微元, 调用={}",
                tag, report.caseCount(), report.hitRateAt5(), report.citationAccuracy(),
                report.avgRelevance(), report.p95LatencyMs(), report.avgCostMicros(), report.kbQaCalls());
        return report;
    }

    private EvalReport.CaseResult runSingle(EvalCase evalCase, String spaceId, List<String> expectedIds) {
        boolean refusalCase = evalCase.expectsRefusal();

        // 1) 检索指标：只看前 5 个 chunk 命中了哪些文档
        List<Chunk> chunks = kbService.searchChunks(spaceId, evalCase.question(), 5);
        List<String> retrievedDocIds = chunks.stream().map(Chunk::getDocumentId).distinct().toList();
        boolean hitAt5 = !refusalCase && !expectedIds.isEmpty()
                && retrievedDocIds.stream().anyMatch(expectedIds::contains);

        // 2) 端到端问答：走的是主链路 /api/kb/**（legacy RAG 默认关闭，不参与评测）
        long start = System.currentTimeMillis();
        KbQaService.QaResult answer = kbQaService.ask(
                spaceId, evalCase.question(), "eval-" + evalCase.id(), "eval-runner");
        long latency = System.currentTimeMillis() - start;

        List<String> citationDocIds = answer.citations() == null ? List.of()
                : answer.citations().stream().map(Citation::getDocumentId).distinct().toList();
        boolean citationHit = !refusalCase && !expectedIds.isEmpty()
                && citationDocIds.stream().anyMatch(expectedIds::contains);
        boolean refusalCorrect = refusalCase && isRefusalAnswer(answer.answer());

        double relevance = judge(evalCase, answer.answer());

        return new EvalReport.CaseResult(evalCase.id(), evalCase.spaceId(), evalCase.question(),
                refusalCase, refusalCorrect,
                hitAt5, citationHit, relevance, latency, preview(answer.answer()),
                retrievedDocIds, expectedIds);
    }

    /**
     * 拒答判定：命中拒答话术，或者压根没有返回引用。
     *
     * <p>注意这两种情况要一起看：知识库里没有相关内容时，
     * {@code KbQaService} 会走"未找到内容"分支，直接返回提示话术、不产生引用。
     *
     * <p>另外还有一类"边界拒答"：问的是历史版本的规定，而知识库只提供当前有效版本。
     * 这种情况要求回答明确说明"查不到历史版本"，而不是拿当前版本的数字冒充历史版本。
     */
    boolean isRefusalAnswer(String answerText) {
        if (answerText == null || answerText.isBlank()) {
            return false;
        }
        boolean explicitRefusal = answerText.contains("未找到")
                || answerText.contains("没有找到")
                || answerText.contains("没有相关")
                || answerText.contains("无法提供")
                || answerText.contains("无法确认")
                || answerText.contains("无法回答")
                || answerText.contains("请补充")
                || answerText.contains("上传相关文档");
        if (explicitRefusal) {
            return true;
        }
        boolean versionBoundary = answerText.contains("历史版本")
                || answerText.contains("旧版本")
                || answerText.contains("1.0 版")
                || answerText.contains("当前有效版本");
        boolean deniesAvailability = answerText.contains("无法")
                || answerText.contains("不能")
                || answerText.contains("不可")
                || answerText.contains("没有")
                || answerText.contains("只能");
        return versionBoundary && deniesAvailability;
    }

    /**
     * 模型打分 0–3 分，同一条评 2 次取平均。
     * 为什么要 2 次：大模型打分有随机性，评一次的话指标波动可能全来自打分噪声，
     * 那样这个指标就没有比较价值了。
     */
    double judge(EvalCase evalCase, String answerText) {
        if (answerText == null || answerText.isBlank()) {
            return 0d;
        }
        int total = 0;
        int valid = 0;
        for (int round = 0; round < JUDGE_ROUNDS; round++) {
            try {
                String prompt = """
                        你是严格但公正的评测员。请判断"待评回答"相对于"参考要点"的相关性与准确性，打 0-3 分。
                        评分标准：3=完全正确且覆盖要点；2=基本正确但缺少细节；1=部分相关或有明显偏差；0=答非所问或拒答。
                        只输出一个数字，不要解释。

                        问题：%s
                        参考要点：%s
                        待评回答：%s
                        """.formatted(evalCase.question(), evalCase.referenceAnswer(), truncate(answerText, 1200));
                GatewayResponse response = modelGateway.call(GatewayRequest.builder(CallScene.EVAL_JUDGE)
                        .userPrompt(prompt)
                        .temperature(0.0)
                        .build());
                Integer score = parseScore(response.content());
                if (score != null) {
                    total += score;
                    valid++;
                }
            } catch (Exception e) {
                log.warn("打分失败（忽略这一次）：{}", e.toString());
            }
        }
        return valid == 0 ? 0d : round((double) total / valid);
    }

    static Integer parseScore(String content) {
        if (content == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[0-3]").matcher(content);
        return matcher.find() ? Integer.parseInt(matcher.group()) : null;
    }

    /** 建空间 + 灌演示文档（幂等：已存在同名文档就复用，避免刷出一堆版本）。 */
    public Map<String, String> seedSpacesAndDocuments(List<EvalCase> cases) throws IOException {
        Map<String, String> spaceIds = new LinkedHashMap<>();
        for (EvalCase evalCase : cases) {
            if (spaceIds.containsKey(evalCase.spaceId())) {
                continue;
            }
            KnowledgeSpace space = kbService.listSpaces().stream()
                    .filter(existing -> evalCase.spaceId().equals(existing.getName()))
                    .findFirst()
                    .orElseGet(() -> kbService.createSpace(evalCase.spaceId(),
                            "离线评测空间（由 EvalRunner 自动创建）", "private", null));
            spaceIds.put(evalCase.spaceId(), space.getId());
            log.info("评测空间就绪：{} → {}", evalCase.spaceId(), space.getId());
        }

        for (Map.Entry<String, String> entry : spaceIds.entrySet()) {
            for (String filePath : filesFor(entry.getKey(), cases)) {
                ensureDocument(entry.getValue(), filePath);
            }
        }
        return spaceIds;
    }

    /** 这个空间要灌哪些文件：优先用 SPACE_SEED_FILES 的多版本计划，否则取用例里出现的 doc_key。 */
    private List<String> filesFor(String spaceKey, List<EvalCase> cases) throws IOException {
        List<String> planned = SPACE_SEED_FILES.get(spaceKey);
        if (planned != null) {
            return planned;
        }
        List<String> files = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            if (!spaceKey.equals(evalCase.spaceId())) {
                continue;
            }
            for (String docKey : evalCase.docKeys()) {
                String filePath = DOC_FILES.get(docKey);
                if (filePath == null) {
                    throw new IOException("评测集引用了未知的 doc_key: " + docKey
                            + "（请在 EvalRunner.DOC_FILES 里登记对应文件）");
                }
                if (!files.contains(filePath)) {
                    files.add(filePath);
                }
            }
        }
        return files;
    }

    /** 见 EvalRunnerSeedingTest：同名文档的多版本必须按内容哈希判断，不能只看文件名。 */
    void ensureDocument(String spaceId, String filePath) throws IOException {
        String filename = Path.of(filePath).getFileName().toString();
        byte[] bytes = Files.readAllBytes(Path.of(filePath));
        String contentHash = sha256Hex(bytes);

        // 关键：判断"要不要灌"不能只看文件名是否存在——
        // 同一份文档的多个版本用的是同一个文件名，只看文件名会让第二个版本永远灌不进去
        // （这正是难例 hard-004~008 第一次跑出 1.0 版答案的原因）。
        // 正确做法是按内容哈希判断：库里已有同样内容的版本就跳过，否则灌成新版本。
        Optional<Document> existing = kbService.listDocuments(spaceId).stream()
                .filter(doc -> filename.equals(doc.getFilename()))
                .findFirst();
        if (existing.isPresent()) {
            boolean sameContentAlreadyStored = kbService.getDocumentVersions(existing.get().getId()).stream()
                    .anyMatch(version -> contentHash.equalsIgnoreCase(version.getContentHash()));
            if (sameContentAlreadyStored) {
                return;
            }
            log.info("评测文档 {} 内容有新版本，按新版本灌入（space={}）", filename, spaceId);
        }

        Document uploaded = kbService.uploadDocument(spaceId, new ByteArrayMultipartFile(filename, bytes));
        log.info("已灌入评测文档：{} → documentId={}, hash={}", filename, uploaded.getId(),
                contentHash.substring(0, 12));
    }

    static String sha256Hex(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 把用例里的 doc_key 解析成真实 documentId（Hit@5 与引用准确率都靠它比对）。 */
    Map<String, List<String>> resolveExpectedDocIds(List<EvalCase> cases, Map<String, String> spaceIds) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (EvalCase evalCase : cases) {
            String spaceId = spaceIds.get(evalCase.spaceId());
            if (spaceId == null) {
                result.put(evalCase.id(), List.of());
                continue;
            }
            List<Document> documents = kbService.listDocuments(spaceId);
            List<String> ids = new ArrayList<>(evalCase.expectedDocIds());
            for (String docKey : evalCase.docKeys()) {
                String filePath = DOC_FILES.get(docKey);
                if (filePath == null) {
                    continue;
                }
                String filename = Path.of(filePath).getFileName().toString();
                documents.stream()
                        .filter(doc -> filename.equals(doc.getFilename()))
                        .findFirst()
                        .ifPresent(doc -> ids.add(doc.getId()));
            }
            result.put(evalCase.id(), ids.stream().distinct().toList());
        }
        return result;
    }

    private Map<String, String> loadExistingSpaces(List<EvalCase> cases) {
        Map<String, String> spaceIds = new LinkedHashMap<>();
        for (EvalCase evalCase : cases) {
            kbService.listSpaces().stream()
                    .filter(existing -> evalCase.spaceId().equals(existing.getName()))
                    .findFirst()
                    .ifPresent(space -> spaceIds.put(evalCase.spaceId(), space.getId()));
        }
        return spaceIds;
    }

    // ------------------------------------------------------------------ 报告输出

    void writeReports(EvalReport report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Path json = REPORT_DIR.resolve("eval-report-" + report.tag() + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);

        Path markdown = REPORT_DIR.resolve("eval-report-" + report.tag() + ".md");
        Files.writeString(markdown, renderMarkdown(report), StandardCharsets.UTF_8);
        log.info("评测报告已写入：{} 与 {}", json.toAbsolutePath(), markdown.toAbsolutePath());
    }

    String renderMarkdown(EvalReport report) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 离线评测报告：").append(report.tag()).append("\n\n");
        sb.append("- 开始时间：").append(report.startedAt()).append("\n");
        sb.append("- 结束时间：").append(report.finishedAt()).append("\n");
        sb.append("- 用例数：").append(report.caseCount()).append("\n\n");

        EvalReport baseline = readBaseline();
        sb.append("## 指标总览\n\n");
        sb.append("| 指标 | 本次（").append(report.tag()).append("） | 基线（baseline） |\n");
        sb.append("|---|---|---|\n");
        sb.append(metricRow("可答题数 / 拒答题数",
                report.answerableCases() + " / " + report.refusalCases(),
                baseline == null ? "未采集" : baseline.answerableCases() + " / " + baseline.refusalCases()));
        sb.append(metricRow("Hit@5（仅可答题）", pct(report.hitRateAt5()),
                baseline == null ? "未采集" : pct(baseline.hitRateAt5())));
        sb.append(metricRow("引用准确率（仅可答题）", pct(report.citationAccuracy()),
                baseline == null ? "未采集" : pct(baseline.citationAccuracy())));
        sb.append(metricRow("拒答正确率（文档里没有答案时）", pct(report.refusalAccuracy()),
                baseline == null ? "未采集" : pct(baseline.refusalAccuracy())));
        sb.append(metricRow("答案相关性（0-3，评 2 次取均值）", String.valueOf(report.avgRelevance()),
                baseline == null ? "未采集" : String.valueOf(baseline.avgRelevance())));
        sb.append(metricRow("P95 延迟（ms）", String.valueOf(report.p95LatencyMs()),
                baseline == null ? "未采集" : String.valueOf(baseline.p95LatencyMs())));
        sb.append(metricRow("平均单次成本（微元）", String.valueOf(report.avgCostMicros()),
                baseline == null ? "改造前无计量能力" : String.valueOf(baseline.avgCostMicros())));
        sb.append(metricRow("模型调用次数（含打分）", String.valueOf(report.kbQaCalls()),
                baseline == null ? "未采集" : String.valueOf(baseline.kbQaCalls())));
        sb.append(metricRow("降级次数", String.valueOf(report.degradedCalls()),
                baseline == null ? "未采集" : String.valueOf(baseline.degradedCalls())));
        sb.append('\n');

        if (baseline == null) {
            sb.append("> 基线说明：`eval/reports/eval-report-baseline.json` 不存在。\n");
            sb.append("> 按执行文档要求，基线应在动手改造前用同一份评测集跑出来；\n");
            sb.append("> 若要补跑：切到改造前的 commit，用同一份评测集与同一模型运行本跑批，tag 用 baseline。\n\n");
        }

        sb.append("## 逐条结果\n\n");
        sb.append("| 用例 | 空间 | 类型 | Hit@5 | 引用命中 | 相关性 | 延迟(ms) |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (EvalReport.CaseResult result : report.cases()) {
            sb.append("| ").append(result.id())
                    .append(" | ").append(result.spaceId())
                    .append(" | ").append(result.refusalCase() ? "拒答" : "问答")
                    .append(" | ").append(result.refusalCase() ? "—" : (result.hitAt5() ? "PASS" : "MISS"))
                    .append(" | ").append(result.refusalCase() ? "—" : (result.citationHit() ? "PASS" : "MISS"))
                    .append(" | ").append(result.relevance())
                    .append(" | ").append(result.latencyMs())
                    .append(" |\n");
        }
        long refusalOk = report.cases().stream().filter(EvalReport.CaseResult::refusalCorrect).count();
        sb.append("\n> 拒答题（文档里没有答案）共 ").append(report.refusalCases())
                .append(" 条，其中 ").append(refusalOk).append(" 条正确拒答。")
                .append("这类用例没有期望文档，**不计入 Hit@5 与引用准确率的分母**。\n");
        return sb.toString();
    }

    private String metricRow(String name, String current, String baseline) {
        return "| " + name + " | " + current + " | " + baseline + " |\n";
    }

    private EvalReport readBaseline() {
        Path baseline = REPORT_DIR.resolve("eval-report-baseline.json");
        if (!Files.isRegularFile(baseline)) {
            return null;
        }
        try {
            return objectMapper.readValue(baseline.toFile(), EvalReport.class);
        } catch (Exception e) {
            log.warn("读取基线报告失败（忽略）：{}", e.toString());
            return null;
        }
    }

    private static String pct(double value) {
        return String.format("%.1f%%", value * 100);
    }

    private static double ratio(long part, int total) {
        return total == 0 ? 0d : round((double) part / total);
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    private static String preview(String answer) {
        return truncate(answer == null ? "" : answer.replace("\n", " "), 200);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 一个最小的 MultipartFile 实现：把仓库里的演示文档喂给上传接口，不需要起 Web 服务器。 */
    static final class ByteArrayMultipartFile implements MultipartFile {

        private final String filename;
        private final byte[] content;

        ByteArrayMultipartFile(String filename, byte[] content) {
            this.filename = filename;
            this.content = content;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return filename.endsWith(".md") ? "text/markdown" : "text/plain";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), content);
        }
    }
}
