package com.baiyu.agent.gateway;

/**
 * 一次模型调用的结果，附带这次调用的计量信息。
 * 业务层通常只关心 {@link #content()}，计量信息用于落库与展示。
 */
public record GatewayResponse(
        String content,
        String modelUsed,
        String routeType,
        boolean cacheHit,
        String usageSource,
        int promptTokens,
        int completionTokens,
        long costMicros,
        long latencyMs,
        String promptKey,
        Integer promptVersion
) {

    /** 主模型 */
    public static final String ROUTE_PRIMARY = "PRIMARY";
    /** 降级到备用模型 */
    public static final String ROUTE_FALLBACK = "FALLBACK";
    /** 语义缓存命中，没有真实调用模型 */
    public static final String ROUTE_CACHE = "CACHE";
    /** 全部候选都失败，返回兜底话术 */
    public static final String ROUTE_DEGRADED = "DEGRADED";

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public static GatewayResponse degraded(String answer, String promptKey, Integer promptVersion, long latencyMs) {
        return new GatewayResponse(answer, "none", ROUTE_DEGRADED, false,
                TokenUsage.SOURCE_ESTIMATED, 0, 0, 0L, latencyMs, promptKey, promptVersion);
    }
}
