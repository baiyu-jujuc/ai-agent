package com.baiyu.agent.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一次模型调用的用量记录。
 *
 * <p>三个设计要点（都是面试会被问到的）：
 * <ol>
 *   <li>{@code promptUnitPrice} / {@code completionUnitPrice} 是<b>单价快照</b>：供应商调价后，
 *       历史成本重算不会失真。</li>
 *   <li>{@code usageSource} 区分 PROVIDER（真实值）与 ESTIMATED（估算值），
 *       两者混在一起统计，成本数据就是假的。</li>
 *   <li>{@code costMicros} 用整数微元存储，避免 double 累加误差。</li>
 * </ol>
 */
@Entity
@Table(name = "llm_usage_record", indexes = {
        @Index(name = "idx_usage_created", columnList = "created_at"),
        @Index(name = "idx_usage_space", columnList = "space_id,created_at"),
        @Index(name = "idx_usage_model", columnList = "model_id,created_at")
})
public class LlmUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "space_id", length = 64)
    private String spaceId;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "scene", length = 32, nullable = false)
    private String scene;

    @Column(name = "model_id", length = 64, nullable = false)
    private String modelId;

    /** PRIMARY / FALLBACK / CACHE / DEGRADED */
    @Column(name = "route_type", length = 16, nullable = false)
    private String routeType;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    /** PROVIDER / ESTIMATED */
    @Column(name = "usage_source", length = 16, nullable = false)
    private String usageSource;

    /** 微元 / 百万 token，写入时的单价快照 */
    @Column(name = "prompt_unit_price", nullable = false)
    private long promptUnitPrice;

    @Column(name = "completion_unit_price", nullable = false)
    private long completionUnitPrice;

    /** 本次调用成本，单位：微元（百万分之一元） */
    @Column(name = "cost_micros", nullable = false)
    private long costMicros;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    /** SUCCESS / ERROR / DEGRADED */
    @Column(name = "outcome", length = 16, nullable = false)
    private String outcome;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "prompt_key", length = 64)
    private String promptKey;

    @Column(name = "prompt_version")
    private Integer promptVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public LlmUsageRecord() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(String spaceId) {
        this.spaceId = spaceId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getRouteType() {
        return routeType;
    }

    public void setRouteType(String routeType) {
        this.routeType = routeType;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getUsageSource() {
        return usageSource;
    }

    public void setUsageSource(String usageSource) {
        this.usageSource = usageSource;
    }

    public long getPromptUnitPrice() {
        return promptUnitPrice;
    }

    public void setPromptUnitPrice(long promptUnitPrice) {
        this.promptUnitPrice = promptUnitPrice;
    }

    public long getCompletionUnitPrice() {
        return completionUnitPrice;
    }

    public void setCompletionUnitPrice(long completionUnitPrice) {
        this.completionUnitPrice = completionUnitPrice;
    }

    public long getCostMicros() {
        return costMicros;
    }

    public void setCostMicros(long costMicros) {
        this.costMicros = costMicros;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(boolean cacheHit) {
        this.cacheHit = cacheHit;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getPromptKey() {
        return promptKey;
    }

    public void setPromptKey(String promptKey) {
        this.promptKey = promptKey;
    }

    public Integer getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(Integer promptVersion) {
        this.promptVersion = promptVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
