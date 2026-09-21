package com.baiyu.agent.eval;

import java.util.List;

/** 一次评测跑批的结果，字段与执行文档第 2 节"L4 评测与可观测"的指标一一对应。 */
public record EvalReport(
        String tag,
        String startedAt,
        String finishedAt,
        int caseCount,
        int answerableCases,
        int refusalCases,
        double hitRateAt5,
        double citationAccuracy,
        double refusalAccuracy,
        double avgRelevance,
        long p95LatencyMs,
        long totalCostMicros,
        long avgCostMicros,
        long kbQaCalls,
        long degradedCalls,
        List<CaseResult> cases
) {

    public record CaseResult(
            String id,
            String spaceId,
            String question,
            boolean refusalCase,
            boolean refusalCorrect,
            boolean hitAt5,
            boolean citationHit,
            double relevance,
            long latencyMs,
            String answerPreview,
            List<String> retrievedDocIds,
            List<String> expectedDocIds
    ) {
    }

    /** P95：把延迟排序后取第 ceil(0.95 × n) 个，与验收脚本口径一致。 */
    public static long p95(List<Long> latencies) {
        if (latencies.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = latencies.stream().sorted().toList();
        int index = (int) Math.ceil(0.95d * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
