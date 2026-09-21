package com.baiyu.agent.gateway;

import com.baiyu.agent.gateway.entity.LlmUsageRecord;
import com.baiyu.agent.gateway.repository.LlmUsageRecordRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量查询与聚合。聚合放在应用层做（数据量是面试作品集级别），
 * 好处是聚合口径一眼可见、可单测，而不是写一段没人看得懂的 SQL。
 */
@Service
public class UsageQueryService {

    private final LlmUsageRecordRepository repository;

    public UsageQueryService(LlmUsageRecordRepository repository) {
        this.repository = repository;
    }

    public UsageReport report(LocalDate from, LocalDate to, String spaceId, String scene) {
        ZoneId zone = ZoneId.systemDefault();
        Instant fromInstant = (from == null ? LocalDate.of(1970, 1, 1) : from).atStartOfDay(zone).toInstant();
        Instant toInstant = (to == null ? LocalDate.now(zone) : to).plusDays(1).atStartOfDay(zone).toInstant();

        List<LlmUsageRecord> rows = (spaceId == null || spaceId.isBlank())
                ? repository.findByCreatedAtBetweenOrderByCreatedAtDesc(fromInstant, toInstant)
                : repository.findBySpaceIdAndCreatedAtBetweenOrderByCreatedAtDesc(spaceId, fromInstant, toInstant);

        if (scene != null && !scene.isBlank()) {
            rows = rows.stream().filter(r -> scene.equalsIgnoreCase(r.getScene())).toList();
        }

        Map<String, Bucket> byModel = new LinkedHashMap<>();
        Map<String, Bucket> byScene = new LinkedHashMap<>();
        Map<String, Bucket> bySpace = new LinkedHashMap<>();
        Map<String, Bucket> byDay = new LinkedHashMap<>();

        long promptTokens = 0;
        long completionTokens = 0;
        long costMicros = 0;
        long cacheHits = 0;
        long degraded = 0;
        long errors = 0;

        for (LlmUsageRecord row : rows) {
            promptTokens += row.getPromptTokens();
            completionTokens += row.getCompletionTokens();
            costMicros += row.getCostMicros();
            if (row.isCacheHit()) {
                cacheHits++;
            }
            if ("DEGRADED".equals(row.getOutcome())) {
                degraded++;
            }
            if ("ERROR".equals(row.getOutcome())) {
                errors++;
            }
            add(byModel, row.getModelId() == null ? "unknown" : row.getModelId(), row);
            add(byScene, row.getScene() == null ? "unknown" : row.getScene(), row);
            add(bySpace, row.getSpaceId() == null ? "unknown" : row.getSpaceId(), row);
            add(byDay, row.getCreatedAt() == null ? "unknown"
                    : row.getCreatedAt().atZone(zone).toLocalDate().toString(), row);
        }

        Summary summary = new Summary(rows.size(), promptTokens, completionTokens,
                promptTokens + completionTokens, costMicros, cacheHits, degraded, errors);
        return new UsageReport(summary, rows, byModel, byScene, bySpace, byDay);
    }

    private void add(Map<String, Bucket> target, String key, LlmUsageRecord row) {
        Bucket bucket = target.computeIfAbsent(key, k -> new Bucket());
        bucket.calls++;
        bucket.promptTokens += row.getPromptTokens();
        bucket.completionTokens += row.getCompletionTokens();
        bucket.costMicros += row.getCostMicros();
        if (row.isCacheHit()) {
            bucket.cacheHits++;
        }
    }

    public record Summary(long calls, long promptTokens, long completionTokens, long totalTokens,
                          long costMicros, long cacheHits, long degraded, long errors) {
    }

    public static class Bucket {

        private long calls;
        private long promptTokens;
        private long completionTokens;
        private long costMicros;
        private long cacheHits;

        public long getCalls() {
            return calls;
        }

        public long getPromptTokens() {
            return promptTokens;
        }

        public long getCompletionTokens() {
            return completionTokens;
        }

        public long getTotalTokens() {
            return promptTokens + completionTokens;
        }

        public long getCostMicros() {
            return costMicros;
        }

        public long getCacheHits() {
            return cacheHits;
        }
    }

    public record UsageReport(Summary summary, List<LlmUsageRecord> items,
                              Map<String, Bucket> byModel, Map<String, Bucket> byScene,
                              Map<String, Bucket> bySpace, Map<String, Bucket> byDay) {
    }
}
