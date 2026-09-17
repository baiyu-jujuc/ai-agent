package com.baiyu.agent.gateway;

import com.baiyu.agent.gateway.entity.LlmUsageRecord;
import com.baiyu.agent.gateway.repository.LlmUsageRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "计量失败不能影响主链路"这条纪律的单元级证明。
 * 真正端到端的证明在验收里用"把表改名让写入必然失败"来做故障注入。
 */
class UsageRecorderTest {

    private LlmUsageRecordRepository repository;
    private GatewayProperties properties;
    private UsageRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(LlmUsageRecordRepository.class);
        properties = new GatewayProperties();
        recorder = new UsageRecorder(repository, properties);
    }

    @Test
    void recordsWhenMeteringEnabled() {
        recorder.record(sample());
        verify(repository).save(any(LlmUsageRecord.class));
    }

    @Test
    void skipsWhenMeteringDisabled() {
        properties.setMeteringEnabled(false);
        recorder.record(sample());
        verify(repository, never()).save(any());
    }

    @Test
    void swallowsRepositoryFailure() {
        when(repository.save(any(LlmUsageRecord.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("table renamed"));

        assertDoesNotThrow(() -> recorder.record(sample()));
    }

    private LlmUsageRecord sample() {
        LlmUsageRecord record = new LlmUsageRecord();
        record.setModelId("m1");
        record.setScene("KB_QA");
        record.setRouteType("PRIMARY");
        record.setUsageSource("PROVIDER");
        record.setOutcome("SUCCESS");
        record.setCreatedAt(java.time.Instant.now());
        return record;
    }
}
