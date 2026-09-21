package com.baiyu.agent.gateway;

import com.baiyu.agent.gateway.entity.LlmUsageRecord;
import com.baiyu.agent.gateway.repository.LlmUsageRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageQueryServiceTest {

    private LlmUsageRecordRepository repository;
    private UsageQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(LlmUsageRecordRepository.class);
        service = new UsageQueryService(repository);
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(repository.findBySpaceIdAndCreatedAtBetweenOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void summaryAggregatesTokensCostAndOutcomes() {
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(
                record("m1", "KB_QA", "space-1", 100, 50, 200L, "SUCCESS", false),
                record("m1", "KB_QA", "space-1", 200, 80, 360L, "SUCCESS", false),
                record("m2", "CHAT_STREAM", "space-2", 10, 5, 20L, "ERROR", false),
                record("m1", "KB_QA", "space-1", 0, 0, 0L, "SUCCESS", true),
                record("none", "KB_QA", "space-1", 0, 0, 0L, "DEGRADED", false)));

        UsageQueryService.UsageReport report = service.report(LocalDate.now(), LocalDate.now(), null, null);

        UsageQueryService.Summary summary = report.summary();
        assertEquals(5, summary.calls());
        assertEquals(310, summary.promptTokens());
        assertEquals(135, summary.completionTokens());
        assertEquals(445, summary.totalTokens());
        assertEquals(580L, summary.costMicros());
        assertEquals(1, summary.cacheHits());
        assertEquals(1, summary.degraded());
        assertEquals(1, summary.errors());
    }

    @Test
    void aggregatesByModelSceneSpaceAndDay() {
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(
                record("m1", "KB_QA", "space-1", 100, 50, 200L, "SUCCESS", false),
                record("m2", "CHAT_SIMPLE", "space-2", 20, 10, 40L, "SUCCESS", false)));

        UsageQueryService.UsageReport report = service.report(LocalDate.now(), LocalDate.now(), null, null);

        assertTrue(report.byModel().containsKey("m1"));
        assertEquals(1, report.byModel().get("m1").getCalls());
        assertEquals(150, report.byModel().get("m1").getTotalTokens());
        assertEquals(200L, report.byModel().get("m1").getCostMicros());

        assertTrue(report.byScene().containsKey("KB_QA"));
        assertTrue(report.bySpace().containsKey("space-2"));
        assertEquals(1, report.byDay().size());
    }

    @Test
    void filtersBySceneInApplicationLayer() {
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(
                record("m1", "KB_QA", "space-1", 100, 50, 200L, "SUCCESS", false),
                record("m2", "CHAT_SIMPLE", "space-2", 20, 10, 40L, "SUCCESS", false)));

        UsageQueryService.UsageReport report = service.report(LocalDate.now(), LocalDate.now(), null, "kb_qa");

        assertEquals(1, report.summary().calls());
        assertEquals(100, report.summary().promptTokens());
    }

    @Test
    void filtersBySpaceUsingRepositoryMethod() {
        service.report(LocalDate.now(), LocalDate.now(), "space-9", null);
        verify(repository).findBySpaceIdAndCreatedAtBetweenOrderByCreatedAtDesc(eq("space-9"), any(), any());
    }

    @Test
    void emptyRangeReturnsZeroedSummary() {
        UsageQueryService.UsageReport report = service.report(LocalDate.now(), LocalDate.now(), null, null);
        assertEquals(0, report.summary().calls());
        assertEquals(0L, report.summary().costMicros());
        assertTrue(report.items().isEmpty());
    }

    private LlmUsageRecord record(String model, String scene, String spaceId,
                                  int promptTokens, int completionTokens, long costMicros,
                                  String outcome, boolean cacheHit) {
        LlmUsageRecord record = new LlmUsageRecord();
        record.setModelId(model);
        record.setScene(scene);
        record.setSpaceId(spaceId);
        record.setRouteType(cacheHit ? "CACHE" : "PRIMARY");
        record.setPromptTokens(promptTokens);
        record.setCompletionTokens(completionTokens);
        record.setTotalTokens(promptTokens + completionTokens);
        record.setCostMicros(costMicros);
        record.setOutcome(outcome);
        record.setCacheHit(cacheHit);
        record.setUsageSource(TokenUsage.SOURCE_PROVIDER);
        record.setCreatedAt(Instant.now().atZone(ZoneId.systemDefault()).toInstant());
        return record;
    }
}
