package com.baiyu.agent.gateway.repository;

import com.baiyu.agent.gateway.entity.LlmUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LlmUsageRecordRepository extends JpaRepository<LlmUsageRecord, String> {

    List<LlmUsageRecord> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant from, Instant to);

    List<LlmUsageRecord> findBySpaceIdAndCreatedAtBetweenOrderByCreatedAtDesc(String spaceId, Instant from, Instant to);

    long countByOutcome(String outcome);

    long countByScene(String scene);
}
